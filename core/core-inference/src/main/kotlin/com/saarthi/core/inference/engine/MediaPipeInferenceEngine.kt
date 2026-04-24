package com.saarthi.core.inference.engine

import android.app.ActivityManager
import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe LLM inference engine wrapper.
 *
 * Crash prevention: MediaPipe's native layer allows only ONE active LlmInference
 * handler per process.  [generateResponseAsync] registers a persistent native handler
 * that must be freed via [close] — cancelling the coroutine mid-generation does NOT
 * free it, causing "Another handler is already registered" on the next call.
 *
 * Fix: [generateStream] uses the synchronous [generateResponse] instead.  It runs
 * entirely on [Dispatchers.IO] inside [initMutex] so there is no persistent handler
 * to leak.  [forceDestroyEngine] nulls the reference BEFORE closing so a crash inside
 * close() cannot leave stale state.
 */
@Singleton
class MediaPipeInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : InferenceEngine {

    private var llmInference: LlmInference? = null

    override var isReady: Boolean = false
        private set

    private var currentModelPath: String? = null

    /** Serializes all init/release/generate so native handler is never double-registered. */
    private val initMutex = Mutex()

    // ─────────────────────────────────── initialize ──────────────────────────

    override suspend fun initialize(config: InferenceConfig) = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (isReady && config.modelPath == currentModelPath) {
                DebugLogger.log("MEDIAPIPE", "Already loaded: ${config.modelPath}")
                return@withLock
            }

            // Always destroy first — prevents "Another handler" crash
            forceDestroyEngine()

            validateModel(config.modelPath)

            DebugLogger.log("MEDIAPIPE", "Building LlmInference options for ${config.modelPath}")
            val options = LlmInferenceOptions.builder()
                .setModelPath(config.modelPath)
                .setMaxTokens(config.maxTokens)
                .setMaxTopK(config.topK)
                .build()

            llmInference = try {
                DebugLogger.log("MEDIAPIPE", "Calling createFromOptions (native)…")
                System.out.flush()
                val engine = LlmInference.createFromOptions(context, options)
                DebugLogger.log("MEDIAPIPE", "createFromOptions succeeded")
                engine
            } catch (e: Throwable) {
                DebugLogger.log("MEDIAPIPE", "createFromOptions FAILED: ${e.javaClass.simpleName}: ${e.message}")
                forceDestroyEngine()   // clean up any partial alloc
                throw mapError(e, config.modelPath)
            }

