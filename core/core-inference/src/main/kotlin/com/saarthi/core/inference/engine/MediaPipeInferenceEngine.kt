package com.saarthi.core.inference.engine

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class MediaPipeInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : InferenceEngine {

    private var llmInference: LlmInference? = null
    override var isReady: Boolean = false
        private set

    override suspend fun initialize(config: InferenceConfig) {
        Timber.d("Initializing MediaPipe engine: ${config.modelPath}")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(config.modelPath)
            .setMaxTokens(config.maxTokens)
            .setTopK(config.topK)
            .setTemperature(config.temperature)
            .build()
        llmInference = LlmInference.createFromOptions(context, options)
        isReady = true
        Timber.d("MediaPipe engine ready")
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> = callbackFlow {
        val engine = requireEngine()
        engine.generateResponseAsync(prompt) { partialResult, done ->
            trySend(partialResult)
            if (done) close()
        }
        awaitClose()
    }

    override suspend fun generate(prompt: String, packType: PackType): String =
        suspendCancellableCoroutine { continuation ->
            val engine = requireEngine()
            val response = StringBuilder()
            engine.generateResponseAsync(prompt) { partial, done ->
                response.append(partial)
                if (done) continuation.resume(response.toString())
            }
        }

    override fun release() {
        llmInference?.close()
        llmInference = null
        isReady = false
    }

    private fun requireEngine(): LlmInference =
        checkNotNull(llmInference) { "InferenceEngine not initialized. Call initialize() first." }
}
