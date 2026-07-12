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
        // Top-K returned to the prompt builder. Raised from 5 to 8 now
        // that the LARGE-tier ragBudget is ~2650c (was ~1050c) — the
        // larger chunk space can hold 4-5 full chunks, so retrieving 8
        // gives BM25 more candidates and structural sampling a wider net.
        private const val DEFAULT_TOP_K = 8

        // Heading-anchored retrieval. When a query strongly matches a
        // detected outline heading (e.g. "what are special provisions"
        // → the "SPECIAL PROVISIONS" chapter), the section's own chunks
        // are pulled to the top BEFORE BM25 — fixing the production miss
        // where "What are special provisions" retrieved scattered chunks
        // (top score 5.86, none being the actual section) and produced a
        // thin 45-token answer. Capped so anchoring never crowds out the
        // BM25 evidence that fills the remaining slots.
        private const val HEADING_ANCHOR_MAX = 3
        // Synthetic score for anchored chunks — above any realistic BM25
        // score so they sort first and survive topK truncation, and clearly
        // distinguishable in the debug log.
        private const val HEADING_ANCHOR_SCORE = 50.0

        // Sentinel chunkIndex for an auto-extracted document outline —
        // headings scraped during indexing and stored as a single virtual
        // chunk that meta-queries ("what sections are there?", "summarise
        // this") rank first. Sits in the SAME table to avoid a schema
        // bump; the < 0 index is the only thing that distinguishes it
        // from a regular content chunk.
        private const val OUTLINE_CHUNK_INDEX = -1

        // Token triggers — if ANY of these tokens appears as a standalone
        // word in the query, route to structural sampling instead of BM25.
        // Token-based (not phrase-based) so "Summarise document content"
        // — which has no "summarise this"-style anchor — still hits the
        // meta path. The phrase list below catches multi-word forms.
        private val META_TOKEN_TRIGGERS = setOf(
            // Whole-doc summary / analysis
            "summarise", "summarize", "summary", "synopsis",
            "tldr", "tl;dr", "overview", "outline", "toc",
            "analyse", "analyze", "analysis",   // "Analyse attached document"
            // Structure / listing
            "sections", "chapters", "headings",
            "list", "lists", "listed",           // "list all sections / topics"
            "topics", "topic",
            // Positional — bottom of the doc.
            "conclusion", "concluding", "conclude", "conclusions",
            "ending", "endings", "final", "wrap-up", "wrapup",
            // End-of-document reference sections — structural tail sampling
            // handles these better than BM25 because the headings often
            // appear only once (or not at all in the text layer for PDFs
            // where the heading was an image).
            "glossary", "glossaries",
            "appendix", "appendices", "annexure", "annexures",
            // Positional — top of the doc.
            "introduction", "intro", "preface", "foreword", "beginning",
            "preamble", "opening",
            // Hindi (Latin transliteration)
            "saaransh", "vishaysuchi", "anukramani",
        )

        // Structural terms whose content sits near the END of most documents.
        // When these appear in a meta-query, pickTailSamples() is used instead
        // of the default evenly-spaced sampling — the glossary of a phone-repair
        // manual is in the last 5 % of the doc, not the middle.
        private val TAIL_STRUCTURE_TOKENS = setOf(
            "glossary", "glossaries",
            "appendix", "appendices", "annexure", "annexures",
            "bibliography", "references",
            "answers", "solutions",   // workbook / activity answer keys
        )

        // Query words that signal continuation of the previous turn.
        // When any of these lead the query AND a prior question is available,
        // the search bypasses meta-routing and runs BM25 with the combined
        // prior+current query — so "also list meaning of each" retrieves the
        // same evidence as the prior "meaning of terms associated with hazards".
        private val FOLLOW_UP_TOKENS = setOf(
            "also", "additionally", "furthermore", "moreover",
            "elaborate", "expand",
        )

        // Devanagari triggers — match as substring because Devanagari
        // morphology often glues these to suffixes without whitespace.
        private val META_DEVANAGARI_PATTERN = Regex("(अनुक्रम|सारांश|विषयसूची|सार)")

        // Multi-word phrase triggers — catch turns of phrase the token
        // list can't ("what is this document about", "tell me about this
        // document"). Kept short; tokens above carry most of the weight.
        private val META_QUERY_PHRASES = listOf(
            "what's this", "what is this",
            "what are the sections", "what are the chapters", "what are the headings",
            "what are the topics", "what are the subjects",
            "table of contents",
            "tell me about this document", "tell me what this",
            "what does this cover", "what does it cover",
            "describe this document", "describe the document",
            "give me an overview", "give an overview",
            "give overview", "give a summary",
            "analyse the", "analyze the",
            "analyse attached", "analyze attached",
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
        val totalChars = entities.sumOf { it.text.length }
        // DebugLogger so it surfaces in the user-visible saarthi_debug.log
        // — Timber alone is invisible in production captures.
        com.saarthi.core.inference.DebugLogger.log(
            "RAG",
            "indexed ${entities.size} chunks (${totalChars}c, outline=$hasOutline) for ${file.name} (session=$sessionId)"
        )
        Timber.d("RAG: indexed ${entities.size} chunks (${totalChars}c, outline=$hasOutline) for ${file.name}")
    }

    /**
     * Search top chunks for [query] across every document indexed under
     * [sessionId]. Four retrieval routes:
     *
     *  • FOLLOW-UP queries (starts with "also", "additionally", etc. AND
     *    [priorQuery] is available): bypass meta-routing, run BM25 with
     *    the combined prior+current query. "Also list meaning of each"
     *    paired with prior "meaning of terms associated with hazards"
     *    retrieves the correct hazards chunks instead of a generic sample.
     *  • META queries ("summarise", "glossary", "overview", etc.):
     *    structural sample — outline first, then evenly-spaced content
     *    (or tail-biased for glossary/appendix). BM25 is bypassed because
     *    the query terms rarely appear verbatim in the doc body.
     *  • Normal queries: BM25 ranks content chunks. Top hits get neighbor
     *    expansion; structural padding fills any remaining topK slots.
     *  • Zero BM25 hits: full structural fallback gives the model
     *    representative content to answer from (better than refusing).
     */
    suspend fun search(
        sessionId: String,
        query: String,
        topK: Int = DEFAULT_TOP_K,
        /**
         * Restricts retrieval to chunks belonging to these document URIs.
         */
        restrictToDocUris: Set<String>? = null,
        /**
         * The last completed user question from the conversation history.
         * Used for two purposes:
         *  1. Follow-up expansion: when [query] starts with a continuation
         *     token ("also", "additionally", …), BM25 runs on
         *     `"$priorQuery $query"` so the follow-up retrieves the same
         *     evidence region as the prior turn.
         *  2. Zero-hit fallback: if BM25 finds nothing for [query] alone,
         *     retry with [priorQuery] to surface the relevant context.
         * Pass null (or blank) to disable both behaviours.
         */
        priorQuery: String? = null,
    ): List<RetrievedChunk> {
        val raw = ragChunkDao.getBySession(sessionId)
        // Defensive: if the URI restriction produces an empty set (e.g.
        // the focused doc was deleted from Room since being marked) fall
        // back to the full corpus rather than returning no context at all.
        val all = if (!restrictToDocUris.isNullOrEmpty()) {
            raw.filter { it.docUri in restrictToDocUris }.ifEmpty { raw }
        } else raw
        if (all.isEmpty()) return emptyList()

        // Follow-up detection: if the query STARTS with a continuation
        // token AND we have context from the prior turn, bypass meta-routing
        // and use BM25 on the combined query. This handles "also list meaning
        // of each mentioned" continuing "meaning of terms associated with
        // hazards" — the combined BM25 query surfaces the same hazard chunks
        // rather than a generic structural sample.
        val queryTokens = query.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
        val isFollowUp = !priorQuery.isNullOrBlank() && queryTokens.take(4).any { it in FOLLOW_UP_TOKENS }

        if (isMetaQuery(query) && !isFollowUp) {
            return structuralSample(all, topK, query)
        }

        // BM25 sees only content chunks. The outline chunk is curated
        // meta, not evidence, so it should not be ranked against the
        // user's actual question.
        val contentChunks = all.filter { it.chunkIndex >= 0 }
        if (contentChunks.isEmpty()) return emptyList()

        // Heading-anchored retrieval: if the query closely matches a
        // detected outline heading, surface that section's chunks first.
        // Additive — BM25 still ranks below; this only guarantees the
        // section the user named is present and leading.
        val anchoredEntities = anchoredHeadingChunks(all, contentChunks, query)

        // Expand the query when following up on the prior turn.
        val effectiveQuery = if (isFollowUp && !priorQuery.isNullOrBlank()) {
            "${priorQuery.take(150)} $query"
        } else {
            query
        }

        val ranked = Bm25Retriever.rank(contentChunks.map { it.text }, effectiveQuery, topK)

        // Neighbor expansion: for the top BM25 hits, also include the
        // *next* chunk in the same document. Answers often straddle a
        // chunk boundary — the keyword lands in chunk 5 but the actual
        // sentence finishes in chunk 6 (or the relevant numbers / table
        // sits one chunk later). Pulling in the immediate neighbor at
        // half-score is cheap insurance against missing the conclusion
        // of the matched passage.
        val docChunksByUri = contentChunks.groupBy { it.docUri }
            .mapValues { (_, list) -> list.sortedBy { it.chunkIndex } }

        val usedIds = LinkedHashSet<Long>()
        val bm25Hits = mutableListOf<RetrievedChunk>()
        // Seed with the anchored section chunks so BM25 dedupes against them
        // and they lead the final result.
        for (e in anchoredEntities) {
            if (usedIds.add(e.id)) {
                bm25Hits.add(RetrievedChunk(e.text, e.docName, HEADING_ANCHOR_SCORE, e.chunkIndex))
            }
        }
        for ((rank, scored) in ranked.withIndex()) {
            val entity = contentChunks[scored.index]
            if (usedIds.add(entity.id)) {
                bm25Hits.add(RetrievedChunk(entity.text, entity.docName, scored.score, entity.chunkIndex))
            }
            // Only the top-2 hits get neighbor expansion — beyond that
            // BM25 itself is probably surfacing the relevant chunks.
            if (rank < 2) {
                val docChunks = docChunksByUri[entity.docUri] ?: continue
                val posInDoc = docChunks.indexOfFirst { it.id == entity.id }
                docChunks.getOrNull(posInDoc + 1)?.let { neighbor ->
                    if (usedIds.add(neighbor.id)) {
                        bm25Hits.add(
                            RetrievedChunk(
                                neighbor.text, neighbor.docName,
                                score = scored.score * 0.5,    // half-credit; neighbour, not exact match
                                chunkIndex = neighbor.chunkIndex,
                            )
                        )
                    }
                }
            }
        }

        // If BM25 + neighbors fully populated the slot, return as-is —
        // adding structural padding here would dilute strong signal.
        if (bm25Hits.size >= topK) return bm25Hits.take(topK)

        // Zero-hit retry with prior query: if the current query alone had no
        // BM25 vocabulary match (e.g. "Also list meaning of each" without
        // prior-query expansion) but the prior question does have matches,
        // run a second BM25 pass on the prior query to surface the same
        // evidence region. Half-score marks these as context rather than
        // exact matches. Skipped when we already expanded above (isFollowUp).
        if (bm25Hits.isEmpty() && !priorQuery.isNullOrBlank() && !isFollowUp) {
            val retryRanked = Bm25Retriever.rank(contentChunks.map { it.text }, priorQuery.take(150), topK)
            for (scored in retryRanked) {
                val entity = contentChunks[scored.index]
                if (usedIds.add(entity.id)) {
                    bm25Hits.add(
                        RetrievedChunk(entity.text, entity.docName, scored.score * 0.5, entity.chunkIndex)
                    )
                }
                if (bm25Hits.size >= topK) break
            }
            if (bm25Hits.size >= topK) return bm25Hits.take(topK)
        }

        // BM25 (+ neighbors) under-covered the query. Pad with
        // structural context — outline first, then first/middle/last
        // per doc — so the model has surrounding evidence to reason
        // against. Reuses `usedIds` populated by the neighbor-expansion
        // pass above so we never re-emit the same chunk.
        val padding = mutableListOf<RetrievedChunk>()

        // Outline (if extracted at index time) is the highest-value
        // padding item — gives the model a structural map of the doc.
        all.firstOrNull { it.chunkIndex == OUTLINE_CHUNK_INDEX }?.let { o ->
            padding.add(RetrievedChunk(o.text, o.docName, 0.0, OUTLINE_CHUNK_INDEX))
        }

        // Then structural samples per doc, skipping anything BM25
        // already returned.
        val byDoc = contentChunks.groupBy { it.docUri }
        val docCount = byDoc.size.coerceAtLeast(1)
        for ((_, docChunks) in byDoc) {
            val sorted = docChunks.sortedBy { it.chunkIndex }
            val perDoc = ((topK - bm25Hits.size - padding.size + docCount - 1) / docCount).coerceAtLeast(2)
            for (s in pickStructuralSamples(sorted, perDoc)) {
                if (s.id in usedIds) continue
                padding.add(RetrievedChunk(s.text, s.docName, 0.0, s.chunkIndex))
                usedIds.add(s.id)
                if (bm25Hits.size + padding.size >= topK) break
            }
            if (bm25Hits.size + padding.size >= topK) break
        }
        return bm25Hits + padding
    }

    /**
     * Evenly-spaced sample including first and last. Used for general
     * meta-query structural sampling and BM25 padding.
     */
    private fun pickStructuralSamples(sorted: List<RagChunkEntity>, count: Int): List<RagChunkEntity> {
        if (sorted.size <= count) return sorted
        val indices = (0 until count).map { i ->
            ((i.toDouble() / (count - 1).coerceAtLeast(1)) * (sorted.size - 1)).toInt()
        }.distinct()
        return indices.map { sorted[it] }
    }

    /**
     * Last [count] chunks, in document order. Used for tail-structure
     * meta-queries (glossary, appendix, bibliography, answer keys) whose
     * content sits near the END of most documents — evenly-spaced sampling
     * misses these sections because they represent only ~5–10 % of the doc.
     */
    private fun pickTailSamples(sorted: List<RagChunkEntity>, count: Int): List<RagChunkEntity> {
        return if (sorted.size <= count) sorted else sorted.takeLast(count)
    }

    /**
     * Outline + content samples per document. Used for meta queries
     * (structural overview, glossary, appendix, etc.).
     *
     * When [query] contains a tail-structure term (glossary, appendix,
     * answers, bibliography), content samples are taken from the END of the
     * document rather than evenly-spaced — these sections are always near
     * the end and evenly-spaced sampling reliably misses them.
     */
    private fun structuralSample(all: List<RagChunkEntity>, topK: Int, query: String = ""): List<RetrievedChunk> {
        val queryLower = query.lowercase()
        val isTailQuery = TAIL_STRUCTURE_TOKENS.any { queryLower.contains(it) }
        val byDoc = all.groupBy { it.docUri }
        val perDoc = (topK / byDoc.size).coerceAtLeast(2)
        val result = mutableListOf<RetrievedChunk>()
        for ((_, docChunks) in byDoc) {
            docChunks.firstOrNull { it.chunkIndex == OUTLINE_CHUNK_INDEX }?.let { o ->
                result.add(RetrievedChunk(o.text, o.docName, 1.0, OUTLINE_CHUNK_INDEX))
            }
            val content = docChunks.filter { it.chunkIndex >= 0 }.sortedBy { it.chunkIndex }
            if (content.isEmpty()) continue
            val samples = if (isTailQuery) pickTailSamples(content, perDoc)
                          else pickStructuralSamples(content, perDoc)
            samples.forEach { result.add(RetrievedChunk(it.text, it.docName, 0.0, it.chunkIndex)) }
        }
        return result
    }

    /**
     * If [query] strongly matches a heading in the auto-extracted outline,
     * return that section's leading chunks (the chunk containing the heading
     * plus the next few in document order) so they lead the result. Returns
     * empty when there's no outline, no heading match, or the heading text
     * couldn't be located in any chunk (e.g. it straddled a chunk boundary) —
     * in every such case retrieval falls back cleanly to BM25.
     */
    private fun anchoredHeadingChunks(
        all: List<RagChunkEntity>,
        contentChunks: List<RagChunkEntity>,
        query: String,
    ): List<RagChunkEntity> {
        val outline = all.firstOrNull { it.chunkIndex == OUTLINE_CHUNK_INDEX } ?: return emptyList()
        val heading = matchHeading(query, parseOutlineHeadings(outline.text)) ?: return emptyList()

        // Locate the section in document order, then take it + the following
        // chunks (the section body). Search per-doc so the index sequence is
        // contiguous within one document.
        for ((_, docChunks) in contentChunks.groupBy { it.docUri }) {
            val sorted = docChunks.sortedBy { it.chunkIndex }
            val pos = sorted.indexOfFirst { it.text.contains(heading, ignoreCase = true) }
            if (pos >= 0) {
                val section = sorted.subList(pos, minOf(pos + HEADING_ANCHOR_MAX, sorted.size)).toList()
                com.saarthi.core.inference.DebugLogger.log(
                    "RAG", "heading-anchored (headingLen=${heading.length}) → ${section.size} chunk(s)"
                )
                return section
            }
        }
        return emptyList()
    }

    private fun isMetaQuery(query: String): Boolean {
        val lower = query.lowercase().trim()
        if (lower.isEmpty()) return false
        // 1. Token-level: split on non-letter/digit so "Summarise document content"
        //    decomposes to {"summarise", "document", "content"} and "summarise"
        //    matches the trigger. Substring matching missed this — that was the
        //    02:38:24 production miss where ragChunks=1 instead of the structural
        //    sample.
        val tokens = lower.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
        if (tokens.any { it in META_TOKEN_TRIGGERS }) return true
        // 2. Devanagari script regex (whitespace-free morphology).
        if (META_DEVANAGARI_PATTERN.containsMatchIn(lower)) return true
        // 3. Multi-word phrase fallback.
        return META_QUERY_PHRASES.any { lower.contains(it) }
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
     * Sentence/word-aware overlapping chunker.
     *
     * Previous version did pure char slicing every 600 chars, which cut
     * "personal" into "per" + "sonal" and "have" into "ha" + "ve" — the
     * resulting chunk fragments could not match BM25 query tokens, so
     * relevant chunks scored 0. Visible in the production log at
     * 04:41:59 / 12:21:01: previews like `"sonal data is processed…"`
     * and `"ve, manage, review…"` came straight from mid-word cuts.
     *
     * Now: end each chunk at a sentence boundary (`.`, `!`, `?`, `\n`)
     * within the latter half of the chunk, or — if no sentence end is
     * found — at a word boundary. The next chunk also starts at a word
     * boundary so the overlap window doesn't reintroduce the fragment.
     */
    private fun chunkText(text: String): List<String> =
        chunkDocumentText(text, CHUNK_SIZE, CHUNK_OVERLAP)
}

