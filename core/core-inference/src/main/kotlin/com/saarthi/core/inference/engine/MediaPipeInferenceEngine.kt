package com.saarthi.core.inference.engine

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaPipeInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : InferenceEngine {

    private var llmInference: LlmInference? = null
    override var isReady: Boolean = false
        private set
        
    private var currentModelPath: String? = null

    override suspend fun initialize(config: InferenceConfig) = withContext(Dispatchers.IO) {
        if (isReady) {
            if (config.modelPath == currentModelPath) return@withContext
            // Same engine instance but different model: release and re-init.
            release()
        }

        val path = config.modelPath
        val lower = path.lowercase()
        val supported =
            lower.endsWith(".task") || lower.endsWith(".bin") || lower.endsWith(".litertlm") || lower.endsWith(".litert")
        if (!supported) {
            throw IllegalArgumentException(
                "Unsupported MediaPipe model format: ${path.substringAfterLast('.')}\n\n" +
                    "Supported: .task, .bin, .litertlm, .litert"
            )
        }

        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException(
            "Model file not found: $path\n\nPlease re-download the model."
        )
        if (!file.canRead()) throw SecurityException(
            "Cannot read model file — storage permission may be needed or file is in an inaccessible directory."
        )

        // RAM guard: model needs at least ~1.5x its size in RAM to load
        val fileSizeMb = file.length() / 1_048_576
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val availRamMb = memInfo.availMem / 1_048_576
        DebugLogger.log("MEDIAPIPE", "Model size: ${fileSizeMb}MB  Available RAM: ${availRamMb}MB")
        if (availRamMb < fileSizeMb) {
            throw RuntimeException(
                "Not enough RAM to load this model.\n\n" +
                "Model needs ~${fileSizeMb}MB but only ${availRamMb}MB is free.\n\n" +
                "Close other apps and try again, or choose a smaller model."
            )
        }

        Timber.d("Initializing MediaPipe engine: $path")
        
        DebugLogger.log("MEDIAPIPE", "Building LlmInference options...")
        val builder = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(config.maxTokens)
            // MediaPipe tasks-genai 0.10.20 exposes max-topK but not temperature/topK knobs.
            .setMaxTopK(config.topK)
        val options = builder.build()
        
        llmInference = try {
            DebugLogger.log("MEDIAPIPE", "Calling LlmInference.createFromOptions (native)...")
            // Force flush before the native call that may kill the process
            System.out.flush()
            val engine = LlmInference.createFromOptions(context, options)
            DebugLogger.log("MEDIAPIPE", "Native creation successful")
            engine
        } catch (e: Throwable) {
            isReady = false
            if (isGpuError(e)) {
                throw RuntimeException(
                    "This model requires GPU acceleration that your device doesn't support.\n\n" +
                    "You need the CPU version of the model.\n" +
                    "Download: gemma2-2b-it-cpu-int4.bin (~1.3 GB)\n" +
                    "Search 'gemma 2b it mediapipe' on Kaggle, then download the cpu-int4 variant.",
                    e,
                )
            } else {
                val errorMsg = e.message ?: e.javaClass.simpleName
                DebugLogger.log("MEDIAPIPE", "Init failed: $errorMsg")
                throw RuntimeException("MediaPipe failed to load model: $errorMsg", e)
            }
        }
        isReady = true
        currentModelPath = path
        Timber.d("MediaPipe engine ready")
    }

    private fun isGpuError(e: Throwable): Boolean {
        val msg = e.message.orEmpty().lowercase()
        return "opencl" in msg || "clset" in msg || "opengl" in msg
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> = flow {
        try {
            val result = withContext(Dispatchers.IO) { requireEngine().generateResponse(prompt) }
            emit(result)
        } catch (e: Exception) {
            Timber.e(e, "MediaPipe stream generation failed")
            throw e
        }
    }

    override suspend fun generate(prompt: String, packType: PackType): String =
        withContext(Dispatchers.IO) {
            try {
                requireEngine().generateResponse(prompt)
            } catch (e: Exception) {
                Timber.e(e, "MediaPipe generation failed")
                throw e
            }
        }

    override fun release() {
        llmInference?.close()
        llmInference = null
        isReady = false
        currentModelPath = null
    }

    private fun requireEngine(): LlmInference =
        checkNotNull(llmInference) { "InferenceEngine not initialized. Call initialize() first." }
}
