package com.saarthi.feature.assistant.data

import com.saarthi.core.common.sqliteWriteWithRetry
import com.saarthi.core.memory.db.RagChunkDao
import com.saarthi.core.memory.db.RagChunkEntity
import com.saarthi.core.rag.Bm25Retriever
import com.saarthi.feature.assistant.domain.AttachedFile
import java.util.concurrent.ConcurrentHashMap
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
    /** R1 — per-chunk token cache; invalidated when a session's chunks are deleted. */
    private val chunkTokenCache = ConcurrentHashMap<Long, List<String>>()
    private val cachedChunkIdsBySession = ConcurrentHashMap<String, MutableSet<Long>>()

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
        const val DEFAULT_TOP_K = 8

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
        /** sizeBytes:textChars stamp so a changed re-attach can replace stale chunks. */
        private const val FINGERPRINT_CHUNK_INDEX = -2

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
        // When these appear in a meta-query, the zero-score fallback takes
        // the last chunks of each file instead of the opening excerpt.
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

        // Devanagari triggers live in [META_DEVANAGARI_PATTERN] (file-level).
        // Bare "सार" is excluded — it is a substring of ordinary words.

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

        /**
         * Why this query took the overview/meta path. Logged as meta=list etc.
         * Tokens only — never the raw query.
         */
        internal fun metaRouteReason(query: String): String? {
            val lower = query.lowercase().trim()
            if (lower.isEmpty()) return null
            val tokens = lower.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
            tokens.firstOrNull { it in META_TOKEN_TRIGGERS }?.let { return it }
            if (isDevanagariMetaTrigger(lower)) return "devanagari"
            if (isIndicMetaTrigger(lower)) return "indic"
            if (META_QUERY_PHRASES.any { lower.contains(it) }) return "phrase"
            return null
        }
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
        if (file.error != null) return
        val text = file.extractedText?.trim().orEmpty()
        if (text.isEmpty() || extractionFailureMessage(text) != null) return

        val t0 = System.nanoTime()
        val uriKey = file.uri.toString()
        val stamp = contentStamp(file.sizeBytes, text.length)
        val existing = sqliteWriteWithRetry { ragChunkDao.getByDoc(sessionId, uriKey) }
        if (existing.isNotEmpty()) {
            val stored = existing.firstOrNull { it.chunkIndex == FINGERPRINT_CHUNK_INDEX }?.text
            if (!shouldReplaceIndex(stored, stamp)) return
            sqliteWriteWithRetry { ragChunkDao.deleteByDoc(sessionId, uriKey) }
        }

        val chunks = chunkText(text)
        if (chunks.isEmpty()) return

        val entities = ArrayList<RagChunkEntity>(chunks.size + 2)
        entities.add(
            RagChunkEntity(
                sessionId = sessionId,
                docUri = uriKey,
                docName = file.name,
                mimeType = file.mimeType,
                chunkIndex = FINGERPRINT_CHUNK_INDEX,
                text = stamp,
            )
        )

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
        sqliteWriteWithRetry { ragChunkDao.insertAll(entities) }
        val hasOutline = entities.any { it.chunkIndex == OUTLINE_CHUNK_INDEX }
        val totalChars = entities.filter { it.chunkIndex >= 0 }.sumOf { it.text.length }
        val indexMs = (System.nanoTime() - t0) / 1_000_000
        logRag(
            ragIndexLogLine(
                chunkCount = entities.size,
                chars = totalChars,
                hasOutline = hasOutline,
                nameLen = file.name.length,
                sessionIdLen = sessionId.length,
                indexMs = indexMs,
            ),
        )
    }

    /**
     * Distinct documents indexed under [sessionId], oldest first.
     * Used for the prompt manifest ("Documents in this chat: …").
     */
    suspend fun listSessionDocuments(sessionId: String): List<SessionRagDocument> {
        val rows = sqliteWriteWithRetry { ragChunkDao.getBySession(sessionId) }
        if (rows.isEmpty()) return emptyList()
        return rows.groupBy { it.docUri }
            .map { (uri, chunks) ->
                val newest = chunks.maxBy { it.createdAt }
                SessionRagDocument(uri = uri, name = newest.docName, lastIndexedAt = newest.createdAt)
            }
            .sortedBy { it.lastIndexedAt }
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
         * Files attached on this turn. Ranked higher but never used as a
         * hard filter — every indexed file in the session stays searchable.
         */
        boostDocUris: Set<String> = emptySet(),
        /**
         * Hard filter (NOT a boost). When non-empty, retrieval is restricted
         * to ONLY these document URIs — every other indexed file in the
         * session is excluded from the corpus for this turn. Used on an
         * attach turn so a brand-new file's "give an overview" question is
         * answered from that file alone, never mixed with excerpts of the
         * documents attached earlier in the chat (the multi-file attach
         * deflection, G1). Empty on ordinary turns → full session corpus,
         * exactly as before. If the restricted set has no indexed chunks
         * (e.g. the attached file was an unreadable image), the search
         * returns empty and the caller surfaces it via the unreadable note
         * — we deliberately do NOT fall back to the full corpus, which would
         * reintroduce the cross-file mixing this filter exists to prevent.
         */
        restrictDocUris: Set<String> = emptySet(),
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
        /**
         * When true (user chat), files whose indexed body is ≤
         * [FileContentExtractor.WHOLE_FILE_CHARS] are returned in full
         * document order instead of a BM25 subset. Pack search leaves this
         * false so Kisan top-K is unchanged.
         */
        expandSmallFiles: Boolean = true,
    ): List<RetrievedChunk> {
        val t0 = System.nanoTime()
        val sessionRows = sqliteWriteWithRetry { ragChunkDao.getBySession(sessionId) }
        // Attach-turn scoping (G1): restrict the corpus to the just-attached
        // files so their overview/summary is not answered from a mix of the
        // earlier documents' excerpts. No fall-back to the full corpus on an
        // empty result — see restrictDocUris kdoc.
        val all = if (restrictDocUris.isNotEmpty()) {
            sessionRows.filter { it.docUri in restrictDocUris }
        } else {
            sessionRows
        }
        val sessionDocCount = all.map { it.docUri }.filter { it.isNotEmpty() }.distinct().size
        if (all.isEmpty()) {
            val searchMs = (System.nanoTime() - t0) / 1_000_000
            logRag(
                ragSearchLogLine(
                    docCount = 0,
                    boostCount = boostDocUris.size,
                    path = RagSearchPath.empty,
                    hitCount = 0,
                    queryLen = query.length,
                    searchMs = searchMs,
                ),
            )
            return emptyList()
        }
        val recencyUri = all.maxBy { it.createdAt }.docUri
        val sessionFiles = all.groupBy { it.docUri }.map { (uri, chunks) ->
            uri to chunks.first().docName
        }
        val route = routeQuery(query, sessionFiles)
        var headingChunkCount = 0
        fun finish(hits: List<RetrievedChunk>): List<RetrievedChunk> {
            // All-zero / overview: rebuild per file (outline + contiguous
            // opening, or spaced samples for "which file"). Real BM25 hits
            // (body score > 0) are left as ranked.
            val resolved = if (hasPositiveBodyHit(hits)) hits
                else structuralSample(all, topK, query, spaced = route.whichFile)
            val docCount = resolved.map { it.docUri }.filter { it.isNotEmpty() }.distinct().size.coerceAtLeast(1)
            val minSlots = if (route.equalSlots) (topK / docCount).coerceAtLeast(1) else 1
            val allocated = allocatePerDocSlots(
                applySessionBoost(resolved, boostDocUris, recencyUri, route.namedDocUris),
                topK,
                minSlots,
            )
            val contentEntities = all.filter { it.chunkIndex >= 0 }
            val excerpted = coherentExcerptForLowRelevance(allocated, contentEntities)
            if (!expandSmallFiles) return excerpted
            val fullByUri = contentEntities
                .groupBy { it.docUri }
                .mapValues { (_, chunks) ->
                    chunks.sortedBy { it.chunkIndex }.map { it.toRetrieved(1.0) }
                }
            return coherentExcerptForLowRelevance(
                expandWholeSmallFiles(excerpted, fullByUri),
                contentEntities,
            )
        }

        // Follow-up detection: if the query STARTS with a continuation
        // token AND we have context from the prior turn, bypass meta-routing
        // and use BM25 on the combined query. This handles "also list meaning
        // of each mentioned" continuing "meaning of terms associated with
        // hazards" — the combined BM25 query surfaces the same hazard chunks
        // rather than a generic structural sample.
        val queryTokens = query.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
        val isFollowUp = !priorQuery.isNullOrBlank() && queryTokens.take(4).any { it in FOLLOW_UP_TOKENS }
        val metaReason = if (isFollowUp) null else metaRouteReason(query)
        fun done(hits: List<RetrievedChunk>, path: RagSearchPath): List<RetrievedChunk> {
            val searchMs = (System.nanoTime() - t0) / 1_000_000
            logRag(
                ragSearchLogLine(
                    docCount = sessionDocCount,
                    boostCount = boostDocUris.size,
                    path = path,
                    hitCount = hits.size,
                    queryLen = query.length,
                    searchMs = searchMs,
                    named = route.namedDocUris.size,
                    equalSlots = route.equalSlots,
                    whichFile = route.whichFile,
                    thisDocument = route.thisDocument,
                    followUp = isFollowUp,
                    metaReason = metaReason,
                    headingChunks = headingChunkCount,
                ),
            )
            return hits
        }

        if ((metaReason != null || route.whichFile) && !isFollowUp) {
            val path = if (metaReason != null) RagSearchPath.meta else RagSearchPath.structural
            return done(finish(emptyList()), path)
        }

        // BM25 sees only content chunks. The outline chunk is curated
        // meta, not evidence, so it should not be ranked against the
        // user's actual question.
        val contentChunks = all.filter { it.chunkIndex >= 0 }
        if (contentChunks.isEmpty()) return done(emptyList(), RagSearchPath.empty)

        // Heading-anchored retrieval: if the query closely matches a
        // detected outline heading, surface that section's chunks first.
        // Additive — BM25 still ranks below; this only guarantees the
        // section the user named is present and leading.
        val anchoredEntities = anchoredHeadingChunks(all, contentChunks, query)
        headingChunkCount = anchoredEntities.size

        // Expand the query when following up on the prior turn.
        val effectiveQuery = if (isFollowUp && !priorQuery.isNullOrBlank()) {
            "${priorQuery.take(150)} ${route.expandedQuery}"
        } else {
            route.expandedQuery
        }

        val uniqueDocs = contentChunks.map { it.docUri }.distinct().size.coerceAtLeast(1)
        val rankK = (topK * uniqueDocs).coerceAtMost(contentChunks.size).coerceAtLeast(topK)
        val ranked = rankContentChunks(contentChunks, sessionId, effectiveQuery, rankK)

        // Neighbor expansion: for the top BM25 hits, also include the
        // *next* chunk in the same document. Answers often straddle a
        // chunk boundary — the keyword lands in chunk 5 but the actual
        // sentence finishes in chunk 6 (or the relevant numbers / table
        // sits one chunk later). Pulling in the immediate neighbor at
        // half-score is cheap insurance against missing the conclusion
        // of the matched passage.
        val docChunksByUri = contentChunks.groupBy { it.docUri }
            .mapValues { (_, list) -> list.sortedBy { it.chunkIndex } }

        val orderedIdsByDoc = docChunksByUri.mapValues { (_, list) -> list.map { it.id } }
        val usedIds = LinkedHashSet<Long>()
        val bm25Hits = mutableListOf<RetrievedChunk>()
        // Seed with the anchored section chunks so BM25 dedupes against them
        // and they lead the final result.
        for (e in anchoredEntities) {
            if (usedIds.add(e.id)) {
                bm25Hits.add(e.toRetrieved(HEADING_ANCHOR_SCORE))
            }
        }
        for ((rank, scored) in ranked.withIndex()) {
            val entity = contentChunks[scored.index]
            if (usedIds.add(entity.id)) {
                bm25Hits.add(entity.toRetrieved(scored.score))
            }
            // Only the top-2 hits get neighbor expansion — beyond that
            // BM25 itself is probably surfacing the relevant chunks.
            if (rank < 2) {
                val neighborId = nextSameDocNeighborId(entity.id, entity.docUri, orderedIdsByDoc)
                    ?: continue
                val neighbor = docChunksByUri[entity.docUri]?.firstOrNull { it.id == neighborId }
                    ?: continue
                if (usedIds.add(neighbor.id)) {
                    bm25Hits.add(neighbor.toRetrieved(scored.score * 0.5))
                }
            }
        }

        // If BM25 + neighbors fully populated the slot, still run per-doc
        // allocation so a large first file cannot occupy every top-K seat.
        // All-zero never comes from BM25.rank (zeros are dropped); finish()
        // still rebuilds if only padding/outline remains.
        if (bm25Hits.size >= topK) return done(finish(bm25Hits), RagSearchPath.bm25)

        // Zero-hit retry with prior query: if the current query alone had no
        // BM25 vocabulary match (e.g. "Also list meaning of each" without
        // prior-query expansion) but the prior question does have matches,
        // run a second BM25 pass on the prior query to surface the same
        // evidence region. Half-score marks these as context rather than
        // exact matches. Skipped when we already expanded above (isFollowUp).
        if (bm25Hits.isEmpty() && !priorQuery.isNullOrBlank() && !isFollowUp) {
            // R6: scope the prior-query retry to the docs relevant to this turn
            // (this-turn attaches, filename-named docs, most-recent file) rather
            // than the whole session, so a prior question can't surface an
            // unrelated older document. Falls back to the full pool if scoping
            // somehow empties it.
            val scope = retryDocScope(boostDocUris, route.namedDocUris, recencyUri)
            val retryPool = contentChunks.filter { it.docUri in scope }.ifEmpty { contentChunks }
            val retryRanked = rankContentChunks(retryPool, sessionId, priorQuery.take(150), rankK)
            for (scored in retryRanked) {
                val entity = retryPool[scored.index]
                if (usedIds.add(entity.id)) {
                    bm25Hits.add(entity.toRetrieved(scored.score * 0.5))
                }
            }
            if (bm25Hits.size >= topK) return done(finish(bm25Hits), RagSearchPath.bm25)
        }

        // No lexical body hit: per-file contiguous (or spaced) fallback
        // instead of first/middle/last scatter. finish() builds it.
        if (!hasPositiveBodyHit(bm25Hits)) return done(finish(emptyList()), RagSearchPath.structural)

        // BM25 (+ neighbors) under-covered the query. Pad remaining slots
        // with structural context. Real hits are kept; padding is extra.
        val padding = mutableListOf<RetrievedChunk>()

        // Outline (if extracted at index time) is the highest-value
        // padding item — gives the model a structural map of the doc.
        all.firstOrNull { it.chunkIndex == OUTLINE_CHUNK_INDEX }?.let { o ->
            padding.add(o.toRetrieved(0.0))
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
                padding.add(s.toRetrieved(0.0))
                usedIds.add(s.id)
                if (bm25Hits.size + padding.size >= topK) break
            }
            if (bm25Hits.size + padding.size >= topK) break
        }
        return done(finish(bm25Hits + padding), RagSearchPath.bm25)
    }

    /**
     * Evenly-spaced sample including first and last. Used to pad remaining
     * slots when BM25 already has at least one real hit.
     */
    private fun pickStructuralSamples(sorted: List<RagChunkEntity>, count: Int): List<RagChunkEntity> {
        if (sorted.size <= count) return sorted
        val indices = (0 until count).map { i ->
            ((i.toDouble() / (count - 1).coerceAtLeast(1)) * (sorted.size - 1)).toInt()
        }.distinct()
        return indices.map { sorted[it] }
    }

    /**
     * Outline + content samples per document. Used for meta/overview and
     * all-zero BM25 fallback. Never sorts chunkIndex across files.
     *
     * Tail queries (glossary, appendix) take the end. "Which file" takes
     * first/middle/last so each file is identifiable. Overviews and
     * zero-score questions take a contiguous opening excerpt.
     */
    private fun structuralSample(
        all: List<RagChunkEntity>,
        topK: Int,
        query: String = "",
        spaced: Boolean = false,
    ): List<RetrievedChunk> {
        val queryLower = query.lowercase()
        val isTailQuery = TAIL_STRUCTURE_TOKENS.any { queryLower.contains(it) }
        val mode = when {
            isTailQuery -> ZeroScorePick.TAIL
            spaced -> ZeroScorePick.SPACED
            else -> ZeroScorePick.CONTIGUOUS
        }
        val byDoc = all.groupBy { it.docUri }
        val perDoc = (topK / byDoc.size).coerceAtLeast(2)
        val result = mutableListOf<RetrievedChunk>()
        for ((_, docChunks) in byDoc) {
            docChunks.firstOrNull { it.chunkIndex == OUTLINE_CHUNK_INDEX }?.let { o ->
                result.add(o.toRetrieved(1.0))
            }
            val content = docChunks.filter { it.chunkIndex >= 0 }.sortedBy { it.chunkIndex }
            if (content.isEmpty()) continue
            val indices = pickZeroScoreBodyIndices(content.size, perDoc, mode)
            indices.forEach { i -> result.add(content[i].toRetrieved(0.0)) }
        }
        return result
    }

    /**
     * If [query] strongly matches a heading in a document outline, return
     * that section's leading chunks so they lead the result. When the heading
     * string is missing from the body (OCR / chunk split), still take a
     * contiguous window using the next outline heading as the end marker,
     * or token overlap. Empty → BM25 only.
     */
    private fun anchoredHeadingChunks(
        all: List<RagChunkEntity>,
        contentChunks: List<RagChunkEntity>,
        query: String,
    ): List<RagChunkEntity> {
        val outlinesByDoc = all.filter { it.chunkIndex == OUTLINE_CHUNK_INDEX }.associateBy { it.docUri }
        for ((uri, docChunks) in contentChunks.groupBy { it.docUri }) {
            val outline = outlinesByDoc[uri] ?: continue
            val headings = parseOutlineHeadings(outline.text)
            val heading = matchHeading(query, headings) ?: continue
            val sorted = docChunks.sortedBy { it.chunkIndex }
            val window = headingAnchorWindow(sorted.map { it.text }, heading, headings, HEADING_ANCHOR_MAX)
                ?: continue
            val section = sorted.subList(window.start, window.endExclusive)
            if (section.isEmpty()) continue
            val headingBit = if (ragLogDocNames()) " heading=${heading.take(40)}" else ""
            logRag("heading-anchored headingLen=${heading.length}$headingBit → ${section.size} chunk(s)")
            return section
        }
        return emptyList()
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
        invalidateTokenCache(sessionId)
        ragChunkDao.deleteBySession(sessionId)
    }

    /** Drop one file's chunks. No-op if it was never indexed. */
    suspend fun deleteByDoc(sessionId: String, docUri: String) {
        invalidateTokenCache(sessionId)
        ragChunkDao.deleteByDoc(sessionId, docUri)
    }

    // ── BM25 token cache (R1) ───────────────────────────────────────────────

    private fun rankContentChunks(
        chunks: List<RagChunkEntity>,
        sessionId: String,
        query: String,
        topK: Int,
    ): List<Bm25Retriever.Scored> {
        if (chunks.isEmpty()) return emptyList()
        val tokenised = chunks.map { cachedTokensFor(sessionId, it) }
        return Bm25Retriever.rankTokenised(tokenised, query, topK)
    }

    private fun cachedTokensFor(sessionId: String, chunk: RagChunkEntity): List<String> =
        chunkTokenCache.getOrPut(chunk.id) {
            cachedChunkIdsBySession
                .computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
                .add(chunk.id)
            Bm25Retriever.tokeniseDocument(chunk.text)
        }

    private fun invalidateTokenCache(sessionId: String) {
        cachedChunkIdsBySession.remove(sessionId)?.forEach { chunkTokenCache.remove(it) }
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
    /** Source document URI; empty for pack/synthetic chunks. */
    val docUri: String = "",
)