/** Result of a [RagDocumentRepository.search] call. */
data class RetrievedChunk(
    val text: String,
    val docName: String,
    val score: Double,
    /**
     * Position of this chunk inside its document. -1 = auto-extracted
     * outline; ≥ 0 = sequential content chunk (0 is the first chunk).
     * Exposed so the prompt builder can emit a stable citation label
     * ("part 3 of 12") instead of just `[N]`.
     */
    val chunkIndex: Int = 0,
)

// ── Heading-anchored retrieval (pure, testable) ─────────────────────────────────

/**
 * Words to drop before matching a query against a heading: interrogatives,
 * articles, and document-reference filler ("tell me about the … section").
 * What survives is the content the user actually named.
 */
private val HEADING_STOPWORDS = setOf(
    "what", "whats", "which", "are", "is", "the", "of", "and", "a", "an", "to",
    "for", "in", "on", "this", "that", "these", "those", "tell", "me", "about",
    "explain", "describe", "give", "please", "document", "doc", "section",
    "sections", "chapter", "chapters", "part", "parts", "all", "any", "my",
    "your", "under", "as", "per", "does", "do", "says", "say", "content",
    "contents", "provide", "show", "list",
)

/**
 * Normalise a heading or query to its significant content tokens: lowercase,
 * split on non-alphanumerics, drop stopwords and tokens shorter than 3 chars
 * (articles, roman numerals like "iv"), and strip a trailing plural "s" so
 * "provisions" and "provision" match. Symmetric — the same transform runs on
 * both sides, so plural mangling cancels out.
 */
