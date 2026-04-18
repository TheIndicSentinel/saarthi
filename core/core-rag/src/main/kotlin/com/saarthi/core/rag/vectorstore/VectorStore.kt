package com.saarthi.core.rag.vectorstore

data class SearchResult(
    val id: Long,
    val text: String,
    val score: Float,
)

interface VectorStore {
    suspend fun insert(text: String, embedding: FloatArray): Long
    suspend fun search(queryEmbedding: FloatArray, topK: Int = 3): List<SearchResult>
    suspend fun deleteAll()
}