            isReady = true
            currentModelPath = config.modelPath
            Timber.d("MediaPipe engine ready: ${config.modelPath}")
        }
    }

    // ─────────────────────────────────── generation ──────────────────────────

    /**
     * Generates a full response synchronously on IO and emits it as a single token.
     *
     * Using [LlmInference.generateResponse] (synchronous) instead of
     * [generateResponseAsync] eliminates the "Another handler is already registered"
     * crash entirely: synchronous generation does not register a persistent native
     * progress listener, so there is nothing to leak when the coroutine is cancelled
     * (e.g. on screen lock).  The mutex ensures only one generation runs at a time.
     */
    override fun generateStream(prompt: String, packType: PackType): Flow<String> = flow {
        val response = withContext(Dispatchers.IO) {
            initMutex.withLock {
                val engine = llmInference
                    ?: throw IllegalStateException("MediaPipe engine not initialised")
                DebugLogger.log("MEDIAPIPE", "generateStream: starting synchronous generation")
                try {
                    engine.generateResponse(prompt)
                } catch (e: Throwable) {
                    DebugLogger.log("MEDIAPIPE", "generateStream FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    if (isFatalEngineError(e)) forceDestroyEngine()
                    throw mapError(e, currentModelPath ?: "")
                }
            }
        }
        DebugLogger.log("MEDIAPIPE", "generateStream: response length=${response.length}")
        if (response.isNotEmpty()) emit(response)
    }

    override suspend fun generate(prompt: String, packType: PackType): String =
        withContext(Dispatchers.IO) {
            initMutex.withLock {
                val engine = llmInference
                    ?: throw IllegalStateException("MediaPipe engine not initialised")
                try {
                    engine.generateResponse(prompt)
                } catch (e: Exception) {
                    DebugLogger.log("MEDIAPIPE", "generateResponse FAILED: ${e.message}")
                    // If the native layer is broken, destroy so next call reinitialises
                    if (isFatalEngineError(e)) {
                        forceDestroyEngine()
                    }
                    throw e
                }
            }
        }

    // ─────────────────────────────────── release ─────────────────────────────

    override fun release() {
        // Run synchronously — callers don't need to await full cleanup.
        // Use runBlocking so init/generate can't race release and double-register handlers.
        DebugLogger.log("MEDIAPIPE", "release() requested")
        runBlocking {
            initMutex.withLock {
                forceDestroyEngine()
            }
        }
    }

    // ─────────────────────────────────── helpers ─────────────────────────────

    /**
     * Nulls the reference BEFORE closing so crash-in-close can't leave stale state.
     * This is what prevents "Another handler is already registered".
     */
    private fun forceDestroyEngine() {
        val engine = llmInference ?: return
        llmInference = null          // clear FIRST
        isReady = false
        currentModelPath = null
        DebugLogger.log("MEDIAPIPE", "forceDestroyEngine: closing native handle")
        try {
            engine.close()
            DebugLogger.log("MEDIAPIPE", "forceDestroyEngine: closed OK")
        } catch (e: Exception) {
            // Log but do not rethrow — ref is already null so handler is unregistered
            DebugLogger.log("MEDIAPIPE", "forceDestroyEngine: close() threw (ignored): ${e.message}")
        }
        // Suggest GC so native finaliser runs before next createFromOptions
        System.gc()
    }

    private fun validateModel(path: String) {
        val lower = path.lowercase()
        val supported = lower.endsWith(".task") || lower.endsWith(".bin") ||
            lower.endsWith(".litertlm") || lower.endsWith(".litert")
        if (!supported) throw IllegalArgumentException(
            "Unsupported MediaPipe model format: ${path.substringAfterLast('.')}\n\n" +
                "Supported: .task, .bin, .litertlm"
        )

        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException(
            "Model file not found: $path\n\nPlease re-download the model."
        )
        if (!file.canRead()) throw SecurityException(
            "Cannot read model file — storage permission may be needed."
        )
        if (file.length() < 1_000_000L) throw IllegalArgumentException(
            "Model file appears incomplete (${file.length() / 1024}KB).\n\nPlease re-download the model."
        )

        // Log RAM for diagnostics only — MediaPipe uses GPU/mmap and manages its own memory.
        // Do NOT block on RAM here: the engine uses quantised weights on the GPU and the
        // file size does not map 1-to-1 to RAM consumption.
        val fileSizeMb = file.length() / 1_048_576
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val availRamMb = memInfo.availMem / 1_048_576
        DebugLogger.log("MEDIAPIPE", "Model: ${fileSizeMb}MB  Available RAM: ${availRamMb}MB  (MediaPipe manages its own memory)")
    }

    private fun isFatalEngineError(e: Throwable): Boolean {
        val msg = e.message.orEmpty()
        return "Another handler" in msg || "handler is already" in msg ||
            e.javaClass.simpleName == "MediaPipeException"
    }

    private fun mapError(e: Throwable, path: String): Throwable {
        val msg = e.message.orEmpty().lowercase()
        return when {
            "opencl" in msg || "clset" in msg || "opengl" in msg ->
                RuntimeException(
                    "This model requires GPU acceleration not supported by your device.\n\n" +
                        "Choose a smaller model or a GGUF variant.",
                    e
                )
            "another handler" in msg || "handler is already" in msg ->
                RuntimeException(
                    "Engine conflict — previous session didn't close cleanly.\n\n" +
                        "Force-stop the app and try again.",
                    e
                )
            "failed to load" in msg || "can't open" in msg || "no such file" in msg ->
                RuntimeException(
                    "Model file could not be loaded.\n\n" +
                        "The file may be corrupted. Delete it and re-download.",
                    e
                )
            "out of memory" in msg || "oom" in msg ->
                RuntimeException(
                    "Not enough memory to load this model.\n\n" +
                        "Close background apps and try again, or choose a smaller model.",
                    e
                )
            else ->
                RuntimeException(
                    "Failed to load model (${e.javaClass.simpleName}): ${e.message}\n\n" +
                        "If this persists, delete the model file and re-download.",
                    e
                )
        }
    }
}