internal fun headingTokens(s: String): Set<String> =
    s.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .asSequence()
        .filter { it.length >= 3 }
        .filter { it !in HEADING_STOPWORDS }
        .map { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }
        .toSet()

/** Heading lines ("- …") parsed out of the auto-extracted outline chunk. */
internal fun parseOutlineHeadings(outlineText: String): List<String> =
    outlineText.lines()
        .map { it.trim() }
        .filter { it.startsWith("- ") }
        .map { it.removePrefix("- ").trim() }
        .filter { it.isNotEmpty() }

/**
 * Best heading from [headings] that the [query] names, or null. Conservative:
 * every *significant* heading token (length ≥ 4, so roman numerals and short
 * connectives don't gate the match) must be present in the query, and the
 * heading must carry at least ~6 chars of significant tokens so a single short
 * word can't anchor. Ties break toward the more specific (more-token) heading.
 *
 * Requiring all significant tokens present is the safe direction: it fires only
 * on a clear section reference ("special provisions" → "SPECIAL PROVISIONS")
 * and stays silent on partial overlaps ("rights" alone won't match "RIGHTS AND
 * DUTIES OF DATA PRINCIPAL"), so anchoring never hijacks an ordinary query.
 */
internal fun matchHeading(query: String, headings: List<String>): String? {
    val qTokens = headingTokens(query)
    if (qTokens.isEmpty()) return null
    var best: String? = null
    var bestScore = 0
    for (h in headings) {
        val significant = headingTokens(h).filter { it.length >= 4 }
        if (significant.isEmpty() || significant.sumOf { it.length } < 6) continue
        if (significant.all { it in qTokens } && significant.size > bestScore) {
            bestScore = significant.size
            best = h
        }
    }
    return best
}