/** Distinct file indexed under a chat session, for the prompt manifest. */
data class SessionRagDocument(
    val uri: String,
    val name: String,
    val lastIndexedAt: Long,
)

private fun RagChunkEntity.toRetrieved(score: Double) = RetrievedChunk(
    text = text,
    docName = docName,
    score = score,
    chunkIndex = chunkIndex,
    docUri = docUri,
)

/**
 * R6 — documents a prior-query retry should be scoped to: this-turn attaches,
 * filename-named docs, and the most-recently-indexed file. Restricting the
 * zero-hit retry to these instead of the whole session stops a prior question
 * from dredging up an unrelated older document's chunks. recencyUri anchors it
 * so the set is never empty when the session has any document.
 */
internal fun retryDocScope(
    boostDocUris: Set<String>,
    namedDocUris: Set<String>,
    recencyUri: String,
): Set<String> = buildSet {
    addAll(boostDocUris)
    addAll(namedDocUris)
    if (recencyUri.isNotEmpty()) add(recencyUri)
}

/** This-turn attach — score bump only; other session files stay in the pool. */
internal const val THIS_TURN_BOOST = 1.35

/** After a restart (no this-turn URIs), mild bump for the most recently indexed file. */
internal const val RECENCY_BOOST = 1.15

internal fun contentStamp(sizeBytes: Long, textChars: Int): String = "$sizeBytes:$textChars"

