package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkDao
import com.saarthi.core.memory.db.RagChunkEntity
import com.saarthi.core.rag.Bm25Retriever
import com.saarthi.feature.assistant.domain.AttachedFile
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production RAG pipeline for attached documents.
 *
 * Replaces the in-memory `sessionDocuments` map + keyword-overlap chunking
 * that lost everything on process restart. Now:
 *
 *  • Persistence — chunks live in `rag_chunks` keyed by sessionId, so
 *    follow-up turns and reopen-after-kill both see the same context.
 *  • Retrieval — BM25 (Lucene's default) replaces keyword-count; rare
 *    query terms outweigh common ones, length is normalised, TF saturates.
 *  • Idempotency — re-attaching the same file in the same session is a
 *    no-op, checked via a `(sessionId, docUri)` count before we chunk.
 *
 * Image / binary attachments are not indexed here; ChatRepositoryImpl
 * surfaces them in the prompt via a short separate note so the model
 * knows they were attached but unindexable.
 */
@Singleton
class RagDocumentRepository @Inject constructor(
    private val ragChunkDao: RagChunkDao,
) {

    companion object {
        // 600 chars ≈ 150 tokens. Small enough that 4-6 chunks fit
        // comfortably inside the LARGE-tier prompt budget alongside the
        // system + user message, big enough to carry one coherent
        // paragraph of context.
        private const val CHUNK_SIZE = 600
        // 80-char overlap preserves the answer when it straddles a chunk
        // boundary. Cheap insurance — costs ~13% extra storage, fixes
        // ~5% of edge-case retrieval misses.
        private const val CHUNK_OVERLAP = 80
        // Top-K returned to the prompt builder. 5 keeps the prompt
        // bounded (~3000 chars of context) while giving the LLM enough
        // surface area to triangulate facts across multiple chunks.
        private const val DEFAULT_TOP_K = 5
    }

    /**
     * Index [file] for [sessionId] if it isn't already. Idempotent: the
     * `(sessionId, docUri)` count check prevents re-chunking the same
     * file on every turn — the user's session-pin loop calls this every
     * sendMessage().
     *
     * Skips files without extractable text (binaries, oversize-rejected,
     * empty OCR). Those still appear in the chat bubble as attachments;
     * the prompt builder notes their presence separately.
     */
    suspend fun indexIfNeeded(sessionId: String, file: AttachedFile) {
        val uriKey = file.uri.toString()
        if (ragChunkDao.countByDoc(sessionId, uriKey) > 0) return
        val text = file.extractedText?.trim().orEmpty()
        if (text.isEmpty()) return

        val chunks = chunkText(text)
        if (chunks.isEmpty()) return

        val entities = chunks.mapIndexed { idx, chunk ->
            RagChunkEntity(
                sessionId = sessionId,
                docUri = uriKey,
                docName = file.name,
                mimeType = file.mimeType,
                chunkIndex = idx,
                text = chunk,
            )
        }
        ragChunkDao.insertAll(entities)
        Timber.d("RAG: indexed ${entities.size} chunks for ${file.name} (session=$sessionId)")
    }

    /**
     * Search top chunks for [query] across every document indexed under
     * [sessionId]. Returns ranked hits or — when nothing matches by BM25 —
     * the first chunk of each document so the model still has something
     * concrete to ground on instead of falling back to general knowledge.
     */
    suspend fun search(
        sessionId: String,
        query: String,
        topK: Int = DEFAULT_TOP_K,
    ): List<RetrievedChunk> {
        val all = ragChunkDao.getBySession(sessionId)
        if (all.isEmpty()) return emptyList()

        val ranked = Bm25Retriever.rank(all.map { it.text }, query, topK)
        if (ranked.isNotEmpty()) {
            return ranked.map { (idx, score) ->
                val e = all[idx]
                RetrievedChunk(e.text, e.docName, score)
            }
        }

        // Fallback: no query term matched. Hand the model the first
        // chunk of each document so it can still answer overview /
        // intent questions ("summarise this", "what's this about?")
        // without going fully off-doc.
        return all.groupBy { it.docUri }
            .map { (_, byDoc) -> byDoc.minByOrNull { it.chunkIndex }!! }
            .take(topK)
            .map { RetrievedChunk(it.text, it.docName, score = 0.0) }
    }

    /** Distinct documents indexed under [sessionId] — for the "is this chat RAG-augmented?" gate. */
    suspend fun hasIndexedDocs(sessionId: String): Boolean =
        ragChunkDao.listDocUris(sessionId).isNotEmpty()

    /** Wipe all indexed chunks for [sessionId]. Called on session-delete and clear-history. */
    suspend fun deleteForSession(sessionId: String) {
        ragChunkDao.deleteBySession(sessionId)
    }

    // ── Internal ─────────────────────────────────────────────────────────

    /**
     * Char-based overlapping chunker. Word-aware splits matter for some
     * embedders but BM25 tokenises internally anyway, so we keep the
     * chunker plain-char for simplicity. The +overlap window preserves
     * the answer across boundaries.
     */
    private fun chunkText(text: String): List<String> {
        val cleaned = text.trim()
        if (cleaned.length <= CHUNK_SIZE) return listOf(cleaned)
        val chunks = ArrayList<String>(cleaned.length / CHUNK_SIZE + 1)
        var start = 0
        while (start < cleaned.length) {
            val end = (start + CHUNK_SIZE).coerceAtMost(cleaned.length)
            val piece = cleaned.substring(start, end).trim()
            if (piece.isNotBlank()) chunks += piece
            if (end == cleaned.length) break
            start = end - CHUNK_OVERLAP
        }
        return chunks
    }
}

/** Result of a [RagDocumentRepository.search] call. */
data class RetrievedChunk(
    val text: String,
    val docName: String,
    val score: Double,
)