// ── Document chunker (pure, testable) ──────────────────────────────────────────

/**
 * Sentence/word-aware overlapping chunker — the quality floor for RAG retrieval.
 * A bad chunker (mid-word slicing) produces fragments that can't match BM25 query
 * tokens, so the right chunk scores 0 and the model answers from the wrong text.
 *
 * Each chunk ends at a sentence boundary (`.`, `!`, `?`, `\n`) found in the
 * latter half of the window, or — failing that — at the next word boundary so a
 * word is never split. The next chunk starts at a word boundary too, so the
 * overlap window never reintroduces a fragment.
 *
 * Top-level `internal` so it is unit-testable without constructing the
 * repository + its Room DAO.
 */
internal fun chunkDocumentText(text: String, chunkSize: Int, overlap: Int): List<String> {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return emptyList()
    if (cleaned.length <= chunkSize) return listOf(cleaned)

    val chunks = ArrayList<String>(cleaned.length / chunkSize + 1)
    var start = 0
    while (start < cleaned.length) {
        // Skip leading whitespace so a chunk never starts with a blank.
        while (start < cleaned.length && cleaned[start].isWhitespace()) start++
        if (start >= cleaned.length) break

        val hardEnd = (start + chunkSize).coerceAtMost(cleaned.length)
        val end = when {
            hardEnd >= cleaned.length -> hardEnd
            else -> {
                val sentenceEnd = findSentenceBoundary(cleaned, start + chunkSize / 2, hardEnd)
                if (sentenceEnd > 0) sentenceEnd
                else findWordBoundary(cleaned, hardEnd)
            }
        }

        val piece = cleaned.substring(start, end).trim()
        if (piece.isNotBlank()) chunks += piece
        if (end >= cleaned.length) break

        val overlapAnchor = (end - overlap).coerceAtLeast(start + 1)
        start = skipToWordStart(cleaned, overlapAnchor)
    }
    return chunks
}