/** Legacy rows have no stamp — leave them. Re-index only when a stamp exists and differs. */
internal fun shouldReplaceIndex(storedStamp: String?, newStamp: String): Boolean =
    storedStamp != null && storedStamp != newStamp

/** At least one excerpt per file so a large first doc cannot fill top-K alone. */
private const val MIN_SLOTS_PER_DOC = 1

/**
 * Post-rank boost: this-turn attaches (1.35), else named filename
 * matches (1.25), else recency when neither set (1.15). Does not drop hits.
 */
internal const val FILENAME_BOOST = 1.25

internal fun applySessionBoost(
    hits: List<RetrievedChunk>,
    boostDocUris: Set<String>,
    recencyUri: String,
    namedDocUris: Set<String> = emptySet(),
): List<RetrievedChunk> {
    if (hits.isEmpty()) return hits
    return hits.map { hit ->
        val multiplier = when {
            boostDocUris.isNotEmpty() && hit.docUri in boostDocUris -> THIS_TURN_BOOST
            namedDocUris.isNotEmpty() && hit.docUri in namedDocUris -> FILENAME_BOOST
            boostDocUris.isEmpty() && namedDocUris.isEmpty() && hit.docUri == recencyUri -> RECENCY_BOOST
            else -> 1.0
        }
        if (multiplier == 1.0) hit else hit.copy(score = hit.score * multiplier)
    }
}

