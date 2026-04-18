package com.saarthi.core.rag.embedding

import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.PackType
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

// Uses base Gemma to produce token-averaged embeddings.
// Replace with a dedicated MiniLM ONNX model for production quality.
@Singleton
class GemmaEmbeddingModel @Inject constructor(
    private val engine: InferenceEngine,
) : EmbeddingModel {

    override val dimensions: Int = 384

    override suspend fun embed(text: String): FloatArray {
        // Production: run sentence-transformers/all-MiniLM-L6-v2 via ONNX Runtime
        // Placeholder returns a normalised zero vector until ONNX model is bundled
        return FloatArray(dimensions) { 0f }.also { normalise(it) }
    }

    private fun normalise(v: FloatArray) {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        if (norm > 0f) v.indices.forEach { v[it] /= norm }
    }
}