/** Latest position in `[lo, hi)` that ends a sentence (returns char AFTER the terminator), or 0 if none. */
private fun findSentenceBoundary(text: String, lo: Int, hi: Int): Int {
    if (lo >= hi) return 0
    for (i in (hi - 1) downTo lo.coerceAtLeast(0)) {
        val c = text[i]
        if (c == '.' || c == '!' || c == '?' || c == '\n') {
            val next = i + 1
            if (next >= text.length || text[next].isWhitespace()) return next
        }
    }
    return 0
}

/** Walk RIGHT from `idx` until the first whitespace — returns that position so chunks end on whole words. */
private fun findWordBoundary(text: String, idx: Int): Int {
    var i = idx.coerceAtMost(text.length)
    while (i < text.length && !text[i].isWhitespace()) i++
    return i
}

/**
 * Return a clean word-start position at or after [idx] so the next chunk never
 * begins on a word fragment. If [idx] lands in the MIDDLE of a word, advance to
 * the end of that word first (otherwise the overlap window would reintroduce a
 * leading fragment like "rd57" that can't match the BM25 token "word57"), then
 * skip any whitespace to land on the next word's first character.
 */
private fun skipToWordStart(text: String, idx: Int): Int {
    var i = idx.coerceAtLeast(0)
    // Mid-word? (previous and current chars are both non-whitespace) → finish the word.
    if (i in 1 until text.length && !text[i - 1].isWhitespace() && !text[i].isWhitespace()) {
        while (i < text.length && !text[i].isWhitespace()) i++
    }
    while (i < text.length && text[i].isWhitespace()) i++
    return i
}
