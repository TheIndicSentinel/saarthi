package com.saarthi.core.rag

import com.saarthi.core.rag.embedding.EmbeddingModel
import com.saarthi.core.rag.vectorstore.VectorStore
import javax.inject.Inject

/**
 * @deprecated Not used in production. The live RAG path is
 * [com.saarthi.feature.assistant.data.RagDocumentRepository], which uses
 * BM25 retrieval, sentence/word-boundary aware chunking, structural sampling,
 * neighbor expansion, and Room-persisted chunks. This class predates that
 * implementation and is retained only because [RagPipelineTest] documents the
 * vector-store + embedding-model contract. Do not add new call sites here.
 */
@Deprecated(
    message = "Production RAG is in RagDocumentRepository (BM25 + Room). " +
              "This class is dead code kept for its unit-test contract.",
    level = DeprecationLevel.WARNING,
)
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
        if (text.isBlank()) return  // skip empty documents — used to insert a single blank chunk
        val chunks = chunkText(text, chunkSize = 512, overlap = 64)
            .filter { it.isNotBlank() }  // skip any chunk that ends up empty (single-word + overlap edge case)
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
