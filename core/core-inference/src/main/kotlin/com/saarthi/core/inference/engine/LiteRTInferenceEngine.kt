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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val initMutex = Mutex()
    private val generateMutex = Mutex()

    // Dedicated single-thread dispatcher for all MediaPipe calls.
    // This ensures that native objects are never accessed concurrently and
    // callbacks return to a stable, single thread context.
    private val engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // Crash detection: a synchronous SharedPrefs write survives process kill.
    // If 'litert_gen_pending' is true at startup, the previous generation never
    // completed cleanly → the C++ LlmInference state may be corrupted.
    private val enginePrefs: SharedPreferences
        get() = context.getSharedPreferences("litert_engine_prefs", Context.MODE_PRIVATE)

    private fun wasKilledDuringGeneration(): Boolean =
        enginePrefs.getBoolean("litert_gen_pending", false)

    private fun markGenerationStarted() =
        enginePrefs.edit().putBoolean("litert_gen_pending", true).commit()

    private fun markGenerationEnded() =
        enginePrefs.edit().putBoolean("litert_gen_pending", false).apply()

    // Hard cap on output tokens. Prevents Samsung sustained-CPU watchdog from killing the process.
    // 512 tokens at 0.4-3 tok/s = 2-21 minutes max per response — safe and useful.
    private val MAX_NEW_TOKENS = 512

    private fun setReady(value: Boolean) {
        isReady = value
        _isReadyFlow.value = value
    }

    override suspend fun initialize(config: InferenceConfig) = withContext(engineDispatcher) {
        initMutex.withLock {
            // If we crashed mid-generation, the C++ state is corrupt — force full reinit.
            val crashedDuringGen = wasKilledDuringGeneration()
            if (crashedDuringGen) {
                DebugLogger.log("LITERT", "Crash recovery: forcing engine reinit")
                markGenerationEnded()
                closeInternal()
            }

            if (!crashedDuringGen &&
                isReady &&
                loadedModelPath == config.modelPath &&
                loadedMaxTokens == config.maxTokens) {
                DebugLogger.log("LITERT", "Already loaded — skipping: ${config.modelPath.substringAfterLast('/')}")
                // Ensure model name is still correctly set if re-initialized with same path
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

            val sizeMb = file.length() / 1_048_576
            // Gemma LiteRT .task models have a fixed KV cache compiled in. 1280 is the
            // minimum that all official Google Gemma mobile models are compiled against.
            // DO NOT lower below 1280 — it causes silent native crashes on overflow.
            val effectiveMaxTokens = config.maxTokens.coerceAtLeast(1280)
            DebugLogger.log("LITERT", "Loading ${config.modelPath.substringAfterLast('/')}  size=${sizeMb}MB  maxTokens=$effectiveMaxTokens")

            try {
                // tasks-genai >= 0.10.17: setTopK/setTemperature/setRandomSeed were moved
                // out of LlmInferenceOptions.Builder into a per-request SamplingParams API.
                // The builder now only accepts setModelPath and setMaxTokens.
                // ── Adaptive Hardware Profiling ──
                val profile = deviceProfiler.profile()
                val isSamsung = profile.manufacturer.contains("samsung", ignoreCase = true)
                
                // Smart Backend Selection:
                // 1. Only try GPU if the profiler says it's 'gpuSafe' (checks Vulkan + RAM).
                // 2. Fall back to CPU if the model size exceeds the safe budget.
                val preferredBackend = if (profile.gpuSafe && sizeMb <= profile.safeModelBudgetMb) {
                    LlmInference.Backend.GPU
                } else {
                    LlmInference.Backend.CPU
                }

                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(config.modelPath)
                    .setMaxTokens(effectiveMaxTokens)
                    .setPreferredBackend(preferredBackend)
                    .build()

                llmInference    = LlmInference.createFromOptions(context, options)
                loadedModelPath = config.modelPath
                loadedMaxTokens = config.maxTokens
                activeModelName = config.modelName
                _activeModelNameFlow.value = config.modelName
                setReady(true)
                DebugLogger.log("LITERT", "Model ready ($profile | Backend: ${preferredBackend.name})")
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                DebugLogger.log("LITERT", "Load failed: $msg")
                Timber.e(e, "LiteRT model load failed")
                throw RuntimeException("LiteRT failed to load model: $msg", e)
            }
        }
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> = flow {
        val inference = llmInference
            ?: throw IllegalStateException("LiteRT engine not initialised.")

        DebugLogger.log("LITERT", "Stream start (sync mode)  promptChars=${prompt.length}")

        val fullResponse = withContext(engineDispatcher) {
            generateMutex.withLock {
                markGenerationStarted()
                try {
                    // SYNC generation: single blocking call, no native async callbacks.
                    // This eliminates the cross-thread JNI callback crash on Android 16/Samsung.
                    inference.generateResponse(prompt)
                } catch (e: Exception) {
                    markGenerationEnded()
                    throw e
                } finally {
                    markGenerationEnded()
                }
            }
        }

        if (fullResponse.isNullOrBlank()) {
            DebugLogger.log("LITERT", "Empty response from model")
            emit("I couldn't generate a response. Please try again.")
            return@flow
        }

        // Clean up the response (remove end of turn tokens)
        val cleanedResponse = fullResponse
            .replace("<end_of_turn>", "")
            .replace("<eos>", "")
            .trim()

        DebugLogger.log("LITERT", "Generation complete  chars=${cleanedResponse.length}")

        // Simulate streaming by emitting word-by-word chunks.
        // This preserves the chat UI's typewriter effect.
        val words = cleanedResponse.split(" ")
        for (i in words.indices) {
            val chunk = if (i == 0) words[i] else " ${words[i]}"
            emit(chunk)
        }

        DebugLogger.log("LITERT", "Stream emission complete")
    }

    override suspend fun generate(prompt: String, packType: PackType): String =
        withContext(engineDispatcher) {
            val inference = llmInference
                ?: throw IllegalStateException("LiteRT engine not initialised.")
            DebugLogger.log("LITERT", "Generate start  promptChars=${prompt.length}")
            try {
                generateMutex.withLock {
                    inference.generateResponse(prompt)
                }
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "Generation failed"
                Timber.e(e, "LiteRT generation failed")
                throw RuntimeException(msg, e)
            } finally {
                // runCatching { if (wakeLock.isHeld) wakeLock.release() }
            }
        }

    // LiteRT does not support LoRA adapters — silently ignore.
    override suspend fun loadLoraAdapter(adapterPath: String, scale: Float) = Unit
    override fun clearLoraAdapter() = Unit

    override fun release() {
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { llmInference?.close() }
            .onFailure { Timber.w(it, "LiteRT close warning") }
        llmInference    = null
        loadedModelPath = null
        loadedMaxTokens = 0
        activeModelName = null
        _activeModelNameFlow.value = null
        setReady(false)
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
