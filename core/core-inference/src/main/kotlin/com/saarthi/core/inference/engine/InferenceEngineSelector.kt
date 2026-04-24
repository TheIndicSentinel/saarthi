package com.saarthi.core.inference.engine

import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton inference engine — routes all requests to [LlamaCppInferenceEngine].
 *
 * Architecture decision: llama.cpp GGUF is the only supported backend.
 *
 *  • No process-level handler slot — zero "Another handler is already registered" crashes.
 *  • True streaming token-by-token (via JNI callback).
 *  • Native LoRA adapter support (future domain packs).
 *  • Live model switching without app restart.
 *  • Industry standard: same stack as Pocketpal AI, ChatterUI.
 *
 * MediaPipe LiteRT was removed: its process-level native handler cannot be reliably freed
 * between sessions, causing unfixable "Another handler is already registered" crashes on
 * model switch or Activity recreation.  All Google Gemma models are available as GGUF.
 */
@Singleton
class InferenceEngineSelector @Inject constructor(
    private val llamaCppEngine: LlamaCppInferenceEngine,
) : InferenceEngine {

    override val isReady: Boolean get() = llamaCppEngine.isReady

    override suspend fun initialize(config: InferenceConfig) {
        if (!config.modelPath.endsWith(".gguf", ignoreCase = true)) {
            DebugLogger.log("ENGINE", "Unsupported model format — only GGUF is supported: ${config.modelPath}")
            throw UnsupportedOperationException(
                "Only GGUF models are supported.\n\n" +
                "Please download a GGUF model from the model list.\n" +
                "Legacy MediaPipe models (.task, .litertlm) are no longer supported."
            )
        }
        Timber.d("InferenceEngineSelector → LlamaCppInferenceEngine")
        llamaCppEngine.initialize(config)
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> =
        llamaCppEngine.generateStream(prompt, packType)

    override suspend fun generate(prompt: String, packType: PackType): String =
        llamaCppEngine.generate(prompt, packType)

    override suspend fun loadLoraAdapter(adapterPath: String, scale: Float) =
        llamaCppEngine.loadLoraAdapter(adapterPath, scale)

    override fun clearLoraAdapter() =
        llamaCppEngine.clearLoraAdapter()

    override fun release() = llamaCppEngine.release()
}
