package com.saarthi.core.inference.engine

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.DeviceProfiler
import com.saarthi.core.inference.InferenceService
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inference engine backed by Google AI Edge litertlm-android (same runtime as AI Edge Gallery).
 *
 * Uses the [Engine] + [Conversation] API:
 *   • [Engine] holds the model weights — loaded once, kept alive across requests.
 *   • [Conversation] holds the KV-cache — recreated cheaply per request.
 *
 * Backend hierarchy (auto-selected via [DeviceProfiler]):
 *   1. NPU  — Qualcomm QNN/Hexagon (SM8750 only, requires QNN-compiled .litertlm)
 *   2. GPU  — OpenCL/Vulkan (Adreno, Mali, Tensor GPU)
 *   3. CPU  — XNNPACK NEON (guaranteed path on all ARM64 devices)
 *
 * Key safety invariants preserved from the MediaPipe era:
 *   • Per-model crash tracking (crash count + GPU ban, 24h expiry)
 *   • Generation heartbeat (10s interval) for crash timing diagnosis
 *   • FGS lifecycle via [InferenceService] during model load AND inference
 *   • Deferred engine close — never closes [Engine] while native thread is active
 *   • ComponentCallbacks2 memory pressure monitoring
 */
@Singleton
class LiteRTInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceProfiler: DeviceProfiler,
) : InferenceEngine, ComponentCallbacks2 {

    init {
        context.registerComponentCallbacks(this)
    }

    @Volatile private var engine: Engine? = null
    @Volatile private var activeConversation: Conversation? = null

    // When closeInternal() is called while a native generation is in progress,
    // we cannot close the Engine immediately. Save it here; the native 'done' /
    // 'error' callback closes it once the native thread finishes.
    @Volatile private var closingEngine: Engine? = null
    @Volatile private var closingConversation: Conversation? = null

    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedMaxTokens: Int = 0
    // The actual maxNumTokens passed to the Engine (≤ loadedMaxTokens which stores config value).
    @Volatile private var loadedEffectiveMaxTokens: Int = 0

    private val _isReadyFlow = MutableStateFlow(false)
    override val isReadyFlow: Flow<Boolean> = _isReadyFlow.asStateFlow()

    @Volatile override var isReady: Boolean = false
        private set

    private val _activeModelNameFlow = MutableStateFlow<String?>(null)
    override val activeModelNameFlow: Flow<String?> = _activeModelNameFlow.asStateFlow()

    @Volatile override var activeModelName: String? = null
        private set

    @Volatile private var usingNpu: Boolean = false
    @Volatile private var usingGpu: Boolean = false

    // isGenerating: tracks whether our *coroutine* is inside a generation block.
    // isNativeGenerating: tracks whether the litertlm native thread is still computing.
    // These are separate: cancelling the coroutine sets isGenerating=false immediately,
    // but the native thread keeps running until onDone/onError fires. Closing the Engine
    // while the native thread is active causes a use-after-free crash. closeInternal()
    // checks BOTH flags to decide whether to defer the close.
    @Volatile private var isGenerating: Boolean = false
    @Volatile override var isNativeGenerating: Boolean = false
        private set

    // Set when the crash loop detector fires (≥3 consecutive crashes for this model).
    // generateStream() throws a user-visible error instead of crashing again.
    // Cleared at the start of every initialize() so a fresh model pick gets a clean slate.
    @Volatile private var crashLoopBlocked: Boolean = false

    private val initMutex = Mutex()
    private val generateMutex = Mutex()

    // Single-threaded dispatcher for all Engine calls. Ensures native objects are
    // never accessed concurrently and callbacks return to a stable thread context.
    private val engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // Crash detection: synchronous SharedPrefs writes survive process kills.
    //   litert_gen_pending      — set during generation; true at startup → crashed mid-response
    //   litert_init_pending     — set during model init; true at startup → crashed mid-load (OOM)
    //   litert_was_using_gpu    — which backend was active at crash (GPU/NPU = true)
    //   litert_crash_model_path — which model was generating at crash
    //   litert_conv_ready       — false during createConversation(), true after; crash while false = don't ban GPU
    //   litert_crash_count_*    — per-model consecutive crash count
    //   litert_gpu_ban_*        — per-model GPU ban flag (true = use CPU for 24h)
    //   litert_gpu_ban_ts_*     — per-model GPU ban timestamp
    private val enginePrefs: SharedPreferences
        get() = context.getSharedPreferences("litert_engine_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val GPU_BAN_EXPIRY_MS = 24 * 60 * 60 * 1000L  // 24 hours
    }

    // ── Version-based crash state reset ──────────────────────────────────────

    // On each new APK install, clear all per-session crash tracking so stale crash
    // counts from a previous build don't trigger the crash loop blocker on first run.
    // GPU bans are also cleared — a new build may have different backend config.
    init {
        val prefs = context.getSharedPreferences("litert_engine_prefs", Context.MODE_PRIVATE)
        val currentVersion = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            }
        }.getOrDefault(-1)
        val storedVersion = prefs.getInt("litert_app_version", 0)
        if (currentVersion != -1 && currentVersion != storedVersion) {
            val editor = prefs.edit()
            editor.putBoolean("litert_gen_pending", false)
            editor.putBoolean("litert_init_pending", false)
            editor.putBoolean("litert_conv_ready", true)
            prefs.all.keys.filter { it.startsWith("litert_crash_count_") ||
                                    it.startsWith("litert_gpu_ban_") }.forEach { editor.remove(it) }
            editor.putInt("litert_app_version", currentVersion)
            editor.commit()
            DebugLogger.log("LITERT", "[VERSION] New install v$currentVersion (was v$storedVersion) — crash state cleared")
        }
    }

    // ── Crash detection helpers ───────────────────────────────────────────────

    private fun wasKilledDuringGeneration() =
        enginePrefs.getBoolean("litert_gen_pending", false)

    private fun wasKilledDuringInit() =
        enginePrefs.getBoolean("litert_init_pending", false)

    private fun modelKey(modelPath: String) = modelPath.substringAfterLast('/')

    private fun getCrashCount(modelPath: String) =
        enginePrefs.getInt("litert_crash_count_${modelKey(modelPath)}", 0)

    private fun incrementCrashCount(modelPath: String) {
        val count = getCrashCount(modelPath) + 1
        enginePrefs.edit().putInt("litert_crash_count_${modelKey(modelPath)}", count).commit()
        DebugLogger.log("LITERT", "Crash count for ${modelKey(modelPath)}: $count")
    }

    private fun resetCrashCount(modelPath: String) =
        enginePrefs.edit().putInt("litert_crash_count_${modelKey(modelPath)}", 0).apply()

    private fun gpuPreviouslyCrashedDuringGen(modelPath: String): Boolean {
        val key = modelKey(modelPath)
        if (!enginePrefs.getBoolean("litert_gpu_ban_$key", false)) return false
        val bannedAt = enginePrefs.getLong("litert_gpu_ban_ts_$key", 0L)
        val banAgeMs = System.currentTimeMillis() - bannedAt
        return if (banAgeMs < GPU_BAN_EXPIRY_MS) {
            true
        } else {
            DebugLogger.log("LITERT", "GPU ban expired for $key after ${banAgeMs / 3_600_000}h — retrying GPU")
            clearGpuGenCrashedFlag(modelPath)
            false
        }
    }

    private fun breakCrashLoopIfNeeded(modelPath: String): Boolean {
        val count = getCrashCount(modelPath)
        if (count >= 3) {
            val key = modelKey(modelPath)
            DebugLogger.log("LITERT", "CRASH LOOP DETECTED ($count consecutive crashes for $key) — blocking, crash count preserved until new app version")
            enginePrefs.edit()
                .putBoolean("litert_gen_pending", false)
                .putBoolean("litert_init_pending", false)
                .putBoolean("litert_gpu_ban_$key", false)
                .putLong("litert_gpu_ban_ts_$key", 0L)
                // Intentionally NOT resetting litert_crash_count — keeps the block in
                // place until a new APK version installs (version-based reset in init block).
                .commit()
            crashLoopBlocked = true
            return true
        }
        return false
    }

    private fun markGpuGenCrashed(modelPath: String) {
        val key = modelKey(modelPath)
        enginePrefs.edit()
            .putBoolean("litert_gpu_ban_$key", true)
            .putLong("litert_gpu_ban_ts_$key", System.currentTimeMillis())
            .commit()
    }

    private fun clearGpuGenCrashedFlag(modelPath: String) {
        val key = modelKey(modelPath)
        enginePrefs.edit()
            .putBoolean("litert_gpu_ban_$key", false)
            .putLong("litert_gpu_ban_ts_$key", 0L)
            .apply()
    }

    private fun markInitStarted(modelPath: String) {
        enginePrefs.edit()
            .putBoolean("litert_init_pending", true)
            .putString("litert_crash_model_path", modelPath)
            .commit()
    }

    private fun markInitEnded() =
        enginePrefs.edit().putBoolean("litert_init_pending", false).commit()

    private fun markGenerationStarted() {
        enginePrefs.edit()
            .putBoolean("litert_gen_pending", true)
            .putBoolean("litert_was_using_gpu", usingGpu || usingNpu)
            .putString("litert_crash_model_path", loadedModelPath ?: "")
            .commit()
    }

    private fun markGenerationEnded() =
        enginePrefs.edit().putBoolean("litert_gen_pending", false).commit()

    private fun wasUsingGpuAtCrash() =
        enginePrefs.getBoolean("litert_was_using_gpu", false)

    private fun markConvStarted() =
        enginePrefs.edit().putBoolean("litert_conv_ready", false).commit()

    private fun markConvReady() =
        enginePrefs.edit().putBoolean("litert_conv_ready", true).commit()

    // Default true = conservative (unknown crash assumed post-conv → ban GPU).
    // markConvStarted() sets false before createConversation(); markConvReady() sets true after.
    // A crash in createConversation() leaves the pref false → GPU is NOT banned on next run
    // (second run has cached shaders, may complete in time).
    private fun wasConvReadyAtCrash() =
        enginePrefs.getBoolean("litert_conv_ready", true)

    private fun setReady(value: Boolean) {
        isReady = value
        _isReadyFlow.value = value
    }

    private fun backendLabel() = when {
        usingNpu -> "NPU"
        usingGpu -> "GPU"
        else     -> "CPU"
    }

    // Returns true only when the model file has QNN-compiled layers for this SoC.
    // Generic .litertlm bundles (e.g. gemma-4-E2B-it.litertlm) contain no QNN layers
    // and will throw LiteRtLmJniException immediately if Backend.NPU is attempted.
    // SM8750: all catalog models have a QNN variant so NPU is always worth trying.
    // SM8550: only the sm8550-named bundle works on QNN; generic files fall to CPU.
    private fun isModelNpuOptimised(
        modelPath: String,
        profile: com.saarthi.core.inference.model.DeviceProfile,
    ): Boolean = when (profile.socFamily) {
        com.saarthi.core.inference.model.SocFamily.QUALCOMM_SM8750 -> true
        com.saarthi.core.inference.model.SocFamily.QUALCOMM_SM8550 ->
            modelPath.contains("sm8550", ignoreCase = true)
        else -> false
    }

    // ── Initialize ────────────────────────────────────────────────────────────

    override suspend fun initialize(config: InferenceConfig) = withContext(engineDispatcher) {
        initMutex.withLock {
            generateMutex.withLock {
                crashLoopBlocked = false

                // Crash loop breaker: must run before normal crash recovery.
                val loopBroken = breakCrashLoopIfNeeded(config.modelPath)
                if (loopBroken) {
                    markGenerationEnded()
                    markInitEnded()
                    closeInternal()
                    InferenceService.stop(context)
                    DebugLogger.log("LITERT", "Crash loop: throwing — model not compatible with this device")
                    throw RuntimeException(
                        "This model cannot run on your device.\n\n" +
                        "The AI engine crashed too many times while loading. " +
                        "Please choose a different model, or check for app updates."
                    )
                }

                val crashedDuringGen  = wasKilledDuringGeneration()
                val crashedDuringInit = wasKilledDuringInit()
                if (crashedDuringGen || crashedDuringInit) {
                    val wasGpuOrNpu = wasUsingGpuAtCrash()
                    val crashedModelPath = enginePrefs.getString("litert_crash_model_path", "") ?: ""
                    val crashWasThisModel = (crashedDuringGen || crashedDuringInit) &&
                        (crashedModelPath == config.modelPath || crashedModelPath.isEmpty())
                    val batteryExempt = runCatching {
                        context.getSystemService(PowerManager::class.java)
                            .isIgnoringBatteryOptimizations(context.packageName)
                    }.getOrDefault(true)

                    DebugLogger.log("LITERT", "=== CRASH RECOVERY ===")
                    DebugLogger.log("LITERT", "  crashedDuringGen=$crashedDuringGen  crashedDuringInit=$crashedDuringInit")
                    DebugLogger.log("LITERT", "  wasUsingGPU/NPU=$wasGpuOrNpu  crashedModel=${crashedModelPath.substringAfterLast('/')}")
                    DebugLogger.log("LITERT", "  currentModel=${config.modelPath.substringAfterLast('/')}  sameModel=$crashWasThisModel")
                    DebugLogger.log("LITERT", "  crashCount=${getCrashCount(config.modelPath)}  gpuBanned=${gpuPreviouslyCrashedDuringGen(config.modelPath)}")
                    DebugLogger.log("LITERT", "  batteryOptExempt=$batteryExempt")

                    val likelyCause = when {
                        crashWasThisModel && wasGpuOrNpu ->
                            "GPU/NPU_FAULT: inference caused SIGKILL on GPU/NPU backend — banning GPU for 24h."
                        crashWasThisModel && !wasGpuOrNpu && !batteryExempt ->
                            "OEM_WATCHDOG: CPU inference killed by battery watchdog (no exemption). " +
                            "Grant 'Unrestricted' battery in Settings → Apps → Saarthi → Battery."
                        crashWasThisModel && !wasGpuOrNpu && batteryExempt ->
                            "CPU_CRASH_UNKNOWN: Process killed during CPU generation despite battery exemption. " +
                            "Possible OOM or native LiteRT bug."
                        crashedDuringInit ->
                            "INIT_CRASH: Killed during model load — likely OOM. " +
                            "Close background apps and retry, or choose a smaller model."
                        else -> "UNKNOWN"
                    }
                    DebugLogger.log("LITERT", "  likelyCause=$likelyCause")
                    DebugLogger.log("LITERT", "=== END CRASH RECOVERY ===")

                    if (crashWasThisModel) {
                        incrementCrashCount(config.modelPath)
                        if (wasGpuOrNpu) {
                            val convWasReady = wasConvReadyAtCrash()
                            if (convWasReady) {
                                // Crash happened inside sendMessageAsync — GPU actually ran but died
                                // during token generation. Ban GPU for 24h, fall back to CPU.
                                markGpuGenCrashed(config.modelPath)
                                DebugLogger.log("LITERT", "[CRASH] GPU/NPU crashed post-conv-ready (sendMessageAsync) — banning GPU for ${modelKey(config.modelPath)}")
                            } else {
                                // Crash happened inside createConversation() — GPU never ran a single
                                // token. This is a shader compilation / KV-cache alloc timeout on
                                // first run. cacheDir means second run re-uses compiled shaders and
                                // typically completes 3-5× faster. Do NOT ban GPU.
                                DebugLogger.log("LITERT", "[CRASH] GPU/NPU crashed in createConversation() — NOT banning GPU, cached shaders may fix it next run")
                            }
                        }
                    }
                    if (crashedDuringInit && !crashWasThisModel) incrementCrashCount(config.modelPath)
                    markGenerationEnded()
                    markInitEnded()
                    closeInternal()
                }

                // Already loaded with same config — skip reload.
                if (!crashedDuringGen && !crashedDuringInit &&
                    isReady &&
                    loadedModelPath == config.modelPath &&
                    loadedMaxTokens == config.maxTokens) {
                    DebugLogger.log("LITERT", "Already loaded — skipping: ${config.modelPath.substringAfterLast('/')}")
                    activeModelName = config.modelName
                    _activeModelNameFlow.value = config.modelName
                    return@withLock
                }

                // Start FGS BEFORE any heavy work. Model loading allocates 500MB–4GB
                // and burns 100% CPU for 4–10s. Without FGS, Samsung OneUI 7 (Android 16)
                // kills the process before loading completes.
                InferenceService.startLoading(context)

                setReady(false)
                activeModelName = null
                _activeModelNameFlow.value = null
                closeInternal()

                if (config.modelPath.startsWith("/proc/self/fd/")) {
                    throw IllegalArgumentException(
                        "LiteRT models must be in the app's models folder with a real file path.\n\n" +
                        "Please download the model using the catalog instead of picking it from the file browser."
                    )
                }

                val file = File(config.modelPath)
                if (!file.exists()) throw IllegalArgumentException("Model file not found: ${config.modelPath}")
                if (file.length() <= 1024) {
                    file.delete()
                    throw IllegalArgumentException("Model file is corrupted or incomplete. Please delete and download it again.")
                }

                val sizeMb = file.length() / 1_048_576
                logDeviceState("INIT")
                val profile = deviceProfiler.profile()

                val gpuBanned = gpuPreviouslyCrashedDuringGen(config.modelPath)

                // maxNumTokens = total context window (input + output tokens).
                // 1024 = Google AI Edge Gallery default for all backends including CPU.
                // Reduced to 512 on SM8550 (Snapdragon 8 Gen 2): createConversation() allocates
                // the full KV-cache synchronously. At 1024 tokens the allocation + shader warm-up
                // exceeds the ~5–7s Android process watchdog threshold, causing a SIGKILL before
                // a single token is generated. 512 halves the KV-cache, giving more headroom.
                val effectiveMaxTokens: Int = run {
                    val headroomMb = profile.availableRamMb - sizeMb
                    when {
                        headroomMb < 2048 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=512 — low RAM headroom=${headroomMb}MB  model=${sizeMb}MB")
                            512
                        }
                        else -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=1024  headroom=${headroomMb}MB  model=${sizeMb}MB")
                            1024
                        }
                    }
                }

                DebugLogger.log("LITERT", "Loading ${config.modelPath.substringAfterLast('/')}  size=${sizeMb}MB  maxTokens=$effectiveMaxTokens")

                markInitStarted(config.modelPath)
                try {
                    val newEngine = tryLoadWithFallback(config.modelPath, effectiveMaxTokens, profile, gpuBanned)
                    
                    // Match Google AI Edge Gallery: Create conversation once during init
                    // and store it as part of the "Stateful Session".
                    DebugLogger.log("LITERT", "[INIT] Warm-up: creating persistent conversation...")
                    markConvStarted()
                    val samplerConfig = if (usingNpu) null else SamplerConfig(
                        topK = 40, topP = 0.95, temperature = 0.8
                    )
                    val newConv = newEngine.createConversation(ConversationConfig(samplerConfig = samplerConfig))
                    markConvReady()
                    
                    engine = newEngine
                    activeConversation = newConv
                    
                    loadedModelPath = config.modelPath
                    loadedMaxTokens = config.maxTokens
                    loadedEffectiveMaxTokens = effectiveMaxTokens
                    activeModelName = config.modelName
                    _activeModelNameFlow.value = config.modelName
                    markInitEnded()
                    setReady(true)
                    DebugLogger.log("LITERT", "Model ready & pre-warmed  $profile  backend=${backendLabel()}")
                } catch (e: Throwable) {
                    markInitEnded()
                    val rawMsg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    val msg = when {
                        e is OutOfMemoryError ->
                            "Not enough RAM to load this model. Close background apps and try again, or choose a smaller model."
                        rawMsg.contains("LiteRtLmJni", ignoreCase = true) ||
                        rawMsg.contains("JniException", ignoreCase = true) ->
                            "Your device could not load this model. " +
                            "This usually means the model requires hardware your phone does not support. " +
                            "Please try a different model."
                        else -> rawMsg
                    }
                    DebugLogger.log("LITERT", "Load failed: $rawMsg")
                    Timber.e(e, "LiteRT model load failed")
                    InferenceService.stop(context)
                    throw RuntimeException("LiteRT failed to load model: $msg", e)
                }
            }
        }
    }

    /**
     * Backend selection hierarchy:
     *   NPU  → GPU  → CPU
     *
     * NPU is fastest (Qualcomm QNN/Hexagon) but requires a device-specific .litertlm bundle.
     * GPU covers most flagship/mid-range via OpenCL. CPU is the guaranteed fallback.
     *
     * A prior GPU/NPU crash bans both GPU and NPU for 24h (they share the GPU_BAN flag)
     * and forces CPU, after which an OEM driver update restores GPU automatically.
     */
    private fun tryLoadWithFallback(
        modelPath: String,
        maxTokens: Int,
        profile: com.saarthi.core.inference.model.DeviceProfile,
        gpuBanned: Boolean,
    ): Engine {

        val modelNpuCompatible = isModelNpuOptimised(modelPath, profile)

        // ── NPU (Qualcomm QNN — SM8750/SM8550 with device-specific .litertlm) ──
        // Only attempted when the SoC allows NPU AND the model file has QNN-compiled
        // layers for this SoC. Generic bundles have no QNN layers and throw immediately.
        if (profile.npuSafe && !gpuBanned && modelNpuCompatible) {
            try {
                DebugLogger.log("LITERT", "[NPU] Trying QNN/Hexagon NPU backend...")
                return buildEngine(modelPath, maxTokens, Backend.NPU(context.applicationInfo.nativeLibraryDir))
                    .also {
                        usingNpu = true
                        usingGpu = false
                        DebugLogger.log("LITERT", "[NPU] Loaded ✓")
                    }
            } catch (e: Throwable) {
                DebugLogger.log("LITERT", "[NPU] Failed: ${e.message?.take(120)}")
                Timber.w(e, "NPU backend failed")
            }
        } else if (profile.npuSafe && !modelNpuCompatible) {
            DebugLogger.log("LITERT", "[NPU] Skipped — model has no QNN layers for ${profile.socFamily}: ${modelPath.substringAfterLast('/')}")
        }

        // ── GPU (OpenCL/Vulkan — fast on Adreno, Mali, Tensor GPU) ────────────
        if (profile.gpuSafe && !gpuBanned &&
            (profile.safeModelBudgetMb * 1_048_576L) >= maxTokens.toLong()) {
            try {
                DebugLogger.log("LITERT", "[GPU] Trying OpenCL/Vulkan GPU backend...")
                return buildEngine(modelPath, maxTokens, Backend.GPU())
                    .also {
                        usingNpu = false
                        usingGpu = true
                        DebugLogger.log("LITERT", "[GPU] Loaded ✓")
                    }
            } catch (e: Throwable) {
                DebugLogger.log("LITERT", "[GPU] Failed: ${e.message?.take(120)}")
                Timber.w(e, "GPU backend failed")
            }
        } else {
            val reason = when {
                gpuBanned        -> "prior GPU/NPU crash ban active for ${modelKey(modelPath)}"
                !profile.gpuSafe -> "gpuSafe=false — SoC=${profile.socModel} API=${profile.apiLevel}"
                else             -> "model too large for GPU memory budget"
            }
            DebugLogger.log("LITERT", "[GPU] Skipped — $reason")
        }

        // ── CPU (XNNPACK NEON — guaranteed path on all ARM64 devices) ──────────
        DebugLogger.log("LITERT", "[CPU] Falling back to CPU/XNNPACK  threads=${profile.recommendedThreads}")
        return buildEngine(modelPath, maxTokens, Backend.CPU(profile.recommendedThreads))
            .also {
                usingNpu = false
                usingGpu = false
            }
    }

    private fun buildEngine(modelPath: String, maxTokens: Int, backend: Backend): Engine {
        val engineConfig = EngineConfig(
            modelPath    = modelPath,
            backend      = backend,
            // Google AI Edge Gallery explicitly configures vision & audio backends.
            // Gemma 3 is a multimodal model. If these are omitted, the native C++ litertlm
            // engine encounters a null pointer when allocating the multimodal KV-cache
            // and throws an uncatchable SIGKILL during createConversation().
            visionBackend = Backend.GPU(), // Must be GPU for Gemma 3
            audioBackend  = Backend.CPU(), // Must be CPU for Gemma 3
            maxNumTokens = maxTokens,
            // CRITICAL: We MUST explicitly set cacheDir to the internal app cache directory.
            // When cacheDir is null, the native litertlm C++ backend defaults to using the
            // directory where the model is located to store JIT/OpenCL shader caches.
            // Since our models are located in EXTERNAL storage (/storage/emulated/0/...),
            // Android 16's strict SELinux policies interpret a native executable thread trying
            // to write binary cache data to an external SD card path as a massive security
            // violation and issues an immediate, uncatchable SIGKILL.
            // By explicitly pointing to the internal cache dir, we give the Adreno driver
            // a safe, SELinux-approved location to write its OpenCL binaries.
            cacheDir = context.cacheDir.absolutePath,
        )
        val e = Engine(engineConfig)
        e.initialize()  // blocking — must be called on background thread
        return e
    }

    // ── generateStream ────────────────────────────────────────────────────────

    /**
     * Real token-by-token streaming via [Conversation.sendMessageAsync].
     *
     * A new [Conversation] is created per request — this is cheap (allocates KV-cache only;
     * model weights stay in the [Engine]). The conversation is closed in [MessageCallback.onDone]
     * or [MessageCallback.onError] to free the KV-cache, or in [awaitClose] on cancellation.
     */
    override fun generateStream(prompt: String, packType: PackType): Flow<String> = callbackFlow<String> {
        val eng = engine
            ?: throw IllegalStateException(
                if (crashLoopBlocked)
                    "This model cannot run on your device. Please go back and choose a different model."
                else
                    "LiteRT engine not initialised."
            )

        val timeoutMs = when {
            usingNpu -> 60_000L   // NPU: fastest, ~60–100 tok/s
            usingGpu -> 120_000L  // GPU: ~25–40 tok/s
            else     -> 300_000L  // CPU: 5 min — allows 512 tokens at ~2 tok/s on slow devices
        }
        val genStartTimeMs = System.currentTimeMillis()
        DebugLogger.log("LITERT", "Stream start  backend=${backendLabel()}  timeout=${timeoutMs/1000}s  promptChars=${prompt.length}")
        logDeviceState("GEN")
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            val exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
            DebugLogger.log("LITERT", "[GEN] batteryOptExempt=$exempt  model=${loadedModelPath?.substringAfterLast('/') ?: "?"}")
        }.onFailure { DebugLogger.log("LITERT", "[GEN] batteryOptExempt=unavailable") }

        generateMutex.withLock {
            var tokenCount = 0
            var isFinished = false
            var watchdog: kotlinx.coroutines.Job? = null
            var heartbeat: kotlinx.coroutines.Job? = null
            var nativeCallStarted = false
            var conversation: Conversation? = null

            try {
                isGenerating = true
                markGenerationStarted()

                // Periodic heartbeat — fires every 10s. Each line records elapsed time,
                // token count, backend, and system state. If the process is killed (SIGKILL),
                // the last heartbeat before silence shows exactly how far generation reached.
                heartbeat = launch {
                    var tick = 0
                    while (!isFinished) {
                        delay(10_000L)
                        if (isFinished) break
                        tick++
                        val elapsedS = (System.currentTimeMillis() - genStartTimeMs) / 1000
                        DebugLogger.log("LITERT", "[HEARTBEAT $tick] elapsed=${elapsedS}s  tokens=$tokenCount  backend=${backendLabel()}")
                        logDeviceState("HB$tick")
                    }
                }

                watchdog = launch {
                    delay(timeoutMs)
                    if (!isFinished) {
                        isFinished = true
                        DebugLogger.log("LITERT", "Watchdog: timeout at ${timeoutMs/1000}s — force-closing engine to terminate native XNNPACK thread")
                        // Without force-close, the XNNPACK thread runs 10+ min → OS SIGKILL.
                        // engine.close() during active inference may SIGSEGV the native thread;
                        // crash recovery handles it on next launch.
                        isNativeGenerating = false
                        runCatching { conversation?.close() }
                        engine = null
                        loadedModelPath = null
                        loadedMaxTokens = 0
                        activeModelName = null
                        _activeModelNameFlow.value = null
                        setReady(false)
                        runCatching { eng.close() }
                        markGenerationEnded()
                        InferenceService.stop(context)
                        close(RuntimeException(
                            "Response timed out after ${timeoutMs / 1000}s. " +
                            "CPU-only inference is slow on this device — try a shorter message."
                        ))
                    }
                }

                // Use the persistent pre-warmed conversation. 
                // Matches Google AI Edge Gallery behavior.
                val conversation = activeConversation 
                    ?: throw IllegalStateException("Conversation not pre-warmed during initialization")

                DebugLogger.log("LITERT", "[GEN] starting sendMessageAsync using persistent session...")

                // Set isNativeGenerating BEFORE the async call so crash-recovery prefs
                // are correct even if the process is killed in the first milliseconds.
                isNativeGenerating = true
                nativeCallStarted  = true

                conversation.sendMessageAsync(prompt, object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val cleaned = message.toString().filterSpecialTokens()
                        if (cleaned.isNotEmpty()) {
                            trySend(cleaned)
                            tokenCount++
                        }
                    }

                    override fun onDone() {
                        isNativeGenerating = false
                        isFinished = true
                        watchdog?.cancel()
                        heartbeat?.cancel()
                        // Persistent conversation: DO NOT close here. Matches Google AI Edge Gallery.
                        resetCrashCount(loadedModelPath ?: "")
                        markGenerationEnded()
                        val elapsedMs = System.currentTimeMillis() - genStartTimeMs
                        val tps = if (elapsedMs > 0) tokenCount * 1000f / elapsedMs else 0f
                        DebugLogger.log("LITERT", "Stream done  tokens=$tokenCount  elapsed=${elapsedMs/1000}s  tps=${"%.1f".format(tps)}  backend=${backendLabel()}")
                        // Close any Engine that was replaced while this generation was in flight
                        // (e.g., user switched models mid-stream via closeInternal deferred-close).
                        closingEngine?.let { old ->
                            runCatching { old.close() }
                                .onFailure { Timber.w(it, "LiteRT deferred-close warning") }
                            closingEngine = null
                            DebugLogger.log("LITERT", "Deferred engine instance closed after generation")
                        }
                        // Always stop FGS from the native done callback. Handles:
                        //  1. Normal path: FGS already stopped by ChatRepositoryImpl (idempotent).
                        //  2. Timeout/cancel path: ChatRepositoryImpl skipped stop because
                        //     isNativeGenerating was still true — this is the deferred stop.
                        InferenceService.stop(context)
                        close()
                    }

                    override fun onError(error: Throwable) {
                        isNativeGenerating = false
                        isFinished = true
                        watchdog?.cancel()
                        heartbeat?.cancel()
                        // Keep conversation alive for potential retry
                        markGenerationEnded()
                        DebugLogger.log("LITERT", "Generation error: ${error.message}")
                        InferenceService.stop(context)
                        close(RuntimeException("Generation failed: ${error.message}", error))
                    }
                })

                awaitClose {
                    watchdog?.cancel()
                    heartbeat?.cancel()
                    // Persistent conversation: DO NOT close here.
                }

            } catch (e: Throwable) {
                watchdog?.cancel()
                heartbeat?.cancel()
                // Only clear isNativeGenerating if native was never started — otherwise
                // the 'done/error' callback is responsible for clearing it.
                if (!nativeCallStarted) {
                    isNativeGenerating = false
                    markGenerationEnded()
                }
                // Persistent conversation: DO NOT close here.
                if (e is CancellationException) throw e
                close(RuntimeException("Generation failed: ${e.message}", e))
            } finally {
                isGenerating = false
            }
        }
    }.flowOn(engineDispatcher)

    // ── generate (one-shot) ───────────────────────────────────────────────────

    override suspend fun generate(prompt: String, packType: PackType): String =
        withContext(engineDispatcher) {
            val eng = engine ?: throw IllegalStateException("LiteRT engine not initialised.")
            DebugLogger.log("LITERT", "Generate start  promptChars=${prompt.length}")
            generateMutex.withLock {
                isGenerating = true
                markGenerationStarted()
                try {
                    val samplerConfig = if (usingNpu) null else SamplerConfig(40, 0.95, 0.8)
                    val conv = eng.createConversation(ConversationConfig(samplerConfig = samplerConfig))
                    try {
                        val deferred = CompletableDeferred<String>()
                        val sb = StringBuilder()
                        conv.sendMessageAsync(prompt, object : MessageCallback {
                            override fun onMessage(m: Message) {
                                sb.append(m.toString().filterSpecialTokens())
                            }
                            override fun onDone() { deferred.complete(sb.toString()) }
                            override fun onError(e: Throwable) { deferred.completeExceptionally(e) }
                        })
                        deferred.await()
                    } finally {
                        runCatching { conv.close() }
                    }
                } catch (e: Exception) {
                    val msg = e.message?.takeIf { it.isNotBlank() } ?: "Generation failed"
                    Timber.e(e, "LiteRT generation failed")
                    throw RuntimeException(msg, e)
                } finally {
                    isGenerating = false
                    markGenerationEnded()
                }
            }
        }

    // ── LoRA — not supported in litertlm-android ─────────────────────────────
    override suspend fun loadLoraAdapter(adapterPath: String, scale: Float) = Unit
    override fun clearLoraAdapter() = Unit

    override fun release() { closeInternal() }

    // ── Device state logging ──────────────────────────────────────────────────

    private fun logDeviceState(tag: String) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            DebugLogger.log("LITERT", "[$tag] RAM: avail=${mi.availMem/1_048_576}MB  total=${mi.totalMem/1_048_576}MB  lowMem=${mi.lowMemory}")
        } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(PowerManager::class.java)
                val thermalName = when (pm.currentThermalStatus) {
                    0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"
                    3 -> "SEVERE"; 4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"
                    else -> "UNKNOWN(${pm.currentThermalStatus})"
                }
                DebugLogger.log("LITERT", "[$tag] Thermal: $thermalName")
            }
        } catch (_: Exception) {}
        try {
            val bm = context.getSystemService(BatteryManager::class.java)
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            DebugLogger.log("LITERT", "[$tag] Battery: ${pct}%  charging=${bm.isCharging}")
        } catch (_: Exception) {}
    }

    // ── Engine lifecycle ──────────────────────────────────────────────────────

    private fun closeInternal() {
        if (isNativeGenerating) {
            // Native thread is still running — closing the Engine now would cause a
            // use-after-free crash. Save it for deferred close; the 'done/error'
            // callback will close it once the native thread exits.
            DebugLogger.log("LITERT", "Close deferred: native generation still in progress")
            if (engine != null) {
                closingEngine = engine
                closingConversation = activeConversation
                
                engine             = null
                activeConversation = null
                
                loadedModelPath = null
                loadedMaxTokens = 0
                activeModelName = null
                _activeModelNameFlow.value = null
                setReady(false)
            }
            return
        }
        
        runCatching { closingConversation?.close() }
        closingConversation = null
        runCatching { closingEngine?.close() }
            .onFailure { Timber.w(it, "LiteRT deferred-close warning") }
        closingEngine = null

        if (engine == null) return
        setReady(false)
        
        runCatching { activeConversation?.close() }
        runCatching { engine?.close() }
            .onFailure { Timber.w(it, "LiteRT close warning") }
            
        engine             = null
        activeConversation = null
        
        loadedModelPath = null
        loadedMaxTokens = 0
        activeModelName = null
        _activeModelNameFlow.value = null
        DebugLogger.log("LITERT", "Engine & Conversation closed")
    }

    // ── Memory pressure callbacks ─────────────────────────────────────────────

    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            DebugLogger.log("LITERT", "CRITICAL system pressure (level=$level) — releasing engine memory")
            release()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun onLowMemory() {
        DebugLogger.log("LITERT", "CRITICAL LOW MEMORY — emergency release")
        release()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun String.filterSpecialTokens() = this
        .replace("<start_of_turn>", "")
        .replace("<end_of_turn>", "")
        .replace("<eos>", "")
        .replace("<bos>", "")
}