/**
 * Fill [topK] so every document in [hits] gets at least [MIN_SLOTS_PER_DOC]
 * chunk (capped by topK), then remaining seats go to the highest scores.
 */
internal fun allocatePerDocSlots(
    hits: List<RetrievedChunk>,
    topK: Int,
    minPerDoc: Int = MIN_SLOTS_PER_DOC,
): List<RetrievedChunk> {
    if (hits.isEmpty() || topK <= 0) return emptyList()
    val sorted = hits.sortedByDescending { it.score }
    val byDoc = sorted.groupBy { it.docUri }
    if (byDoc.size <= 1) return sorted.take(topK)

    val min = minPerDoc.coerceAtLeast(1)
    val picked = LinkedHashSet<RetrievedChunk>()
    val docsByBest = byDoc.entries.sortedByDescending { (_, chunks) -> chunks.maxOf { it.score } }
    for ((_, chunks) in docsByBest) {
        if (picked.size >= topK) break
        repeat(min) { slot ->
            if (picked.size >= topK) return@repeat
            chunks.getOrNull(slot)?.let { picked.add(it) }
        }
    }
    for (h in sorted) {
        if (picked.size >= topK) break
        picked.add(h)
    }
    return picked.sortedByDescending { it.score }
}

/**
 * R7 / G7 — fair multi-file excerpt ordering for the prompt block.
 *
 * When a turn's retrieved set spans two or more documents, emit them
 * round-robin so EVERY file with a real (positive-score) hit contributes one
 * excerpt before any file contributes a second. This fixes the "only the first
 * attached file was answered" behaviour: previously the block emitted in pure
 * score order and a single high-scoring file could consume the whole char
 * budget, starving the others.
 *
 * Rules that keep this safe:
 *  • A single-document turn is returned unchanged (round-robin of one queue is
 *    a no-op) — the common follow-up / whole-small-file path is untouched.
 *  • Only positive-score hits are interleaved. Zero-score structural padding
 *    keeps its original order and trails at the end, so an off-topic file's
 *    filler can never displace another file's genuine hit.
 *  • Order WITHIN each document is preserved (already score / document order),
 *    so whole-small-file expansions stay in reading order.
 *
 * The overall char budget is still enforced by the caller; this only changes
 * the ORDER chunks are offered in, never which chunks exist.
 */
