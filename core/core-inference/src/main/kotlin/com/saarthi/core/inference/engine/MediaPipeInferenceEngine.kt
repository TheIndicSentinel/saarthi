package com.saarthi.core.inference.engine

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
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
    private var inferenceConfig: InferenceConfig? = null
    override var isReady: Boolean = false
        private set

    override suspend fun initialize(config: InferenceConfig) {
        Timber.d("Initializing MediaPipe engine: ${config.modelPath}")
        // LlmInferenceOptions only accepts model path and token budget.
        // Temperature, topK, and LoRA are per-session options.
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(config.modelPath)
            .setMaxTokens(config.maxTokens)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
        inferenceConfig = config
        isReady = true
        Timber.d("MediaPipe engine ready")
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> = callbackFlow {
        val engine = requireEngine()
        val session = LlmInferenceSession.createFromOptions(engine, buildSessionOptions(packType))
        session.addQueryChunk(prompt)

        session.generateResponseAsync { partialResult, done ->
            trySend(partialResult)
            if (done) {
                session.close()
                close()
            }
        }
        awaitClose { session.close() }
    }

    override suspend fun generate(prompt: String, packType: PackType): String =
        suspendCancellableCoroutine { continuation ->
            val engine = requireEngine()
            val response = StringBuilder()
            val session = LlmInferenceSession.createFromOptions(engine, buildSessionOptions(packType))
            session.addQueryChunk(prompt)
            session.generateResponseAsync { partial, done ->
                response.append(partial)
                if (done) {
                    session.close()
                    continuation.resume(response.toString())
                }
            }
            continuation.invokeOnCancellation { session.close() }
        }

    override fun release() {
        llmInference?.close()
        llmInference = null
        inferenceConfig = null
        isReady = false
    }

    private fun buildSessionOptions(packType: PackType): LlmInferenceSession.LlmInferenceSessionOptions {
        val cfg = inferenceConfig
        return LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .apply {
                if (cfg != null) {
                    setTopK(cfg.topK)
                    setTemperature(cfg.temperature)
                }
                packType.loraFileName?.let { setLoraPath(it) }
            }
            .build()
    }

    private fun requireEngine(): LlmInference =
        checkNotNull(llmInference) { "InferenceEngine not initialized. Call initialize() first." }
}
