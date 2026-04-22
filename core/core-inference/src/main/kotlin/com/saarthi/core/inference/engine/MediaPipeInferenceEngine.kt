package com.saarthi.core.inference.engine

import android.app.ActivityManager
import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
 * Key crash fix: MediaPipe's native layer allows only ONE active LlmInference
 * handler per process. Calling createFromOptions a second time — even after
 * close() — triggers "Another handler is already registered" if the old
 * JVM object hasn't been fully GC-collected.
 *
 * Solution:
 *  1. [initMutex] serialises all init/release operations — no concurrent creation.
 *  2. [forceDestroyEngine] nulls the reference FIRST, then closes — so even if
 *     close() throws, the stale reference is gone.
 *  3. generateResponseAsync is used for streaming to avoid a separate async
 *     handler registration that conflicts with synchronous calls.
 */
@Singleton
class MediaPipeInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : InferenceEngine {

    private var llmInference: LlmInference? = null
    private var inFlight: ListenableFuture<String>? = null

    override var isReady: Boolean = false
        private set

    private var currentModelPath: String? = null

    /** Serializes all init/release so native handler is never double-registered. */
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
     * Streams the full response token-by-token using [LlmInference.generateResponseAsync].
     *
     * MediaPipe's async callback is the ONLY thread-safe generation path —
     * the synchronous [generateResponse] blocks a coroutine thread and can
     * conflict with the listener registered by a previous session.
     */
    override fun generateStream(prompt: String, packType: PackType): Flow<String> = flow {
        initMutex.withLock {
            val engine = llmInference
                ?: throw IllegalStateException("MediaPipe engine not initialised")

            // MediaPipe only allows one active progress handler per process.
            // Never start a second async generation until the previous future is complete.
            if (inFlight?.isDone == false) {
                DebugLogger.log("MEDIAPIPE", "generateStream blocked: previous generation still running")
            }

            // Channel carries partial results; capacity=unlimited so the callback never blocks.
            val resultChannel = Channel<Result<String?>>(Channel.UNLIMITED)

            val future = withContext(Dispatchers.IO) {
                engine.generateResponseAsync(prompt) { partialResult, done ->
                    if (done) {
                        resultChannel.trySend(Result.success(null)) // sentinel
                    } else if (partialResult != null) {
                        resultChannel.trySend(Result.success(partialResult))
                    }
                }
            }
            inFlight = future

            // Ensure the flow terminates on errors/cancellation.
            future.addListener(
                {
                    runCatching { future.get() }
                        .onFailure { resultChannel.trySend(Result.failure(it)) }
                        .also { resultChannel.trySend(Result.success(null)) }
                },
                MoreExecutors.directExecutor(),
            )

            // Drain the channel, re-throwing any engine errors on the flow side
            try {
                for (result in resultChannel) {
                    val token = result.getOrThrow() ?: break   // null sentinel = done
                    if (token.isNotEmpty()) emit(token)
                }
            } finally {
                resultChannel.close()
                if (inFlight === future) inFlight = null
            }
        }
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
        // Cancel any in-flight generation first (unregisters progress handler).
        inFlight?.let { f ->
            DebugLogger.log("MEDIAPIPE", "forceDestroyEngine: cancel in-flight generation")
            runCatching { f.cancel(true) }
        }
        inFlight = null

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
                "Supported: .task, .bin, .litertlm, .litert"
        )

        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException(
            "Model file not found: $path\n\nPlease re-download the model."
        )
        if (!file.canRead()) throw SecurityException(
            "Cannot read model file — storage permission may be needed."
        )

        // RAM guard
        val fileSizeMb = file.length() / 1_048_576
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val availRamMb = memInfo.availMem / 1_048_576
        DebugLogger.log("MEDIAPIPE", "Model: ${fileSizeMb}MB  Available RAM: ${availRamMb}MB")
        if (availRamMb < fileSizeMb) throw RuntimeException(
            "Not enough RAM to load this model.\n\n" +
                "Model needs ~${fileSizeMb}MB but only ${availRamMb}MB is free.\n\n" +
                "Close other apps and try again, or choose a smaller model."
        )
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
                        "Please download the CPU-only variant of the model.",
                    e
                )
            "another handler" in msg || "handler is already" in msg ->
                RuntimeException(
                    "Internal engine conflict detected.\n\n" +
                        "Please force-stop the app, then relaunch and try again.",
                    e
                )
            else ->
                RuntimeException("MediaPipe failed to load model: ${e.message}", e)
        }
    }
}