internal fun interleaveExcerptsByDoc(retrieved: List<RetrievedChunk>): List<RetrievedChunk> {
    if (retrieved.isEmpty()) return retrieved
    if (retrieved.map { it.docUri }.distinct().size <= 1) return retrieved

    val positive = retrieved.filter { it.score > 0.0 }
    val rest = retrieved.filter { it.score <= 0.0 }
    if (positive.isEmpty()) return retrieved

    val byDoc = LinkedHashMap<String, ArrayDeque<RetrievedChunk>>()
    for (c in positive) byDoc.getOrPut(c.docUri) { ArrayDeque() }.add(c)

    val interleaved = ArrayList<RetrievedChunk>(positive.size)
    var added = true
    while (added) {
        added = false
        for (queue in byDoc.values) {
            val next = queue.removeFirstOrNull() ?: continue
            interleaved.add(next)
            added = true
        }
    }
    return interleaved + rest
}

internal enum class ZeroScorePick { CONTIGUOUS, SPACED, TAIL }

internal fun hasPositiveBodyHit(hits: List<RetrievedChunk>): Boolean =
    hits.any { it.chunkIndex >= 0 && it.score > 0.0 }

/**
 * Body indices for one document. CONTIGUOUS = opening window (overviews /
 * all-zero BM25). SPACED = first/middle/last ("which file"). TAIL = last
 * window (glossary / appendix). Never crosses documents.
 */
