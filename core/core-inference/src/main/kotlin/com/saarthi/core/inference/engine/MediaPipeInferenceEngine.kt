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
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe LLM inference engine wrapper.
 *
 * Critical design constraint: MediaPipe's native layer registers a process-level
 * handler slot in [LlmInference.createFromOptions].  Calling [LlmInference.close]
 * does NOT reliably free this slot — any subsequent [createFromOptions] throws
 * "Another handler is already registered".
 *
 * Strategy: load the model ONCE and keep the [LlmInference] instance alive for the
 * process lifetime.  [release] only marks [isReady] false; it does NOT close the
 * native instance.  The instance is closed (and immediately replaced) only when
 * [initialize] is called with a DIFFERENT model path.  This eliminates the
 * close→createFromOptions pattern that causes the crash.
 *
 * Generation uses [LlmInference.generateResponse] (synchronous, no async handler
 * registration) protected by [initMutex] so only one call runs at a time.
 */
@Singleton
class MediaPipeInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : InferenceEngine {

    private var llmInference: LlmInference? = null

    override var isReady: Boolean = false
        private set

    private var currentModelPath: String? = null

    /** Serializes all init/generate so native handler is never double-registered. */
    private val initMutex = Mutex()

    // ─────────────────────────────────── initialize ──────────────────────────

    override suspend fun initialize(config: InferenceConfig) = withContext(Dispatchers.IO) {
        initMutex.withLock {
            // If the instance is already alive for this path, just re-mark ready.
            // This handles the case where release() was called (sets isReady=false)
            // but the native instance is still loaded — avoids close→createFromOptions.
            if (llmInference != null && config.modelPath == currentModelPath) {
                DebugLogger.log("MEDIAPIPE", "Already loaded (reattach): ${config.modelPath}")
                isReady = true
                return@withLock
            }

            // Different model requested — close the old instance first, then load new.
            // This is the ONLY place we call close(); doing it here (same lock scope as
            // the subsequent createFromOptions) minimises the window for a race.
            if (llmInference != null) {
                DebugLogger.log("MEDIAPIPE", "Model changed — closing old instance")
                closeEngine()
                // Give the native layer a moment to fully release before creating new one.
                Thread.sleep(300)
                System.gc()
                System.runFinalization()
                Thread.sleep(200)
            }

            validateModel(config.modelPath)

            DebugLogger.log("MEDIAPIPE", "Building LlmInference for ${config.modelPath}")
            val options = LlmInferenceOptions.builder()
                .setModelPath(config.modelPath)
                .setMaxTokens(config.maxTokens)
                .setMaxTopK(config.topK)
                .build()

            llmInference = try {
                DebugLogger.log("MEDIAPIPE", "Calling createFromOptions…")
                val engine = LlmInference.createFromOptions(context, options)
                DebugLogger.log("MEDIAPIPE", "createFromOptions succeeded")
                engine
            } catch (e: Throwable) {
                DebugLogger.log("MEDIAPIPE", "createFromOptions FAILED: ${e.javaClass.simpleName}: ${e.message}")
                throw mapError(e, config.modelPath)
            }

            isReady = true
            currentModelPath = config.modelPath
            Timber.d("MediaPipe engine ready: ${config.modelPath}")
        }
    }

    // ─────────────────────────────────── generation ──────────────────────────

    override fun generateStream(prompt: String, packType: PackType): Flow<String> = flow {
        val response = withContext(Dispatchers.IO) {
            initMutex.withLock {
                val engine = llmInference
                    ?: throw IllegalStateException("MediaPipe engine not initialised")
                DebugLogger.log("MEDIAPIPE", "generateStream: synchronous generation start")
                try {
                    engine.generateResponse(prompt)
                } catch (e: Throwable) {
                    DebugLogger.log("MEDIAPIPE", "generateStream FAILED: ${e.javaClass.simpleName}: ${e.message}")
                    // On fatal native error, close the broken instance so next initialize()
                    // loads a fresh one.
                    if (isFatalEngineError(e)) {
                        closeEngine()
                    }
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
                    DebugLogger.log("MEDIAPIPE", "generate FAILED: ${e.message}")
                    if (isFatalEngineError(e)) closeEngine()
                    throw e
                }
            }
        }

    // ─────────────────────────────────── release ─────────────────────────────

    /**
     * Marks the engine as not ready without closing the native instance.
     *
     * Closing the native [LlmInference] does NOT reliably free MediaPipe's process-level
     * handler slot, so a subsequent [createFromOptions] (triggered by a re-init) would
     * crash with "Another handler is already registered".  Instead, we keep the instance
     * alive and re-attach to it in [initialize] if the same model is requested again.
     */
    override fun release() {
        DebugLogger.log("MEDIAPIPE", "release() — marking not ready (native instance kept alive)")
        isReady = false
        // Do NOT close llmInference here.  See class-level KDoc.
    }

    // ─────────────────────────────────── helpers ─────────────────────────────

    /** Closes and nulls the native instance. Only called when switching to a different model. */
    private fun closeEngine() {
        val engine = llmInference ?: return
        llmInference = null
        isReady = false
        currentModelPath = null
        DebugLogger.log("MEDIAPIPE", "closeEngine: closing native handle")
        try {
            engine.close()
            DebugLogger.log("MEDIAPIPE", "closeEngine: closed OK")
        } catch (e: Exception) {
            DebugLogger.log("MEDIAPIPE", "closeEngine: close() threw (ignored): ${e.message}")
        }
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

        val fileSizeMb = file.length() / 1_048_576
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val availRamMb = memInfo.availMem / 1_048_576
        DebugLogger.log("MEDIAPIPE", "Model: ${fileSizeMb}MB  Available RAM: ${availRamMb}MB")
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
                    "Engine conflict — please force-stop the app once and try again.",
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
                        "Close background apps or choose a smaller model.",
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
