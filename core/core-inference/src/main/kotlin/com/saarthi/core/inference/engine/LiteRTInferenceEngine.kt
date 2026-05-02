package com.saarthi.core.inference.engine

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

    private var usingGpu: Boolean = false
    private var isGenerating: Boolean = false
    private val initMutex = Mutex()
    private val generateMutex = Mutex()

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
     * True if a previous GPU generation crashed AND the 7-day recovery window has not elapsed.
     *
     * After 7 days we automatically retry GPU — this handles the common case where a user
     * receives an OEM driver update (e.g. Samsung Android 16 monthly patch) that fixes the
     * OpenCL compute instability. Without this, the CPU ban would be permanent.
     */
    private fun gpuPreviouslyCrashedDuringGen(): Boolean {
        val crashed = enginePrefs.getBoolean("litert_gpu_gen_crashed", false)
        if (!crashed) return false
        val bannedAt = enginePrefs.getLong("litert_gpu_ban_timestamp", 0L)
        val banAgeMs = System.currentTimeMillis() - bannedAt
        val GPU_BAN_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L  // 7 days
        return if (banAgeMs < GPU_BAN_EXPIRY_MS) {
            true  // still within ban window
        } else {
            // Ban expired — clear flags and retry GPU on next load
            DebugLogger.log("LITERT", "GPU ban expired after ${banAgeMs / 86_400_000}d — clearing ban, will retry GPU")
            clearGpuGenCrashedFlag()
            false
        }
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
            val crashedDuringGen  = wasKilledDuringGeneration()
            val crashedDuringInit = wasKilledDuringInit()
            if (crashedDuringGen || crashedDuringInit) {
                val wasGpu = wasUsingGpuAtCrash()  // read PERSISTED backend, not volatile field
                DebugLogger.log("LITERT", "Crash recovery: gen=$crashedDuringGen init=$crashedDuringInit gpu=$wasGpu — forcing reinit")
                if (crashedDuringGen && wasGpu) {
                    markGpuGenCrashed()
                    DebugLogger.log("LITERT", "GPU crashed during generation — forcing CPU for future loads")
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
            // Gemma LiteRT .task models have a fixed KV cache compiled in.
            // Minimum: 2048 — prevents native crashes on overflow for long prompts.
            // Maximum cap: 8192 — safe budget for flagship devices.
            val effectiveMaxTokens = config.maxTokens.coerceIn(2048, 8192)
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
                // 1. Only try GPU if the profiler says it's 'gpuSafe' (checks Vulkan + RAM).
                // 2. Fall back to CPU if the model size exceeds the safe budget.
                val preferredBackend = if (profile.gpuSafe && sizeMb <= profile.safeModelBudgetMb) {
                    LlmInference.Backend.GPU
                } else {
                    LlmInference.Backend.CPU
                }

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
                throw RuntimeException("LiteRT failed to load model: $msg", e)
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
            ?: throw IllegalStateException("LiteRT engine not initialised.")

        // Adaptive timeout: GPU at ~30 tok/s → 90s = ~2700 tokens. CPU at ~5 tok/s → 180s.
        val timeoutMs = if (usingGpu) 90_000L else 180_000L
        DebugLogger.log("LITERT", "Stream start  backend=${if (usingGpu) "GPU" else "CPU"}  timeout=${timeoutMs/1000}s  promptChars=${prompt.length}")

        generateMutex.withLock {
            isGenerating = true
            markGenerationStarted()
            var tokenCount = 0
            var isFinished = false
            try {
                val watchdog = launch {
                    delay(timeoutMs)
                    if (!isFinished) {
                        DebugLogger.log("LITERT", "Watchdog: generation timed out after ${timeoutMs/1000}s")
                        close(RuntimeException("Response timed out. Please try again."))
                    }
                }

                inference.generateResponseAsync(prompt) { partialResult, done ->
                    // MediaPipe's partialResult can sometimes contain the full accumulated text.
                    // We only want the new part to keep the UI smooth.
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
                        isFinished = true
                        watchdog.cancel()
                        DebugLogger.log("LITERT", "Stream complete  tokens≈$tokenCount")
                        close()
                    }
                }

                awaitClose {
                    watchdog.cancel()
                    isGenerating = false
                    markGenerationEnded()
                }
            } catch (e: Throwable) {
                isGenerating = false
                markGenerationEnded()
                if (e is CancellationException) throw e
                close(RuntimeException("Generation failed: ${e.message}", e))
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
        if (isGenerating) {
            DebugLogger.log("LITERT", "Close deferred: generation in progress")
            return
        }
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
