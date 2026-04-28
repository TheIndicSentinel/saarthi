package com.saarthi.core.inference.engine

import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes inference requests to the correct backend based on model file extension:
 *
 *   .task / .litertlm / .bin  →  [LiteRTInferenceEngine]  (MediaPipe GPU-accelerated, primary)
 *   .gguf                     →  [LlamaCppInferenceEngine] (CPU fallback, GGUF custom models)
 *
 * LiteRT is the primary backend for all official Google Gemma mobile models.
 * llama.cpp is kept as a fallback for community GGUF models on devices where LiteRT
 * delegates are unavailable (x86 emulator, old drivers).
 */
@Singleton
class InferenceEngineSelector @Inject constructor(
    private val liteRtEngine: LiteRTInferenceEngine,
    private val llamaCppEngine: LlamaCppInferenceEngine,
) : InferenceEngine {

    private enum class ActiveBackend { LITERT, LLAMA_CPP }

    @Volatile private var activeBackend: ActiveBackend = ActiveBackend.LITERT

    private val activeEngine: InferenceEngine
        get() = when (activeBackend) {
            ActiveBackend.LITERT   -> liteRtEngine
            ActiveBackend.LLAMA_CPP -> llamaCppEngine
        }

    override val isReady: Boolean get() = activeEngine.isReady
    override val isReadyFlow: Flow<Boolean> get() = activeEngine.isReadyFlow

    override val activeModelName: String? get() = activeEngine.activeModelName
    override val activeModelNameFlow: Flow<String?> get() = activeEngine.activeModelNameFlow

    override suspend fun initialize(config: InferenceConfig) {
        val path = config.modelPath
        when {
            isLiteRTModel(path) -> {
                // /proc/self/fd/ paths come from URI-picked files. MediaPipe's native stat()
                // cannot resolve them — reject early with a user-readable message.
                if (isFdPath(path)) {
                    throw IllegalArgumentException(
                        "LiteRT models must be downloaded to the app's models folder.\n\n" +
                        "Please use the catalog download button instead of the file browser."
                    )
                }
                if (llamaCppEngine.isReady) {
                    Timber.d("Releasing llama.cpp before switching to LiteRT")
                    llamaCppEngine.release()
                }
                activeBackend = ActiveBackend.LITERT
                DebugLogger.log("ENGINE", "→ LiteRT  model=${path.substringAfterLast('/')}")
                liteRtEngine.initialize(config)
            }
            isGgufModel(path) -> {
                if (liteRtEngine.isReady) {
                    Timber.d("Releasing LiteRT before switching to llama.cpp")
                    liteRtEngine.release()
                }
                activeBackend = ActiveBackend.LLAMA_CPP
                DebugLogger.log("ENGINE", "→ llama.cpp  model=${path.substringAfterLast('/')}")
                llamaCppEngine.initialize(config)
            }
            else -> {
                val ext = path.substringAfterLast('.', "unknown")
                throw UnsupportedOperationException(
                    "Unsupported model format: .$ext\n\n" +
                    "Supported formats: .task (LiteRT — recommended) or .gguf (llama.cpp)"
                )
            }
        }
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> =
        activeEngine.generateStream(prompt, packType)

    override suspend fun generate(prompt: String, packType: PackType): String =
        activeEngine.generate(prompt, packType)

    override suspend fun loadLoraAdapter(adapterPath: String, scale: Float) =
        activeEngine.loadLoraAdapter(adapterPath, scale)

    override fun clearLoraAdapter() =
        activeEngine.clearLoraAdapter()

    override fun release() {
        liteRtEngine.release()
        llamaCppEngine.release()
    }

    private fun isLiteRTModel(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".task") || lower.endsWith(".litertlm") || lower.endsWith(".bin")
    }

    private fun isGgufModel(path: String): Boolean =
        path.endsWith(".gguf", ignoreCase = true)

    private fun isFdPath(path: String): Boolean =
        path.startsWith("/proc/self/fd/")
}