internal fun pickZeroScoreBodyIndices(size: Int, count: Int, mode: ZeroScorePick): List<Int> {
    if (size <= 0 || count <= 0) return emptyList()
    if (size <= count) return (0 until size).toList()
    return when (mode) {
        ZeroScorePick.CONTIGUOUS -> (0 until count).toList()
        ZeroScorePick.TAIL -> ((size - count) until size).toList()
        ZeroScorePick.SPACED -> (0 until count).map { i ->
            ((i.toDouble() / (count - 1).coerceAtLeast(1)) * (size - 1)).toInt()
        }.distinct()
    }
}

internal fun keepOrFallback(
    hits: List<RetrievedChunk>,
    fallback: List<RetrievedChunk>,
): List<RetrievedChunk> = if (hasPositiveBodyHit(hits)) hits else fallback

/**
 * Replace BM25 scatter with every content chunk of a file whose body is
 * ≤ [wholeFileChars]. Larger files keep [retrieved] as ranked. Document
 * order is preserved; the prompt builder still drops whole chunks that
 * do not fit the remaining budget.
 */
internal fun expandWholeSmallFiles(
    retrieved: List<RetrievedChunk>,
    fullContentByUri: Map<String, List<RetrievedChunk>>,
    wholeFileChars: Int = FileContentExtractor.WHOLE_FILE_CHARS,
): List<RetrievedChunk> {
    if (retrieved.isEmpty() || wholeFileChars <= 0) return retrieved
    val docOrder = retrieved.map { it.docUri }.filter { it.isNotEmpty() }.distinct()
    val result = ArrayList<RetrievedChunk>()
    for (uri in docOrder) {
        val full = fullContentByUri[uri].orEmpty()
            .filter { it.chunkIndex >= 0 }
            .sortedBy { it.chunkIndex }
        val chars = full.sumOf { it.text.length }
        result += if (full.isNotEmpty() && chars <= wholeFileChars) full
            else retrieved.filter { it.docUri == uri }
    }
    result += retrieved.filter { it.docUri.isEmpty() }
    return result
}

