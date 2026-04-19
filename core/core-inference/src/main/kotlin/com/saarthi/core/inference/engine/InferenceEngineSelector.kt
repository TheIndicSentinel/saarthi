package com.saarthi.core.inference.engine

import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes inference to the correct backend:
 *   .gguf → LlamaCppInferenceEngine  (streaming, LoRA adapters, Vulkan GPU)
 *   .bin  → MediaPipeInferenceEngine (fallback for pre-downloaded MediaPipe models)
 *
 * This is the singleton [InferenceEngine] bound in the DI graph.
 */
@Singleton
class InferenceEngineSelector @Inject constructor(
    private val mediaPipeEngine: MediaPipeInferenceEngine,
    private val llamaCppEngine: LlamaCppInferenceEngine,
) : InferenceEngine {

    private var activeEngine: InferenceEngine = mediaPipeEngine

    override val isReady: Boolean get() = activeEngine.isReady

    override suspend fun initialize(config: InferenceConfig) {
        val engine = engineFor(config.modelPath)
        if (engine !== activeEngine) {
            activeEngine.release()
            activeEngine = engine
        }
        Timber.d("InferenceEngineSelector → ${engine::class.simpleName}")
        activeEngine.initialize(config)
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> =
        activeEngine.generateStream(prompt, packType)

    override suspend fun generate(prompt: String, packType: PackType): String =
        activeEngine.generate(prompt, packType)

    override suspend fun loadLoraAdapter(adapterPath: String, scale: Float) =
        activeEngine.loadLoraAdapter(adapterPath, scale)

    override fun clearLoraAdapter() =
        activeEngine.clearLoraAdapter()

    override fun release() = activeEngine.release()

    private fun engineFor(modelPath: String): InferenceEngine =
        if (modelPath.endsWith(".gguf", ignoreCase = true)) llamaCppEngine
        else mediaPipeEngine
}
