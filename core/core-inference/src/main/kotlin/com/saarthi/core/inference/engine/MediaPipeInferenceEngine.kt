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
import kotlin.coroutines.resumeWithException

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
            .setTemperature(config.temperature)
            .setTopK(config.topK)
            .setMaxTokens(config.maxTokens)
            .apply {
                config.loraAdapterPath?.let { setLoraPath(it) }
            }
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
        isReady = true
        Timber.d("MediaPipe engine ready")
    }

    override fun generateStream(prompt: String, packType: PackType): Flow<String> = callbackFlow {
        val engine = requireEngine()
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .apply {
                packType.loraFileName?.let { /* apply LoRA via session config when supported */ }
            }
            .build()

        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
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
            val builder = StringBuilder()
            val session = LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder().build()
            )
            session.addQueryChunk(prompt)
            session.generateResponseAsync { partial, done ->
                builder.append(partial)
                if (done) {
                    session.close()
                    continuation.resume(builder.toString())
                }
            }
            continuation.invokeOnCancellation { session.close() }
        }

    override fun release() {
        llmInference?.close()
        llmInference = null
        isReady = false
    }

    private fun requireEngine(): LlmInference =
        checkNotNull(llmInference) { "InferenceEngine not initialized. Call initialize() first." }
}
