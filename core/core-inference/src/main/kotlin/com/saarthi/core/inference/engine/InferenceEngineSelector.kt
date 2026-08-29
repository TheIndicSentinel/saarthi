package com.saarthi.core.inference.engine

import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InferenceEngineSelector @Inject constructor(
    private val liteRtEngine: LiteRTInferenceEngine,
) : InferenceEngine {

    override val isReady: Boolean get() = liteRtEngine.isReady
    override val isReadyFlow: Flow<Boolean> get() = liteRtEngine.isReadyFlow
    override val isInitializing: Boolean get() = liteRtEngine.isInitializing
    override val isInitializingFlow: Flow<Boolean> get() = liteRtEngine.isInitializingFlow
    override val isReloadingAfterRelease: Boolean get() = liteRtEngine.isReloadingAfterRelease
    override val isReloadingAfterReleaseFlow: Flow<Boolean> get() = liteRtEngine.isReloadingAfterReleaseFlow

    override val activeModelName: String? get() = liteRtEngine.activeModelName
    override val activeModelNameFlow: Flow<String?> get() = liteRtEngine.activeModelNameFlow
    override val activeModelDefaultTemperature: Float get() = liteRtEngine.activeModelDefaultTemperature
    // Must forward, otherwise the prompt builder's token-ceiling clamp reads
    // the interface default (0) and silently disables itself — which let
    // over-budget prompts reach the native engine and fail with "Input token
    // ids are too long".
    override val maxContextTokens: Int get() = liteRtEngine.maxContextTokens

    override val isNativeGenerating: Boolean get() = liteRtEngine.isNativeGenerating
    override val isFreshConversation: Boolean get() = liteRtEngine.isFreshConversation

    override fun cancelGeneration() = liteRtEngine.cancelGeneration()

    override suspend fun resetSession() = liteRtEngine.resetSession()

    override suspend fun initialize(config: InferenceConfig) {
        val path = config.modelPath
        if (isLiteRTModel(path)) {
            // /proc/self/fd/ paths come from URI-picked files. MediaPipe's native stat()
            // cannot resolve them — reject early with a user-readable message.
            if (isFdPath(path)) {
                throw IllegalArgumentException(
                    "This model has to be downloaded in the app.\n\n" +
                    "Use Download on the model list instead of picking a file."
                )
            }
            liteRtEngine.initialize(config)
        } else {
            val ext = path.substringAfterLast('.', "unknown")
            throw UnsupportedOperationException(
                "This file type isn't supported (.$ext).\n\n" +
                "Download a model from the list in the app."
            )
        }
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> =
        liteRtEngine.generateStream(prompt, packType)

    override fun release() {
        liteRtEngine.release()
    }

    private fun isLiteRTModel(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".task") || lower.endsWith(".litertlm") || lower.endsWith(".bin")
    }

    private fun isFdPath(path: String): Boolean =
        path.startsWith("/proc/self/fd/")
}
