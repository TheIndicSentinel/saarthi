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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject

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
) : InferenceEngine {

    @Volatile private var llmInference: LlmInference? = null

    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedMaxTokens: Int = 0

    private val _isReadyFlow = MutableStateFlow(false)
    override val isReadyFlow: Flow<Boolean> = _isReadyFlow.asStateFlow()

    @Volatile override var isReady: Boolean = false
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

    // Hard cap on output tokens. At 2-3 tok/s on CPU, 300 tokens = ~2 minutes max.
    // This prevents Samsung's sustained-CPU watchdog from killing the process.
    private val MAX_NEW_TOKENS = 300

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
                return@withLock
            }

            setReady(false)
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
                // ── Smart Delegate Selection ──
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val totalRamGb = memInfo.totalMem / 1_073_741_824.0
                val isSamsung = Build.MANUFACTURER.contains("samsung", ignoreCase = true)
                
                // Currently Android 16 (API 36) has unstable Vulkan/OpenCL on Samsung S series
                val isSafeForGpu = Build.VERSION.SDK_INT < 36 && !isSamsung && totalRamGb >= 4.0
                
                val preferredBackend = if (isSafeForGpu) {
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
                setReady(true)
                DebugLogger.log("LITERT", "Model ready (Smart Backend: ${preferredBackend.name} | RAM: ${"%.1f".format(totalRamGb)}GB)")
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                DebugLogger.log("LITERT", "Load failed: $msg")
                Timber.e(e, "LiteRT model load failed")
                throw RuntimeException("LiteRT failed to load model: $msg", e)
            }
        }
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> = callbackFlow {
        val inference = llmInference
            ?: throw IllegalStateException("LiteRT engine not initialised.")

        DebugLogger.log("LITERT", "Stream start  promptChars=${prompt.length}")

        // Capture ProducerScope so the lambda (called from MediaPipe's thread) can send/close.
        val producer = this

        // Move the block to the engineDispatcher to ensure serial execution
        launch(engineDispatcher) {
            try {
                generateMutex.withLock {
                    val completer = CompletableDeferred<Unit>()
                    var newTokenCount = 0
                    markGenerationStarted()
                    inference.generateResponseAsync(prompt) { partialResult: String?, done: Boolean ->
                        if (!partialResult.isNullOrEmpty()) {
                            producer.trySend(partialResult)
                            newTokenCount++
                            // Hard cutoff: stop after MAX_NEW_TOKENS to prevent Samsung watchdog kill
                            if (newTokenCount >= MAX_NEW_TOKENS && !completer.isCompleted) {
                                DebugLogger.log("LITERT", "Output cap reached ($MAX_NEW_TOKENS tokens) — closing stream")
                                completer.complete(Unit)
                                producer.close()
                            }
                        }
                        if (done && !completer.isCompleted) {
                            DebugLogger.log("LITERT", "Stream complete")
                            completer.complete(Unit)
                            producer.close()
                        }
                    }
                    // Wait for the async generation to actually FINISH before releasing mutex
                    completer.await()
                    markGenerationEnded()
                }
            } catch (e: Exception) {
                markGenerationEnded()
                if (e !is kotlinx.coroutines.CancellationException) {
                    val msg = e.message?.takeIf { it.isNotBlank() } ?: "Generation failed"
                    DebugLogger.log("LITERT", "Stream error: $msg")
                    close(RuntimeException(msg, e))
                }
            }
        }

        awaitClose {
            DebugLogger.log("LITERT", "Stream cancelled (consumer closed)")
        }
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
        setReady(false)
    }
}
