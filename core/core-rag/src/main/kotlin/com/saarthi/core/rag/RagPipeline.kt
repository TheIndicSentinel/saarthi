package com.saarthi.core.rag

import com.saarthi.core.rag.embedding.EmbeddingModel
import com.saarthi.core.rag.vectorstore.VectorStore
import javax.inject.Inject

class RagPipeline @Inject constructor(
    private val embeddingModel: EmbeddingModel,
    private val vectorStore: VectorStore,
) {
    // Retrieve top-K relevant chunks and build an augmented prompt
    suspend fun buildAugmentedPrompt(userQuery: String, basePrompt: String): String {
        val queryEmbedding = embeddingModel.embed(userQuery)
        val results = vectorStore.search(queryEmbedding, topK = 3)

        if (results.isEmpty()) return basePrompt

        val context = results.joinToString("\n---\n") { it.text }
        return """
            |Context (use ONLY this to answer):
            |$context
            |
            |$basePrompt
        """.trimMargin()
    }

    suspend fun indexDocument(text: String) {
        val chunks = chunkText(text, chunkSize = 512, overlap = 64)
        chunks.forEach { chunk ->
            val embedding = embeddingModel.embed(chunk)
            vectorStore.insert(chunk, embedding)
        }
    }

    private fun chunkText(text: String, chunkSize: Int, overlap: Int): List<String> {
        val words = text.split(" ")
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < words.size) {
            val end = minOf(start + chunkSize, words.size)
            chunks += words.subList(start, end).joinToString(" ")
            start += (chunkSize - overlap)
        }
        return chunks
    }
}