internal data class HeadingWindow(val start: Int, val endExclusive: Int)

/** Next content chunk in the same document only — never another file. */
internal fun nextSameDocNeighborId(
    hitId: Long,
    hitDocUri: String,
    orderedIdsByDoc: Map<String, List<Long>>,
): Long? {
    if (hitDocUri.isEmpty()) return null
    val ordered = orderedIdsByDoc[hitDocUri] ?: return null
    val pos = ordered.indexOf(hitId)
    if (pos < 0) return null
    return ordered.getOrNull(pos + 1)
}

/**
 * Substring match on glued Devanagari morphology. Bare "सार" is omitted
 * because it appears inside ordinary words (संसार, प्रसार).
 */
internal val META_DEVANAGARI_PATTERN = Regex("(अनुक्रम|सारांश|विषयसूची|अवलोकन|संक्षेप)")

internal fun isDevanagariMetaTrigger(query: String): Boolean =
    META_DEVANAGARI_PATTERN.containsMatchIn(query)

/**
 * Overview / summary / table-of-contents triggers in the non-Devanagari Indic
 * scripts the app offers — Tamil, Telugu, Bengali, Kannada, Gujarati, Gurmukhi
 * (Punjabi) and Odia. Substring-matched like the Devanagari pattern; each term
 * is a distinctive multi-syllable "meta" word (summary/overview/contents/brief)
 * chosen to avoid matching inside ordinary words. Stems are used where a suffix
 * varies (e.g. Telugu సారాంశ matches సారాంశం / సారాంశము).
 */
