package com.saarthi.core.inference.engine

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaPipeInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : InferenceEngine {

    private var llmInference: LlmInference? = null
    override var isReady: Boolean = false
        private set

    override suspend fun initialize(config: InferenceConfig) = withContext(Dispatchers.IO) {
        Timber.d("Initializing MediaPipe engine: ${config.modelPath}")
        val builder = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(config.modelPath)
            .setMaxTokens(config.maxTokens)
            .setTopK(config.topK)
            .setTemperature(config.temperature)

        llmInference = try {
            LlmInference.createFromOptions(context, builder.build())
        } catch (gpuEx: Exception) {
            if (isGpuError(gpuEx)) {
                Timber.w("GPU init failed (${gpuEx.message}), retrying with CPU backend")
                trySetCpuBackend(builder)
                try {
                    LlmInference.createFromOptions(context, builder.build())
                } catch (cpuEx: Exception) {
                    throw RuntimeException(
                        "GPU not supported on this device and CPU fallback also failed.\n" +
                        "Please download the CPU model: gemma2-2b-it-cpu-int4.bin\n" +
                        "CPU error: ${cpuEx.message}",
                        cpuEx,
                    )
                }
            } else {
                throw gpuEx
            }
        }
        isReady = true
        Timber.d("MediaPipe engine ready")
    }

    private fun isGpuError(e: Exception): Boolean {
        val msg = e.message.orEmpty().lowercase()
        return "opencl" in msg || "gpu" in msg || "clset" in msg || "opengl" in msg
    }

    // Uses reflection so this compiles against any MediaPipe version; no-op if API absent.
    private fun trySetCpuBackend(builder: LlmInference.LlmInferenceOptions.Builder) {
        try {
            val backendClass = Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions\$Backend"
            )
            val cpuValue = backendClass.enumConstants
                ?.firstOrNull { (it as Enum<*>).name == "CPU" } ?: return
            builder.javaClass
                .getMethod("setPreferredBackend", backendClass)
                .invoke(builder, cpuValue)
            Timber.d("CPU backend set via reflection")
        } catch (e: Exception) {
            Timber.w("setPreferredBackend not available: ${e.message}")
        }
    }

    // generateResponse() is the synchronous API stable across MediaPipe 0.10.x versions.
    // Runs on IO dispatcher to avoid blocking the main thread.
    override fun generateStream(prompt: String, packType: PackType): Flow<String> = flow {
        val result = withContext(Dispatchers.IO) { requireEngine().generateResponse(prompt) }
        emit(result)
    }

    override suspend fun generate(prompt: String, packType: PackType): String =
        withContext(Dispatchers.IO) { requireEngine().generateResponse(prompt) }

    override fun release() {
        llmInference?.close()
        llmInference = null
        isReady = false
    }

    private fun requireEngine(): LlmInference =
        checkNotNull(llmInference) { "InferenceEngine not initialized. Call initialize() first." }
}
