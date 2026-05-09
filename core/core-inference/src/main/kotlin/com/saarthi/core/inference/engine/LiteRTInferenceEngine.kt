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
import kotlinx.coroutines.CoroutineScope
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
enum class CrashStage { MODEL_LOAD, GPU_INIT, CPU_INIT, CREATE_CONVERSATION, WARMUP, GENERATION, CLEANUP }

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

    // Signals when the native inference thread has truly finished (onDone/onError fired).
    // The next generateStream() awaits this before creating a new Conversation.
    // LiteRT only supports ONE active Conversation at a time — we must never call
    // createConversation() while the previous native thread is still running.
    @Volatile private var nativeDoneSignal: CompletableDeferred<Unit>? = null

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

    private fun getCpuCrashCount(modelPath: String) =
        enginePrefs.getInt("litert_cpu_crash_count_${modelKey(modelPath)}", 0)

    private fun incrementCrashCount(modelPath: String, wasGpuOrNpu: Boolean) {
        val key = modelKey(modelPath)
        val count = getCrashCount(modelPath) + 1
        val editor = enginePrefs.edit().putInt("litert_crash_count_$key", count)
        
        if (!wasGpuOrNpu) {
            val cpuCount = getCpuCrashCount(modelPath) + 1
            editor.putInt("litert_cpu_crash_count_$key", cpuCount)
            DebugLogger.log("LITERT", "Crash count for $key: $count (CPU count: $cpuCount)")
        } else {
            DebugLogger.log("LITERT", "Crash count for $key: $count")
        }
        editor.commit()
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
        if (count >= 4) {
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

    private fun markStage(stage: CrashStage) {
        enginePrefs.edit().putString("litert_crash_stage", stage.name).commit()
        DebugLogger.log("LITERT", "[STAGE] Entering stage: $stage")
    }

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

    // ── Sampler config (model-aware) ──────────────────────────────────────────
    //
    // Google's recommended Gemma 3/4 sampling — temp=1.0, topK=64, topP=0.95 —
    // matches the AI Edge Gallery reference implementation. The previous
    // temp=0.7 + topK=40 combination caused 1B models to loop on high-probability
    // sequences (visible repetition in chat). Higher temp + larger topK gives
    // the diversity Gemma was trained for.
    //
    // NPU returns null because QNN handles sampling internally on Hexagon.
    private fun samplerFor(modelPath: String): SamplerConfig? {
        if (usingNpu) return null
        val name = modelPath.lowercase()
        return when {
            name.contains("gemma3") || name.contains("gemma-3") || name.contains("gemma 3") ->
                SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)
            name.contains("gemma4") || name.contains("gemma-4") || name.contains("gemma 4") ->
                SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)
            else ->
                SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8)
        }
    }

    private fun samplerForActiveModel(): SamplerConfig? =
        samplerFor(activeModelName ?: loadedModelPath ?: "")

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

                    val crashStage = enginePrefs.getString("litert_crash_stage", "UNKNOWN")
                    DebugLogger.log("LITERT", "=== CRASH RECOVERY ===")
                    DebugLogger.log("LITERT", "  stage=$crashStage  crashedDuringGen=$crashedDuringGen  crashedDuringInit=$crashedDuringInit")
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
                        incrementCrashCount(config.modelPath, wasGpuOrNpu)
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
                    if (crashedDuringInit && !crashWasThisModel) incrementCrashCount(config.modelPath, false)
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
                                val cpuCrashCount = getCpuCrashCount(config.modelPath)
                if (cpuCrashCount >= 3) {
                    DebugLogger.log("LITERT", "[CRASH] Model marked UNSTABLE after $cpuCrashCount CPU crashes.")
                    throw RuntimeException("Model is unstable on this device. Please use a smaller model.")
                }
                val batteryStatus = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = level * 100 / scale.toFloat()
                val isLowBattery = batteryPct < 20f && batteryPct > 0

                val effectiveMaxTokens: Int = run {
                    val headroomMb = profile.availableRamMb - sizeMb
                    when {
                        isLowBattery -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=256 (BATTERY SAFE MODE: ${batteryPct.toInt()}%)")
                            256
                        }
                        cpuCrashCount >= 2 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=64 (ULTRA-SAFE DEBUG MODE)")
                            64
                        }
                        cpuCrashCount >= 1 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=256 (AUTO-RECOVERY: CPU crash count $cpuCrashCount)")
                            256
                        }
                        config.maxTokens > 0 && config.maxTokens <= 1024 -> config.maxTokens
                        headroomMb < 2048 -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=512 — low RAM headroom=${headroomMb}MB")
                            512
                        }
                        else -> {
                            DebugLogger.log("LITERT", "[TOKENS] maxTokens=512 (Production Default)")
                            512
                        }
                    }
                }
                
                val dynamicThreads = when {
                    isLowBattery -> 1
                    cpuCrashCount >= 1 -> 1 // Aggressive: 1 thread after first CPU crash
                    else -> 2
                }
                
                val batteryGpuBanned = isLowBattery
                val xnnpackBanned = cpuCrashCount >= 2
                
                // CRITICAL: GPU VRAM Safety for large models.
                // Gemma 4 (3.5GB) requires ~4.5GB peak memory for GPU load.
                // If weight size > 60% of available RAM, fallback to CPU to avoid OOM.
                val memoryPressureBannedGpu = sizeMb > (profile.availableRamMb * 0.6)
                if (memoryPressureBannedGpu && sizeMb > 2000) {
                    DebugLogger.log("LITERT", "[GPU] BANNED: Model size (${sizeMb}MB) is too large for available RAM (${profile.availableRamMb}MB). Falling back to CPU.")
                }

                val canUseGpu = profile.gpuSafe && !gpuBanned && !batteryGpuBanned && !memoryPressureBannedGpu
                val canUseNpu = profile.npuSafe && !gpuBanned && !batteryGpuBanned 

                DebugLogger.log("LITERT", "Loading ${config.modelPath.substringAfterLast('/')}  size=${sizeMb}MB  maxTokens=$effectiveMaxTokens")

                markInitStarted(config.modelPath)
                markStage(CrashStage.MODEL_LOAD)
                try {
                    val newEngine = tryLoadWithFallback(config.modelPath, effectiveMaxTokens, profile, gpuBanned || batteryGpuBanned, dynamicThreads, xnnpackBanned)
                    
                    // CRITICAL: Save the active backend state BEFORE we do any heavy operations
                    // like createConversation. If the native driver SIGKILLs during the next step,
                    // the Crash Recovery system will correctly see that the GPU was active and ban it.
                    enginePrefs.edit()
                        .putBoolean("litert_was_using_gpu", usingGpu || usingNpu)
                        .commit()


                    
                    // Undo lazy init per research: create conversation synchronously during init
                    DebugLogger.log("LITERT", "[INIT] Engine loaded. Creating conversation matrix synchronously...")
                    val samplerConfig = samplerFor(config.modelPath)
                    markStage(CrashStage.CREATE_CONVERSATION)
                    DebugLogger.log("LITERT", "[NATIVE] [JNI_ENTER] createConversation (tokens=$effectiveMaxTokens, threads=$dynamicThreads, backend=${backendLabel()})")
                    try {
                        activeConversation = newEngine.createConversation(ConversationConfig(samplerConfig = samplerConfig))
                        DebugLogger.log("LITERT", "[NATIVE] [JNI_EXIT] createConversation SUCCESS")
                    } catch (e: Exception) {
                        DebugLogger.log("LITERT", "[JNI_ERROR] createConversation threw: ${e.message}")
                        throw e
                    } catch (t: Throwable) {
                        DebugLogger.log("LITERT", "[JNI_FATAL] createConversation threw Throwable: ${t.javaClass.simpleName}")
                        throw t
                    }
                    
                    // NO warmup — matches Google AI Edge Gallery reference implementation.
                    // Fire-and-forget warmup corrupts conversation state when the user's
                    // first message arrives before the warmup completes, causing onDone
                    // to never fire and permanently blocking subsequent generations.
                    DebugLogger.log("LITERT", "[INIT] Conversation ready (no warmup — Gallery pattern).")
                    
                    engine = newEngine
                    
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
        dynamicThreads: Int,
        xnnpackBanned: Boolean,
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
        val backendLabel = if (xnnpackBanned) "PLAIN CPU (XNNPACK BANNED)" else "CPU/XNNPACK"
        DebugLogger.log("LITERT", "[CPU] Falling back to $backendLabel  threads=$dynamicThreads (auto-recovery)")
        return buildEngine(modelPath, maxTokens, Backend.CPU(dynamicThreads))
            .also {
                usingNpu = false
                usingGpu = false
            }
    }

    private fun buildEngine(modelPath: String, maxTokens: Int, backend: Backend): Engine {
        val engineConfig = EngineConfig(
            modelPath    = modelPath,
            backend      = backend,
            maxNumTokens = maxTokens,
            cacheDir     = if (backend !is Backend.CPU) context.codeCacheDir.absolutePath else null,
        )
        val e = Engine(engineConfig)
        e.initialize()  // blocking — must be called on background thread
        return e
    }

    // ── generateStream ────────────────────────────────────────────────────────

    /**
     * Token-by-token streaming via persistent [Conversation.sendMessageAsync].
     *
     * PERSISTENT CONVERSATION WITH RECYCLING:
     *   • Uses the class-level [activeConversation] (created during init).
     *   • After [onDone] fires, the old conversation is CLOSED and a fresh one
     *     is created immediately. This recycles the KV cache so the next message
     *     starts with full context budget (fixes the 1-token EOS bug).
     *   • [buildPrompt] in ChatRepositoryImpl embeds the full context (system
     *     prompt + history + user message), so each prompt is self-contained.
     *
     * Why persistent (not per-request):
     *   SM8550 GPU's native driver never fires onDone/onError when a freshly
     *   created Conversation is used. Keeping the conversation alive from init
     *   (where warmup primes the GPU shaders) ensures callbacks fire reliably.
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
            usingNpu -> 60_000L
            usingGpu -> 120_000L
            else     -> 300_000L
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

            try {
                isGenerating = true
                markGenerationStarted()

                // ── Wait for previous native thread (with timeout) ────────────
                nativeDoneSignal?.let { prev ->
                    if (!prev.isCompleted) {
                        DebugLogger.log("LITERT", "[GEN] Waiting for previous native thread to finish...")
                        // First try cancelProcess() to stop the native thread gracefully
                        runCatching<Unit?> { activeConversation?.cancelProcess() }
                        DebugLogger.log("LITERT", "[GEN] Called cancelProcess() — waiting for onError to fire...")
                        kotlinx.coroutines.withTimeoutOrNull(10_000L) { prev.await() }
                            ?: run {
                                // cancelProcess didn't work — force cleanup as last resort
                                DebugLogger.log("LITERT", "[GEN] TIMEOUT after cancelProcess — force recycling conversation")
                                isNativeGenerating = false
                                runCatching<Unit?> { activeConversation?.close() }
                                val sc = samplerForActiveModel()
                                activeConversation = runCatching {
                                    eng.createConversation(ConversationConfig(samplerConfig = sc))
                                }.getOrNull()
                                prev.complete(Unit)
                                DebugLogger.log("LITERT", "[GEN] Force-recycled conversation")
                            }
                    }
                }

                val thisDone = CompletableDeferred<Unit>()
                nativeDoneSignal = thisDone

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

                // Use the persistent activeConversation.
                // If null (shouldn't happen), create one as safety fallback.
                if (activeConversation == null) {
                    val sc = samplerForActiveModel()
                    activeConversation = eng.createConversation(ConversationConfig(samplerConfig = sc))
                    DebugLogger.log("LITERT", "[GEN] Safety fallback: created new conversation")
                }
                val conversation = activeConversation!!

                watchdog = launch {
                    delay(timeoutMs)
                    if (!isFinished) {
                        DebugLogger.log("LITERT", "Watchdog: timeout at ${timeoutMs/1000}s — calling cancelProcess()")
                        // Use cancelProcess() to stop the native thread cleanly.
                        // onError(CancellationException) will handle recycling.
                        runCatching<Unit> { conversation.cancelProcess() }
                    }
                }

                DebugLogger.log("LITERT", "[GEN] Starting sendMessageAsync on persistent conversation...")

                isNativeGenerating = true

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

                        // CRITICAL FIX: onDone is called on the native C++ inference thread.
                        // Calling eng.createConversation() here causes a DEADLOCK because
                        // LiteRT holds an internal mutex until this callback returns.
                        // We must do the recycling asynchronously.
                        CoroutineScope(Dispatchers.IO).launch {
                            runCatching { conversation.close() }
                            val sc = samplerForActiveModel()
                            activeConversation = runCatching {
                                eng.createConversation(ConversationConfig(samplerConfig = sc))
                            }.getOrNull()
                            thisDone.complete(Unit)
                        }

                        resetCrashCount(loadedModelPath ?: "")
                        markGenerationEnded()
                        val elapsedMs = System.currentTimeMillis() - genStartTimeMs
                        val tps = if (elapsedMs > 0) tokenCount * 1000f / elapsedMs else 0f
                        DebugLogger.log("LITERT", "Stream done  tokens=$tokenCount  elapsed=${elapsedMs/1000}s  tps=${"%.1f".format(tps)}  backend=${backendLabel()}")
                        closingEngine?.let { old ->
                            runCatching { old.close() }
                            closingEngine = null
                        }
                        InferenceService.stop(context)
                        close()
                    }

                    override fun onError(error: Throwable) {
                        isNativeGenerating = false
                        isFinished = true
                        watchdog?.cancel()
                        heartbeat?.cancel()

                        // Recycle asynchronously to prevent native deadlock
                        CoroutineScope(Dispatchers.IO).launch {
                            runCatching { conversation.close() }
                            val sc = samplerForActiveModel()
                            activeConversation = runCatching {
                                eng.createConversation(ConversationConfig(samplerConfig = sc))
                            }.getOrNull()
                            thisDone.complete(Unit)
                        }

                        markGenerationEnded()

                        // cancelProcess() triggers CancellationException — treat as
                        // normal stop (user navigated away), not as an error.
                        if (error is java.util.concurrent.CancellationException || error is CancellationException) {
                            val elapsedMs = System.currentTimeMillis() - genStartTimeMs
                            val tps = if (elapsedMs > 0) tokenCount * 1000f / elapsedMs else 0f
                            DebugLogger.log("LITERT", "Stream cancelled  tokens=$tokenCount  elapsed=${elapsedMs/1000}s  tps=${"%.1f".format(tps)}  backend=${backendLabel()}")
                            resetCrashCount(loadedModelPath ?: "")
                            InferenceService.stop(context)
                            close()  // normal close — no error
                        } else {
                            DebugLogger.log("LITERT", "Generation error: ${error.message}")
                            InferenceService.stop(context)
                            close(RuntimeException("Generation failed: ${error.message}", error))
                        }
                    }
                })

                awaitClose {
                    watchdog?.cancel()
                    heartbeat?.cancel()
                    // CRITICAL: Stop the native thread immediately.
                    // Without this, the native thread runs indefinitely (model hasn't
                    // hit EOS) and blocks ALL future generations with FAILED_PRECONDITION.
                    // cancelProcess() triggers onError(CancellationException) which
                    // properly recycles the conversation.
                    // (Matches Gallery's stopResponse() → conversation.cancelProcess())
                    if (isNativeGenerating) {
                        DebugLogger.log("LITERT", "[GEN] Coroutine cancelled — calling cancelProcess() to stop native thread")
                        runCatching { conversation.cancelProcess() }
                    } else {
                        DebugLogger.log("LITERT", "[GEN] Coroutine cancelled — native already finished")
                    }
                }

            } catch (e: Throwable) {
                watchdog?.cancel()
                heartbeat?.cancel()
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
                    val samplerConfig = samplerForActiveModel()
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

    // ── Session reset ─────────────────────────────────────────────────────────

    /**
     * Discards the cached [activeConversation] so the next generation starts
     * with a clean KV cache. Called when the user creates a new chat, switches
     * sessions, or clears history.
     *
     * With per-request conversations in [generateStream], this is a safety net:
     * it ensures no stale conversation lingers if the architecture is ever
     * changed back to persistent sessions.
     */
    override suspend fun resetSession() = withContext(engineDispatcher) {
        generateMutex.withLock {
            val eng = engine ?: return@withLock
            runCatching { activeConversation?.close() }
            val sc = samplerForActiveModel()
            activeConversation = runCatching {
                eng.createConversation(ConversationConfig(samplerConfig = sc))
            }.getOrNull()
            nativeDoneSignal = null
            DebugLogger.log("LITERT", "[SESSION] Session reset — conversation recycled, KV cache cleared")
        }
    }

    override fun release() {
        markStage(CrashStage.CLEANUP)
        closeInternal()
    }

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
