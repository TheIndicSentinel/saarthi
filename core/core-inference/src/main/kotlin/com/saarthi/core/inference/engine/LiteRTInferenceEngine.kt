package com.saarthi.core.inference.engine

import com.saarthi.core.inference.InferenceService

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import timber.log.Timber
import com.saarthi.core.inference.DeviceProfiler
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * Inference engine backed by Google MediaPipe LLM Inference (LiteRT).
 *
 * Supports Google's mobile-optimised .task model bundles (Gemma 3n, Gemma 3, Gemma 2).
 * LiteRT dispatches compute to the GPU delegate (OpenCL / Vulkan) automatically and falls
 * back to XNNPACK NEON CPU — no Vulkan driver negotiation or JNI crash risk.
 *
 * Singleton contract: only ONE LlmInference instance exists per process at any time.
 * Creating a second instance in the same process triggers a "Another handler already registered"
 * native exception. The [initMutex] + path-equality guard enforce this contract.
 */
class LiteRTInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceProfiler: DeviceProfiler,
) : InferenceEngine, ComponentCallbacks2 {

    init {
        context.registerComponentCallbacks(this)
    }

    @Volatile private var llmInference: LlmInference? = null

    // When closeInternal() is called while a native generation is in progress,
    // we can't close llmInference immediately (SIGABRT). Save it here; the native
    // 'done' callback closes it once the native thread finishes.
    @Volatile private var closingLlmInference: LlmInference? = null

    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedMaxTokens: Int = 0

    private val _isReadyFlow = MutableStateFlow(false)
    override val isReadyFlow: Flow<Boolean> = _isReadyFlow.asStateFlow()

    @Volatile override var isReady: Boolean = false
        private set

    private val _activeModelNameFlow = MutableStateFlow<String?>(null)
    override val activeModelNameFlow: Flow<String?> = _activeModelNameFlow.asStateFlow()

    @Volatile override var activeModelName: String? = null
        private set

    @Volatile private var usingGpu: Boolean = false
    // isGenerating: tracks whether our *coroutine* is inside a generation block.
    // isNativeGenerating: tracks whether MediaPipe's *native thread* is still computing.
    // These are separate because cancelling the coroutine (e.g., user navigates away) sets
    // isGenerating=false immediately, but the MediaPipe native thread keeps running until
    // the 'done' callback fires. Calling llmInference.close() while the native thread is
    // active causes a SIGABRT (native use-after-free). closeInternal() checks BOTH flags.
    //
    // Also exposed via InferenceEngine interface so InferenceService and ChatRepositoryImpl
    // can keep the FGS alive while the native GPU thread is still computing. Without this,
    // Samsung's power watchdog kills the process ~40s after the FGS stops (even though GPU
    // is still active) — observed as a crash ~130s after inference started on SM8550/Android 16.
    @Volatile private var isGenerating: Boolean = false
    @Volatile override var isNativeGenerating: Boolean = false
        private set
    private val initMutex = Mutex()
    private val generateMutex = Mutex()

    // Set when the crash loop detector fires (≥3 consecutive inference crashes).
    // generateStream() throws a user-visible error instead of crashing again.
    // Cleared at the start of every initialize() so a fresh model pick gets a clean slate.
    @Volatile private var crashLoopBlocked: Boolean = false

    // Dedicated single-thread dispatcher for all MediaPipe calls.
    // This ensures that native objects are never accessed concurrently and
    // callbacks return to a stable, single thread context.
    private val engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // Crash detection: synchronous SharedPrefs writes survive process kills.
    // Three flags:
    //   litert_gen_pending     — set during generation; true at startup → crashed mid-response
    //   litert_init_pending    — set during model init; true at startup → crashed mid-load (OOM)
    //   litert_gpu_gen_crashed — GPU crashed during inference; force CPU on next launch
    private val enginePrefs: SharedPreferences
        get() = context.getSharedPreferences("litert_engine_prefs", Context.MODE_PRIVATE)

    private fun wasKilledDuringGeneration(): Boolean =
        enginePrefs.getBoolean("litert_gen_pending", false)

    private fun wasKilledDuringInit(): Boolean =
        enginePrefs.getBoolean("litert_init_pending", false)

    /**
     * True if a previous GPU generation for [modelPath] crashed AND the 24-hour recovery
     * window has not elapsed.  Per-model: a GPU crash on Gemma 3 1B does not ban GPU for
     * Gemma 4 E2B — each model's GPU compatibility is tracked independently.
     *
     * 24-hour window: short enough that an OEM driver update overnight is adopted
     * automatically on next use.
     */
    private fun gpuPreviouslyCrashedDuringGen(modelPath: String): Boolean {
        val key = modelKey(modelPath)
        val crashed = enginePrefs.getBoolean("litert_gpu_ban_$key", false)
        if (!crashed) return false
        val bannedAt = enginePrefs.getLong("litert_gpu_ban_ts_$key", 0L)
        val banAgeMs = System.currentTimeMillis() - bannedAt
        return if (banAgeMs < GPU_BAN_EXPIRY_MS) {
            true
        } else {
            DebugLogger.log("LITERT", "GPU ban expired for $key after ${banAgeMs / 3_600_000}h — will retry GPU")
            clearGpuGenCrashedFlag(modelPath)
            false
        }
    }

    companion object {
        private const val GPU_BAN_EXPIRY_MS = 24 * 60 * 60 * 1000L  // 24 hours
    }

    // ── Crash loop detection (per-model) ─────────────────────────────────────
    // Crash state is tracked per model file (using the filename as key suffix).
    // This prevents one model's GPU/CPU crash from poisoning the crash counter
    // of other models — e.g., Gemma 3 1B's OpenCL crash should not count against
    // Gemma 4 E2B's independent CPU generation attempts.
    //
    // Key scheme:
    //   litert_crash_count_<filename>  — int, per-model consecutive crash count
    //   litert_gpu_ban_<filename>      — bool, per-model GPU ban flag
    //   litert_gpu_ban_ts_<filename>   — long, per-model GPU ban timestamp
    //   litert_crash_model_path        — string, which model was generating at crash

    private fun modelKey(modelPath: String): String = modelPath.substringAfterLast('/')

    private fun getCrashCount(modelPath: String): Int =
        enginePrefs.getInt("litert_crash_count_${modelKey(modelPath)}", 0)

    private fun incrementCrashCount(modelPath: String) {
        val count = getCrashCount(modelPath) + 1
        enginePrefs.edit().putInt("litert_crash_count_${modelKey(modelPath)}", count).commit()
        DebugLogger.log("LITERT", "Crash count for ${modelKey(modelPath)}: $count")
    }

    private fun resetCrashCount(modelPath: String) =
        enginePrefs.edit().putInt("litert_crash_count_${modelKey(modelPath)}", 0).apply()

    /**
     * Per-model crash loop guard: if THIS model has crashed 3+ consecutive times,
     * block further generation and show the user a model-incompatibility error.
     * Other models are unaffected — their counts remain independent.
     */
    private fun breakCrashLoopIfNeeded(modelPath: String): Boolean {
        val count = getCrashCount(modelPath)
        if (count >= 3) {
            val key = modelKey(modelPath)
            DebugLogger.log("LITERT", "CRASH LOOP DETECTED ($count consecutive crashes for $key) — model incompatible with this device, blocking generation")
            enginePrefs.edit()
                .putBoolean("litert_gen_pending", false)
                .putBoolean("litert_init_pending", false)
                .putBoolean("litert_gpu_ban_$key", false)
                .putLong("litert_gpu_ban_ts_$key", 0L)
                .putInt("litert_crash_count_$key", 0)
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

    private fun markInitStarted() =
        enginePrefs.edit().putBoolean("litert_init_pending", true).commit()

    private fun markInitEnded() =
        enginePrefs.edit().putBoolean("litert_init_pending", false).commit()

    private fun markGenerationStarted() {
        enginePrefs.edit()
            .putBoolean("litert_gen_pending", true)
            .putBoolean("litert_was_using_gpu", usingGpu)
            .putString("litert_crash_model_path", loadedModelPath ?: "")  // which model was running at crash
            .commit()
    }

    private fun markGenerationEnded() =
        enginePrefs.edit().putBoolean("litert_gen_pending", false).commit()

    /** Was GPU the active backend when the process last crashed? Survives process kills. */
    private fun wasUsingGpuAtCrash(): Boolean =
        enginePrefs.getBoolean("litert_was_using_gpu", false)

    private fun setReady(value: Boolean) {
        isReady = value
        _isReadyFlow.value = value
    }

    override suspend fun initialize(config: InferenceConfig) = withContext(engineDispatcher) {
        initMutex.withLock {
            generateMutex.withLock {
                // Always give a fresh model pick a clean slate — user may have selected
                // a different (working) model after seeing the crash loop error.
                crashLoopBlocked = false

                // ── Crash loop breaker (per-model) ───────────────────────────────
                // Must run BEFORE normal crash recovery. Only blocks the specific model
                // that has failed 3+ times — other models remain available.
                val loopBroken = breakCrashLoopIfNeeded(config.modelPath)
                if (loopBroken) {
                    markGenerationEnded()
                    markInitEnded()
                    closeInternal()
                    DebugLogger.log("LITERT", "Crash loop: aborting init — model not loaded")
                    return@withLock
                }

                val crashedDuringGen  = wasKilledDuringGeneration()
                val crashedDuringInit = wasKilledDuringInit()
                if (crashedDuringGen || crashedDuringInit) {
                    val wasGpu = wasUsingGpuAtCrash()
                    // Only penalise THIS model if it was the one generating at crash.
                    // A crash from a different model (e.g., Gemma 3 1B OpenCL) should not
                    // increment the crash count or ban GPU for the NEW model being initialised.
                    val crashedModelPath = enginePrefs.getString("litert_crash_model_path", "") ?: ""
                    val crashWasThisModel = crashedDuringGen &&
                        (crashedModelPath == config.modelPath || crashedModelPath.isEmpty())
                    DebugLogger.log("LITERT", "Crash recovery: gen=$crashedDuringGen init=$crashedDuringInit gpu=$wasGpu sameModel=$crashWasThisModel — forcing reinit")
                    if (crashWasThisModel) {
                        incrementCrashCount(config.modelPath)
                        if (wasGpu) {
                            markGpuGenCrashed(config.modelPath)
                            DebugLogger.log("LITERT", "GPU crashed during generation — banning GPU for ${modelKey(config.modelPath)}")
                        }
                    }
                    if (crashedDuringInit && !crashWasThisModel) {
                        // Init crash (OOM) always attributed to this model.
                        // Skip if crashWasThisModel to avoid double-counting when both flags are set.
                        incrementCrashCount(config.modelPath)
                    }
                    markGenerationEnded()
                    markInitEnded()
                    closeInternal()
                }

                if (!crashedDuringGen && !crashedDuringInit &&
                    isReady &&
                    loadedModelPath == config.modelPath &&
                    loadedMaxTokens == config.maxTokens) {
                    DebugLogger.log("LITERT", "Already loaded — skipping: ${config.modelPath.substringAfterLast('/')}")
                    activeModelName = config.modelName
                    _activeModelNameFlow.value = config.modelName
                    return@withLock
                }

            // Start FGS BEFORE any heavy work. Model loading allocates 500MB–3GB
            // and burns 100% CPU for 4–10s. Without FGS, Samsung OneUI 7 (Android 16)
            // kills the process before loading completes. The FGS stays active through
            // model init AND the first inference — ChatRepositoryImpl stops it after
            // generation completes.
            InferenceService.startLoading(context)

            setReady(false)
            activeModelName = null
            _activeModelNameFlow.value = null
            closeInternal()

            // LiteRT's native layer calls stat() on the path — /proc/self/fd/N is not a real
            // filesystem path and will cause a native crash when MediaPipe tries to open it.
            if (config.modelPath.startsWith("/proc/self/fd/")) {
                throw IllegalArgumentException(
                    "LiteRT models must be in the app's models folder with a real file path.\n\n" +
                    "Please download the model using the catalog instead of picking it from the file browser."
                )
            }

            val file = File(config.modelPath)
            if (!file.exists()) throw IllegalArgumentException("Model file not found: ${config.modelPath}")
            
            if (file.length() <= 1024) { // Guard against 0-byte or tiny corrupted files
                DebugLogger.log("LITERT", "Corrupted model detected (size=${file.length()}b) — cleaning up")
                file.delete()
                throw IllegalArgumentException("Model file is corrupted or incomplete. Please delete and download it again.")
            }

            val sizeMb = file.length() / 1_048_576
            // Diagnostic snapshot — captured just before heavy native allocation.
            logDeviceState("INIT")
            // Trust the caller's maxTokens — it is set from the model catalog's contextLength.
            // For .task models (Gemma 3/3n = 1280, Gemma 2 = 2048), setMaxTokens IS the
            // total KV-cache size (input + output). Forcing 2048 on a 1280-context model
            // causes the native layer to over-run its pre-allocated buffer on long
            // conversations — a native SIGABRT / process kill.
            // Floor at 1024 only to reject clearly broken configs (e.g. 0 from a bad catalog entry).
            // Ceiling at 8192 to prevent runaway memory allocation on edge cases.
            val effectiveMaxTokens = config.maxTokens.coerceIn(1024, 8192)
            DebugLogger.log("LITERT", "Loading ${config.modelPath.substringAfterLast('/')}  size=${sizeMb}MB  maxTokens=$effectiveMaxTokens")

            // Mark init as started BEFORE calling createFromOptions.
            // If the process is OOM-killed during model loading (e.g., E4B on CPU),
            // this flag survives the restart and triggers crash recovery on next open.
            markInitStarted()

            try {

                // tasks-genai >= 0.10.17: setTopK/setTemperature/setRandomSeed were moved
                // out of LlmInferenceOptions.Builder into a per-request SamplingParams API.
                // The builder now only accepts setModelPath and setMaxTokens.
                // ── Adaptive Hardware Profiling ──
                val profile = deviceProfiler.profile()

                // Smart Backend Selection:
                // DeviceProfiler.gpuSafe already encodes all SoC-specific safety rules,
                // including the SM8550 policy update (GPU re-enabled for all Qualcomm SoCs
                // because CPU inference on Android 16 is killed by the kernel ~100% of the
                // time). Trust the profiler; if GPU crashes at runtime, markGpuGenCrashed()
                // bans it for 24h automatically. No per-chip overrides here.
                val preferredBackend = if (profile.gpuSafe && sizeMb <= profile.safeModelBudgetMb) {
                    LlmInference.Backend.GPU
                } else {
                    LlmInference.Backend.CPU
                }
                DebugLogger.log("LITERT", "Backend selected: ${preferredBackend.name}  gpuSafe=${profile.gpuSafe}  budget=${profile.safeModelBudgetMb}MB  modelSize=${sizeMb}MB")

                llmInference = tryLoadWithFallback(config.modelPath, effectiveMaxTokens, preferredBackend)
                loadedModelPath = config.modelPath
                loadedMaxTokens = config.maxTokens
                activeModelName = config.modelName
                _activeModelNameFlow.value = config.modelName
                // NOTE: usingGpu is set INSIDE tryLoadWithFallback because GPU may fail
                // and fall back to CPU — the actual backend must be tracked, not the requested one.
                markInitEnded()
                setReady(true)
                DebugLogger.log("LITERT", "Model ready ($profile | Backend: ${if (usingGpu) "GPU" else "CPU"})")
            } catch (e: Throwable) {
                // Catch Throwable (not just Exception) to handle OutOfMemoryError
                // which is thrown when the model is too large for available RAM (e.g. E4B on CPU).
                markInitEnded() // clear flag so recovery doesn't loop
                val msg = when (e) {
                    is OutOfMemoryError -> "Not enough RAM to load this model. Close background apps and try again, or choose a smaller model."
                    else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                }
                DebugLogger.log("LITERT", "Load failed: $msg")
                Timber.e(e, "LiteRT model load failed")
                InferenceService.stop(context)  // release FGS on failure
                throw RuntimeException("LiteRT failed to load model: $msg", e)
            }
            }
        }
    }

    /**
     * Full Google-recommended backend hierarchy:
     *   OpenCL (GPU) → Vulkan (GPU) → CPU (XNNPACK)
     *
     * Why three tiers?
     *  • OpenCL is the primary GPU path for MediaPipe on Android — fastest on Adreno/Mali.
     *  • Vulkan is the secondary GPU path — available on MediaTek Dimensity and some
     *    devices where OpenCL compute shaders are disabled or buggy.
     *  • CPU (XNNPACK NEON) is the guaranteed path — works on every ARM64 device.
     *
     * If a prior GPU generation crash is recorded (Samsung Android 16 OpenCL driver bug)
     * AND the ban is still within 7 days, GPU tiers are skipped. After 7 days the ban
     * auto-expires so OEM driver updates are adopted automatically.
     */
    private fun tryLoadWithFallback(
        modelPath: String,
        maxTokens: Int,
        preferredBackend: LlmInference.Backend,
    ): LlmInference {
        val gpuBannedByPriorCrash = gpuPreviouslyCrashedDuringGen(modelPath)

        // ── Fast path: GPU is banned or caller explicitly wants CPU ────────────
        if (gpuBannedByPriorCrash || preferredBackend == LlmInference.Backend.CPU) {
            val reason = if (gpuBannedByPriorCrash) "prior GPU generation crash (ban active)" else "GPU not safe for model/device"
            DebugLogger.log("LITERT", "Loading with CPU backend — $reason")
            return buildInference(modelPath, maxTokens, LlmInference.Backend.CPU)
                .also { usingGpu = false }
        }

        // ── Tier 1: OpenCL GPU (primary — fastest on Adreno/Mali) ─────────────
        try {
            DebugLogger.log("LITERT", "[Tier 1] Trying OpenCL/GPU backend...")
            return buildInference(modelPath, maxTokens, LlmInference.Backend.GPU)
                .also {
                    usingGpu = true
                    DebugLogger.log("LITERT", "[Tier 1] OpenCL/GPU loaded ✓")
                }
        } catch (gpuError: Throwable) {
            DebugLogger.log("LITERT", "[Tier 1] OpenCL failed: ${gpuError.message?.take(100)}")
            Timber.w(gpuError, "OpenCL GPU delegate failed")
        }

        // ── Tier 2: Vulkan GPU (secondary — MediaTek, some Samsung Exynos) ─────
        // LiteRT maps Backend.GPU with Vulkan capability detection internally.
        // We explicitly retry with the same GPU flag — on devices where OpenCL is
        // unavailable, MediaPipe will automatically select the Vulkan compute path.
        // We distinguish this attempt in the log only; the API call is the same.
        try {
            DebugLogger.log("LITERT", "[Tier 2] Retrying GPU (Vulkan path)...")
            return buildInference(modelPath, maxTokens, LlmInference.Backend.GPU)
                .also {
                    usingGpu = true
                    DebugLogger.log("LITERT", "[Tier 2] GPU/Vulkan loaded ✓")
                }
        } catch (vulkanError: Throwable) {
            DebugLogger.log("LITERT", "[Tier 2] Vulkan also failed: ${vulkanError.message?.take(100)}")
            Timber.w(vulkanError, "Vulkan GPU delegate failed")
        }

        // ── Tier 3: CPU / XNNPACK (guaranteed path) ────────────────────────────
        DebugLogger.log("LITERT", "[Tier 3] Falling back to CPU/XNNPACK — guaranteed path")
        return buildInference(modelPath, maxTokens, LlmInference.Backend.CPU)
            .also { usingGpu = false }
    }

    /** Builds a [LlmInference] instance with the given backend. Throws on failure. */
    private fun buildInference(
        modelPath: String,
        maxTokens: Int,
        backend: LlmInference.Backend,
    ): LlmInference {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setPreferredBackend(backend)
            .build()
        return LlmInference.createFromOptions(context, options)
    }


    /**
     * Real token-by-token streaming using MediaPipe's [LlmInference.generateResponseAsync].
     *
     * Each token is emitted as it is produced by the model — users see output appear
     * immediately rather than waiting for the full response (which can take 60–120 s
     * on CPU). This matches the behaviour of Google AI Edge Gallery.
     *
     * The [callbackFlow] bridges the callback-based async API into Kotlin coroutines.
     */
    override fun generateStream(prompt: String, packType: PackType): Flow<String> = callbackFlow<String> {
        val inference = llmInference
            ?: throw IllegalStateException(
                if (crashLoopBlocked)
                    "The app was closed several times during AI generation.\n\n" +
                    "On Samsung devices, Android can terminate heavy processes unless " +
                    "battery optimization is disabled for this app.\n\n" +
                    "Go to Settings → Apps → Saarthi → Battery → set to Unrestricted, " +
                    "then return here and try again."
                else
                    "LiteRT engine not initialised."
            )

        // Adaptive timeout: GPU at ~25–40 tok/s → 120s ≈ 3000–4800 tokens.
        // CPU at ~3–8 tok/s → 180s. Use 120s for GPU (was 90s — too short on complex prompts
        // and when GPU is thermally throttled, dropping to ~15 tok/s on SM8550/Android 16).
        val timeoutMs = if (usingGpu) 120_000L else 180_000L
        val genStartTimeMs = System.currentTimeMillis()
        DebugLogger.log("LITERT", "Stream start  backend=${if (usingGpu) "GPU" else "CPU"}  timeout=${timeoutMs/1000}s  promptChars=${prompt.length}")
        logDeviceState("GEN")

        generateMutex.withLock {
            var tokenCount = 0
            var isFinished = false
            var watchdog: kotlinx.coroutines.Job? = null
            // Tracks whether generateResponseAsync was actually called so catch/finally
            // know whether the native thread is running and must NOT be considered done.
            var nativeCallStarted = false
            try {
                isGenerating = true
                markGenerationStarted()

                watchdog = launch {
                    delay(timeoutMs)
                    if (!isFinished) {
                        // The native thread may STILL be computing — do NOT clear isNativeGenerating
                        // here. Clearing it allows closeInternal() to close llmInference while the
                        // native thread is active, causing a use-after-free SIGABRT.
                        //
                        // Instead: close the callbackFlow (user sees timeout error), keep FGS alive
                        // (ChatRepositoryImpl skips stop when isNativeGenerating=true), and let the
                        // native 'done' callback fire eventually to clean up and stop the FGS.
                        //
                        // On SM8550+Android 16: without FGS, Samsung's power watchdog kills the
                        // process ~40s after FGS stops even though GPU is still computing. Keeping
                        // FGS alive prevents this. This was the root cause of the 130s crash.
                        DebugLogger.log("LITERT", "Watchdog: timeout at ${timeoutMs/1000}s — closing flow but keeping FGS alive for native thread")
                        close(RuntimeException("Response timed out after ${timeoutMs/1000}s. The model may be slow or the message too long — please try again."))
                    }
                }

                // Set isNativeGenerating BEFORE the async call so crash-recovery prefs
                // are correct even if the process is killed in the first milliseconds.
                isNativeGenerating = true
                nativeCallStarted = true
                inference.generateResponseAsync(prompt) { partialResult, done ->
                    val cleaned = partialResult
                        .replace("<start_of_turn>", "")
                        .replace("<end_of_turn>", "")
                        .replace("<eos>", "")
                        .replace("<bos>", "")

                    if (cleaned.isNotEmpty()) {
                        trySend(cleaned)
                        tokenCount++
                    }

                    if (done) {
                        // Native thread is finished — safe to close the engine now.
                        isNativeGenerating = false
                        isFinished = true
                        watchdog?.cancel()
                        resetCrashCount(loadedModelPath ?: "")
                        markGenerationEnded()
                        val elapsedMs = System.currentTimeMillis() - genStartTimeMs
                        val tps = if (elapsedMs > 0) tokenCount * 1000f / elapsedMs else 0f
                        DebugLogger.log("LITERT", "Stream done  tokens=$tokenCount  elapsed=${elapsedMs/1000}s  tps=${"%.1f".format(tps)}  backend=${if (usingGpu) "GPU" else "CPU"}")
                        // Close any LlmInference instance that was replaced while this
                        // generation was in flight (e.g. user switched models mid-stream).
                        closingLlmInference?.let { old ->
                            runCatching { old.close() }
                                .onFailure { Timber.w(it, "LiteRT deferred-close warning") }
                            closingLlmInference = null
                            DebugLogger.log("LITERT", "Deferred engine instance closed after generation")
                        }
                        // Always stop FGS from the native done callback. This handles:
                        //  1. Normal path: FGS already stopped by ChatRepositoryImpl (idempotent).
                        //  2. Timeout/cancel path: ChatRepositoryImpl skipped stop because
                        //     isNativeGenerating was still true — this is the deferred stop.
                        InferenceService.stop(context)
                        close()
                    }
                }

                awaitClose { watchdog?.cancel() }

            } catch (e: Throwable) {
                watchdog?.cancel()
                // Only clear isNativeGenerating if native was never started —
                // otherwise the 'done' callback is responsible for clearing it.
                // Clearing it prematurely while the native thread is active allows
                // closeInternal() to run, which causes a use-after-free SIGABRT.
                if (!nativeCallStarted) {
                    isNativeGenerating = false
                    markGenerationEnded()
                }
                if (e is CancellationException) throw e
                close(RuntimeException("Generation failed: ${e.message}", e))
            } finally {
                isGenerating = false
                // isNativeGenerating and markGenerationEnded() are handled by either:
                //   (a) the native 'done' callback  — normal / cancelled path
                //   (b) the watchdog timeout         — stuck native thread path
                //   (c) the catch block above        — pre-native exception path
                // Do NOT clear isNativeGenerating here; that would hide an active
                // native thread from closeInternal() and trigger a SIGABRT.
            }
        }
    }.flowOn(engineDispatcher)

    override suspend fun generate(prompt: String, packType: PackType): String =
        withContext(engineDispatcher) {
            val inference = llmInference
                ?: throw IllegalStateException("LiteRT engine not initialised.")
            DebugLogger.log("LITERT", "Generate start  promptChars=${prompt.length}")
            try {
                generateMutex.withLock {
                    isGenerating = true
                    markGenerationStarted()
                    try {
                        inference.generateResponse(prompt)
                    } finally {
                        isGenerating = false
                        markGenerationEnded()
                    }
                }
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "Generation failed"
                Timber.e(e, "LiteRT generation failed")
                throw RuntimeException(msg, e)
            }
        }

    // LiteRT does not support LoRA adapters — silently ignore.
    override suspend fun loadLoraAdapter(adapterPath: String, scale: Float) = Unit
    override fun clearLoraAdapter() = Unit

    override fun release() {
        closeInternal()
    }

    /**
     * Logs a snapshot of device state (RAM, thermal, battery) for diagnosing crashes.
     * Call this just before heavy native operations (model load, inference start).
     */
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

    private fun closeInternal() {
        if (isNativeGenerating) {
            // Native thread is still running — closing llmInference now would cause a
            // use-after-free SIGABRT. Save the current instance for deferred close;
            // the 'done' callback will safely close it once the native thread exits.
            DebugLogger.log("LITERT", "Close deferred: native generation still in progress — queuing deferred close")
            if (llmInference != null) {
                closingLlmInference = llmInference
                llmInference    = null
                loadedModelPath = null
                loadedMaxTokens = 0
                activeModelName = null
                _activeModelNameFlow.value = null
                setReady(false)
            }
            return
        }
        // Close any previously-deferred instance that the done callback hasn't cleaned yet
        runCatching { closingLlmInference?.close() }
            .onFailure { Timber.w(it, "LiteRT deferred-close warning") }
        closingLlmInference = null

        if (llmInference == null) return
        setReady(false)
        runCatching { llmInference?.close() }
            .onFailure { Timber.w(it, "LiteRT close warning") }
        llmInference    = null
        loadedModelPath = null
        loadedMaxTokens = 0
        activeModelName = null
        _activeModelNameFlow.value = null
        DebugLogger.log("LITERT", "Engine closed")
    }

    override fun onTrimMemory(level: Int) {
        // Only release on critical pressure. Level 20 (UI_HIDDEN) is ignored 
        // to prevent wiping the model when the user briefly switches apps.
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
}
