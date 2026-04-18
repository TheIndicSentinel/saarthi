package com.saarthi.core.rag.embedding

// Interface Segregation — callers only care about embeddings, not model internals
interface EmbeddingModel {
    suspend fun embed(text: String): FloatArray
    val dimensions: Int
}
