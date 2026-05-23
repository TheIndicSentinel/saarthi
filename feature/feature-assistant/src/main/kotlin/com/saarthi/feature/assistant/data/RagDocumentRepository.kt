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

        // Sentinel chunkIndex for an auto-extracted document outline —
        // headings scraped during indexing and stored as a single virtual
        // chunk that meta-queries ("what sections are there?", "summarise
        // this") rank first. Sits in the SAME table to avoid a schema
        // bump; the < 0 index is the only thing that distinguishes it
        // from a regular content chunk.
        private const val OUTLINE_CHUNK_INDEX = -1

        // Queries that demand a STRUCTURAL view of the doc — not BM25.
        // BM25 fails on these by definition: the query terms ("sections",
        // "overview") almost never appear in the doc body. Detect them up
        // front and route to structural sampling instead so the user gets
        // a useful answer about doc shape rather than the BM25 fallback's
        // generic first-chunk hand-back.
        private val META_QUERY_PHRASES = listOf(
            // English
            "what sections", "what section", "list sections", "list section",
            "what chapters", "list chapters", "table of contents", " toc ", "the toc",
            "summarize this", "summarise this", "summary of this", "summary of the",
            "give an overview", "give me an overview", "give overview",
            "what's in this", "what is in this", "what does this cover",
            "what topics", "what is this document", "describe this document",
            "outline of this", "outline this", "what are the headings",
            // Hindi (Latin + Devanagari)
            "saar batao", "saaransh", "kya likha", "vishaysuchi",
            "अनुक्रम", "विषयसूची", "सारांश",
        )
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

        val entities = ArrayList<RagChunkEntity>(chunks.size + 1)

        // Outline (auto-detected headings) — saved as a virtual chunk at
        // chunkIndex = -1 so the table doesn't need a new column. Meta
        // queries surface it first; normal BM25 ignores it because we
        // filter to chunkIndex >= 0 before ranking. Doc with no detectable
        // headings → no outline chunk, no behaviour change.
        extractOutline(text)?.let { outlineText ->
            entities.add(
                RagChunkEntity(
                    sessionId = sessionId,
                    docUri = uriKey,
                    docName = file.name,
                    mimeType = file.mimeType,
                    chunkIndex = OUTLINE_CHUNK_INDEX,
                    text = outlineText,
                )
            )
        }

        chunks.forEachIndexed { idx, chunk ->
            entities.add(
                RagChunkEntity(
                    sessionId = sessionId,
                    docUri = uriKey,
                    docName = file.name,
                    mimeType = file.mimeType,
                    chunkIndex = idx,
                    text = chunk,
                )
            )
        }
        ragChunkDao.insertAll(entities)
        val hasOutline = entities.firstOrNull()?.chunkIndex == OUTLINE_CHUNK_INDEX
        Timber.d("RAG: indexed ${entities.size} chunks (outline=$hasOutline) for ${file.name} (session=$sessionId)")
    }

    /**
     * Search top chunks for [query] across every document indexed under
     * [sessionId]. Three retrieval routes:
     *
     *  • META queries ("what sections", "summarise", "overview"): structural
     *    sample — outline chunk first, then first/middle/last content
     *    chunks per doc. BM25 is bypassed because the query terms almost
     *    never appear in the doc body for these question shapes.
     *  • Normal queries: BM25 ranks content chunks. The outline chunk is
     *    excluded from BM25 so it doesn't compete with real evidence.
     *  • Zero BM25 hits: fall back to first chunk per doc so the model
     *    has something concrete to ground on (better than refusing).
     */
    suspend fun search(
        sessionId: String,
        query: String,
        topK: Int = DEFAULT_TOP_K,
    ): List<RetrievedChunk> {
        val all = ragChunkDao.getBySession(sessionId)
        if (all.isEmpty()) return emptyList()

        if (isMetaQuery(query)) {
            return structuralSample(all, topK)
        }

        // BM25 sees only content chunks. The outline chunk is curated
        // meta, not evidence, so it should not be ranked against the
        // user's actual question — otherwise a query like "tell me about
        // the methodology" could surface the heading list instead of the
        // paragraph that explains the methodology.
        val contentChunks = all.filter { it.chunkIndex >= 0 }
        if (contentChunks.isEmpty()) return emptyList()

        val ranked = Bm25Retriever.rank(contentChunks.map { it.text }, query, topK)
        if (ranked.isNotEmpty()) {
            return ranked.map { (idx, score) ->
                val e = contentChunks[idx]
                RetrievedChunk(e.text, e.docName, score)
            }
        }

        // Fallback: no query term matched. Hand the model the first
        // chunk of each document so it can still answer general intent
        // questions without going fully off-doc.
        return contentChunks
            .groupBy { it.docUri }
            .map { (_, byDoc) -> byDoc.minByOrNull { it.chunkIndex }!! }
            .take(topK)
            .map { RetrievedChunk(it.text, it.docName, score = 0.0) }
    }

    /**
     * Outline + evenly-spaced content samples per document. Used for
     * meta queries where the user is asking ABOUT the document's shape,
     * not its content.
     *
     * Per-doc budget: roughly `topK / docCount` content chunks, minimum 2
     * (first + last). The outline chunk is added on top — so a single-doc
     * meta query returns 1 outline + ≥2 content samples (typically 4-5
     * items total within topK).
     */
    private fun structuralSample(all: List<RagChunkEntity>, topK: Int): List<RetrievedChunk> {
        val byDoc = all.groupBy { it.docUri }
        val perDoc = (topK / byDoc.size).coerceAtLeast(2)
        val result = mutableListOf<RetrievedChunk>()
        for ((_, docChunks) in byDoc) {
            // Outline first if we extracted one at index time.
            val outline = docChunks.firstOrNull { it.chunkIndex == OUTLINE_CHUNK_INDEX }
            if (outline != null) {
                result.add(RetrievedChunk(outline.text, outline.docName, score = 1.0))
            }
            val content = docChunks.filter { it.chunkIndex >= 0 }.sortedBy { it.chunkIndex }
            if (content.isEmpty()) continue
            val samples = if (content.size <= perDoc) {
                content
            } else {
                // Evenly-spaced indices including first and last.
                val indices = (0 until perDoc).map { i ->
                    ((i.toDouble() / (perDoc - 1).coerceAtLeast(1)) * (content.size - 1)).toInt()
                }.distinct()
                indices.map { content[it] }
            }
            samples.forEach { result.add(RetrievedChunk(it.text, it.docName, score = 0.0)) }
        }
        return result
    }

    private fun isMetaQuery(query: String): Boolean {
        val q = " ${query.lowercase().trim()} "  // pad so " toc " matches as a token
        return META_QUERY_PHRASES.any { q.contains(it) }
    }

    /**
     * Cheap heading detector. Runs once at index time. Looks for the
     * forms most likely to be a heading in OCR'd PDFs and plain text:
     *  1. "Chapter N", "Section N", "Part N", "Appendix X", "Unit N"
     *  2. All-caps short lines ("INTRODUCTION", "METHODOLOGY")
     *  3. Numbered headings ("1. Intro", "2.1 Background")
     *  4. Short title-case lines immediately followed by a blank line
     *
     * Returns null when fewer than 2 headings were found (a one-line
     * "outline" isn't useful). Caps at 20 headings to keep the outline
     * chunk well under the prompt budget.
     */
    private fun extractOutline(text: String, maxHeadings: Int = 20): String? {
        val lines = text.lines()
        val headings = mutableListOf<String>()
        for ((idx, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.length < 3 || line.length > 80) continue
            if (isLikelyHeading(line, lines, idx)) {
                if (headings.none { it.equals(line, ignoreCase = true) }) {
                    headings.add(line)
                    if (headings.size >= maxHeadings) break
                }
            }
        }
        if (headings.size < 2) return null
        return buildString {
            append("Document outline (auto-detected headings):\n")
            headings.forEach { append("- "); append(it); append("\n") }
        }.trimEnd()
    }

    private fun isLikelyHeading(line: String, lines: List<String>, idx: Int): Boolean {
        // Skip the page markers FileContentExtractor injects during PDF OCR —
        // they look heading-shaped but tell the model nothing useful.
        if (line.matches(Regex("^---\\s*Page\\s+\\d+\\s*---$", RegexOption.IGNORE_CASE))) return false

        // 1. "Chapter N", "Section N", etc.
        if (line.matches(Regex("(?i)^(chapter|section|part|appendix|annexure|article|unit)\\s+[\\w\\d.]+.*"))) return true

        // 2. All-caps short line with at least one letter.
        if (line == line.uppercase() && line.any { it.isLetter() } && line.length <= 60) return true

        // 3. Numbered heading: "1. Intro", "1.2 Background", "2.1.3 Methods".
        //    Requires the content after the number to start with a letter
        //    (Latin or Devanagari) so we don't match bare list-numbered
        //    body text like "1. then we walked to the bus stop".
        if (line.matches(Regex("^\\d+(\\.\\d+)*[\\s.:]+[A-Z\\u0900-\\u097F].{2,}$"))) return true

        // 4. Short title-case-ish line followed by blank — common for
        //    body-text-author headings that lack other formatting cues.
        val words = line.split(Regex("\\s+"))
        if (words.size in 1..7 && !line.endsWith(".") && !line.endsWith(",")) {
            val titleCase = words.all { w ->
                w.isEmpty() || w[0].isUpperCase() || w[0].isDigit() || !w[0].isLetter()
            }
            val nextBlank = idx + 1 < lines.size && lines[idx + 1].trim().isBlank()
            if (titleCase && nextBlank) return true
        }
        return false
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
