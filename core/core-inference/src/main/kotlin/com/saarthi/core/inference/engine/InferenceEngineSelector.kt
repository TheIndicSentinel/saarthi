package com.saarthi.core.inference.engine

import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InferenceEngineSelector @Inject constructor(
    private val liteRtEngine: LiteRTInferenceEngine,
) : InferenceEngine {

    override val isReady: Boolean get() = liteRtEngine.isReady
    override val isReadyFlow: Flow<Boolean> get() = liteRtEngine.isReadyFlow

    override val activeModelName: String? get() = liteRtEngine.activeModelName
    override val activeModelNameFlow: Flow<String?> get() = liteRtEngine.activeModelNameFlow

    override suspend fun initialize(config: InferenceConfig) {
        val path = config.modelPath
        if (isLiteRTModel(path)) {
            // /proc/self/fd/ paths come from URI-picked files. MediaPipe's native stat()
            // cannot resolve them — reject early with a user-readable message.
            if (isFdPath(path)) {
                throw IllegalArgumentException(
                    "LiteRT models must be downloaded to the app's models folder.\n\n" +
                    "Please use the catalog download button instead of the file browser."
                )
            }
            liteRtEngine.initialize(config)
        } else {
            val ext = path.substringAfterLast('.', "unknown")
            throw UnsupportedOperationException(
                "Unsupported model format: .$ext\n\n" +
                "Please use official .litertlm models from the catalog."
            )
        }
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> =
        liteRtEngine.generateStream(prompt, packType)

    override suspend fun generate(prompt: String, packType: PackType): String =
        liteRtEngine.generate(prompt, packType)

    override suspend fun loadLoraAdapter(adapterPath: String, scale: Float) =
        liteRtEngine.loadLoraAdapter(adapterPath, scale)

    override fun clearLoraAdapter() =
        liteRtEngine.clearLoraAdapter()

    override fun release() {
        liteRtEngine.release()
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
