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
 *   all others → MediaPipeInferenceEngine (.task, .litertlm)
 *
 * This is the singleton [InferenceEngine] bound in the DI graph.
 *
 * Release policy:
 *   - Engine type change (GGUF ↔ MediaPipe): release the engine being abandoned so its
 *     resources are freed, then initialize the new one.
 *   - Same engine type, different model path: do NOT call release() — let the individual
 *     engine handle model switching internally.  For MediaPipe in particular, calling
 *     release() followed by initialize() triggers the "Another handler is already
 *     registered" crash because close() does not reliably free the native handler slot.
 *   - Same engine type, same model path: no-op (engine handles "already loaded" internally).
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
        val engineChanged = engine !== activeEngine

        if (engineChanged) {
            // Abandoning the current engine — release it so native resources are freed.
            // The NEW engine is NOT pre-released: its internal "already loaded" check or
            // model-switch logic handles any existing state safely.
            Timber.d("InferenceEngineSelector: engine type changed — releasing ${activeEngine::class.simpleName}")
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
