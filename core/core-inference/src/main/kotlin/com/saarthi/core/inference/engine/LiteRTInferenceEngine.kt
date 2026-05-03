package com.saarthi.core.inference.engine

import com.saarthi.core.inference.InferenceService

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
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
    @Volatile private var isGenerating: Boolean = false
    @Volatile private var isNativeGenerating: Boolean = false
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
     * True if a previous GPU generation crashed AND the 24-hour recovery window has not elapsed.
     *
     * Reduced from 7 days to 24 hours because on Samsung Android 16, BOTH GPU and CPU
     * crash during inference. A long GPU ban just locks the user into CPU crashes.
     * 24 hours is enough to prompt a retry after an app restart while still handling
     * the case where an OEM driver update fixes the issue overnight.
     */
    private fun gpuPreviouslyCrashedDuringGen(): Boolean {
        val crashed = enginePrefs.getBoolean("litert_gpu_gen_crashed", false)
        if (!crashed) return false
        val bannedAt = enginePrefs.getLong("litert_gpu_ban_timestamp", 0L)
        val banAgeMs = System.currentTimeMillis() - bannedAt
        val GPU_BAN_EXPIRY_MS = 24 * 60 * 60 * 1000L  // 24 hours (was 7 days)
        return if (banAgeMs < GPU_BAN_EXPIRY_MS) {
            true  // still within ban window
        } else {
            DebugLogger.log("LITERT", "GPU ban expired after ${banAgeMs / 3_600_000}h — clearing ban, will retry GPU")
            clearGpuGenCrashedFlag()
            false
        }
    }

    // ── Crash loop detection ──────────────────────────────────────────────────
    // If the app crashes 3+ times in a row during generation, both GPU and CPU are
    // failing. Clear all flags to give a clean slate and let the UI show an error
    // instead of endlessly crash-looping.
    private fun getCrashCount(): Int = enginePrefs.getInt("litert_crash_count", 0)

    private fun incrementCrashCount() {
        val count = getCrashCount() + 1
        enginePrefs.edit().putInt("litert_crash_count", count).commit()
        DebugLogger.log("LITERT", "Crash count: $count")
    }

    private fun resetCrashCount() =
        enginePrefs.edit().putInt("litert_crash_count", 0).apply()

    /**
     * If crash count >= 3, inference is crashing on every attempt (both GPU and CPU
     * fail on this device/model combination). Clear all flags, block future generation,
     * and return true so initialize() can exit early without loading the model.
     *
     * The caller should show the user a model-incompatibility error instead of retrying.
     * crashLoopBlocked is reset at the top of initialize() so selecting a different
     * model from the UI gets a fresh slate.
     */
    private fun breakCrashLoopIfNeeded(): Boolean {
        val count = getCrashCount()
        if (count >= 3) {
            DebugLogger.log("LITERT", "CRASH LOOP DETECTED ($count consecutive crashes) — model incompatible with this device, blocking generation")
            enginePrefs.edit()
                .putBoolean("litert_gen_pending", false)
                .putBoolean("litert_init_pending", false)
                .putBoolean("litert_gpu_gen_crashed", false)
                .putLong("litert_gpu_ban_timestamp", 0L)
                .putInt("litert_crash_count", 0)
                .commit()
            crashLoopBlocked = true
            return true
        }
        return false
    }

    private fun markGpuGenCrashed() {
        enginePrefs.edit()
            .putBoolean("litert_gpu_gen_crashed", true)
            .putLong("litert_gpu_ban_timestamp", System.currentTimeMillis())
            .commit()
    }

    private fun clearGpuGenCrashedFlag() =
        enginePrefs.edit()
            .putBoolean("litert_gpu_gen_crashed", false)
            .putLong("litert_gpu_ban_timestamp", 0L)
            .apply()

    private fun markInitStarted() =
        enginePrefs.edit().putBoolean("litert_init_pending", true).commit()

    private fun markInitEnded() =
        enginePrefs.edit().putBoolean("litert_init_pending", false).apply()

    private fun markGenerationStarted() {
        enginePrefs.edit()
            .putBoolean("litert_gen_pending", true)
            .putBoolean("litert_was_using_gpu", usingGpu)  // persist so crash recovery knows the backend
            .commit()
    }

    private fun markGenerationEnded() =
        enginePrefs.edit().putBoolean("litert_gen_pending", false).apply()

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

                // ── Crash loop breaker ────────────────────────────────────────────
                // Must run BEFORE normal crash recovery to prevent infinite loops
                // where both GPU and CPU crash on every launch.
                val loopBroken = breakCrashLoopIfNeeded()
                if (loopBroken) {
                    // Model is incompatible — close any loaded instance and leave
                    // isReady=false so the UI shows "model not ready".
                    markGenerationEnded()
                    markInitEnded()
                    closeInternal()
                    DebugLogger.log("LITERT", "Crash loop: aborting init — model not loaded")
                    return@withLock
                }

                val crashedDuringGen  = wasKilledDuringGeneration()
                val crashedDuringInit = wasKilledDuringInit()
                if (crashedDuringGen || crashedDuringInit) {
                    incrementCrashCount()
                    val wasGpu = wasUsingGpuAtCrash()
                    DebugLogger.log("LITERT", "Crash recovery: gen=$crashedDuringGen init=$crashedDuringInit gpu=$wasGpu — forcing reinit")
                    if (crashedDuringGen && wasGpu) {
                        markGpuGenCrashed()
                        DebugLogger.log("LITERT", "GPU crashed during generation — banning GPU for 24h")
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
            InferenceService.start(context)

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
        val gpuBannedByPriorCrash = gpuPreviouslyCrashedDuringGen()

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
                    "This model has crashed repeatedly and is not compatible with your device. " +
                    "Please return to the model selection screen and choose a different model."
                else
                    "LiteRT engine not initialised."
            )

        // Adaptive timeout: GPU at ~30 tok/s → 90s ≈ 2700 tokens. CPU at ~5 tok/s → 180s.
        val timeoutMs = if (usingGpu) 90_000L else 180_000L
        DebugLogger.log("LITERT", "Stream start  backend=${if (usingGpu) "GPU" else "CPU"}  timeout=${timeoutMs/1000}s  promptChars=${prompt.length}")

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
                        DebugLogger.log("LITERT", "Watchdog: generation timed out after ${timeoutMs/1000}s — clearing native state")
                        // Native is stuck; force-clear so closeInternal() can proceed later.
                        isNativeGenerating = false
                        markGenerationEnded()
                        close(RuntimeException("Response timed out. Please try again."))
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
                        resetCrashCount()
                        markGenerationEnded()
                        DebugLogger.log("LITERT", "Stream complete  tokens≈$tokenCount")
                        // Close any LlmInference instance that was replaced while this
                        // generation was in flight (e.g. user switched models mid-stream).
                        closingLlmInference?.let { old ->
                            runCatching { old.close() }
                                .onFailure { Timber.w(it, "LiteRT deferred-close warning") }
                            closingLlmInference = null
                            DebugLogger.log("LITERT", "Deferred engine instance closed after generation")
                        }
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