internal val META_INDIC_PATTERN = Regex(
    "(" +
        // Tamil
        "சுருக்கம்|மேலோட்டம்|பொருளடக்கம்|சாராம்சம்|" +
        // Telugu
        "సారాంశ|అవలోకన|విషయసూచిక|సంక్షిప్త|" +
        // Bengali
        "সারাংশ|সংক্ষিপ্তসার|সূচিপত্র|সারসংক্ষেপ|" +
        // Kannada
        "ಸಾರಾಂಶ|ಅವಲೋಕನ|ಪರಿವಿಡಿ|ಸಂಕ್ಷಿಪ್ತ|" +
        // Gujarati
        "સારાંશ|અવલોકન|અનુક્રમણિકા|સંક્ષિપ્ત|" +
        // Gurmukhi (Punjabi)
        "ਸੰਖੇਪ|ਸਾਰਾਂਸ਼|ਤਤਕਰਾ|" +
        // Odia
        "ସାରାଂଶ|ସୂଚୀପତ୍ର|ସଂକ୍ଷିପ୍ତ|ଅବଲୋକନ" +
        ")",
)

internal fun isIndicMetaTrigger(query: String): Boolean =
    META_INDIC_PATTERN.containsMatchIn(query)

internal fun locateHeadingInChunks(chunkTexts: List<String>, heading: String): Int {
    val exact = chunkTexts.indexOfFirst { it.contains(heading, ignoreCase = true) }
    if (exact >= 0) return exact
    val tokens = headingTokens(heading).filter { it.length >= 4 }
    if (tokens.isEmpty()) return -1
    return chunkTexts.indexOfFirst { chunk ->
        val ct = headingTokens(chunk)
        tokens.all { it in ct }
    }
}

/**
 * Contiguous section window for an outline heading. If the heading text is
 * not in any body chunk, use the next outline heading as the end marker.
 */
internal fun headingAnchorWindow(
    chunkTexts: List<String>,
    heading: String,
    headingsInOrder: List<String>,
    maxChunks: Int,
): HeadingWindow? {
    if (chunkTexts.isEmpty() || maxChunks <= 0) return null
    val start = locateHeadingInChunks(chunkTexts, heading)
    if (start >= 0) {
        return HeadingWindow(start, minOf(start + maxChunks, chunkTexts.size))
    }
    val hi = headingsInOrder.indexOfFirst { it.equals(heading, ignoreCase = true) }
    val nextHeading = headingsInOrder.getOrNull(hi + 1) ?: return null
    val nextStart = locateHeadingInChunks(chunkTexts, nextHeading)
    if (nextStart <= 0) return null
    val from = (nextStart - maxChunks).coerceAtLeast(0)
    return HeadingWindow(from, nextStart)
}

/**
 * R6 — When every body chunk scores 0 (no lexical hit), replace scattered
 * padding with the first chunks in document order so overview-style queries
 * get a coherent opening excerpt.
 */
internal fun coherentExcerptForLowRelevance(
    retrieved: List<RetrievedChunk>,
    documentContent: List<RagChunkEntity> = emptyList(),
): List<RetrievedChunk> {
    if (retrieved.size <= 4) return retrieved
    val body = retrieved.filter { it.chunkIndex >= 0 }
    val anyRelevant = body.any { it.score > 0.0 }
    if (anyRelevant) return retrieved
    val outline = retrieved.filter { it.chunkIndex < 0 }
    val firstInOrder = if (documentContent.isNotEmpty()) {
        documentContent
            .sortedBy { it.chunkIndex }
            .take(4)
            .map { e -> RetrievedChunk(e.text, e.docName, 0.0, e.chunkIndex) }
    } else {
        body.sortedBy { it.chunkIndex }.take(4)
    }
    return outline + firstInOrder
}

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
