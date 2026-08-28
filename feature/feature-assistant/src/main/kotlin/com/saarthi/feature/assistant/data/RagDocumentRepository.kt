package com.saarthi.feature.assistant.data

import com.saarthi.core.common.sqliteWriteWithRetry
import com.saarthi.core.memory.db.RagChunkDao
import com.saarthi.core.memory.db.RagChunkEntity
import com.saarthi.core.memory.db.RagChunkFtsSearch
import com.saarthi.core.rag.Bm25Retriever
import com.saarthi.feature.assistant.domain.AttachedFile
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Score assigned to heading/topic/section anchored chunks in retrieval. */
internal const val ANCHORED_CHUNK_SCORE = 50.0

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
    private val ragChunkFtsSearch: RagChunkFtsSearch,
) {
    /** R1 — per-chunk token cache; invalidated when a session's chunks are deleted. */
    private val chunkTokenCache = ConcurrentHashMap<Long, List<String>>()
    private val cachedChunkIdsBySession = ConcurrentHashMap<String, MutableSet<Long>>()
    /** P3 — once a session trips the FTS5 gate, keep using the FTS prefilter. */
    private val ftsFastPathSessions = ConcurrentHashMap<String, Boolean>()
    /** T1-1 — working document for follow-up turns in a multi-file chat. */
    private val activeDocUriBySession = ConcurrentHashMap<String, String>()

    /** T1-1 — mark [docUri] as the user's current working attachment in this chat. */
    fun setActiveDocUri(sessionId: String, docUri: String) {
        if (docUri.isNotEmpty()) activeDocUriBySession[sessionId] = docUri
    }

    fun clearActiveDocUri(sessionId: String) {
        activeDocUriBySession.remove(sessionId)
    }

    /**
     * T1-1 — cached working doc, or the most recently indexed file when the
     * process restarted and the in-memory pointer was lost.
     */
    fun resolveActiveDocUri(sessionId: String, sessionDocs: List<SessionRagDocument>): String? {
        val cached = activeDocUriBySession[sessionId]
        if (cached != null && sessionDocs.any { it.uri == cached }) return cached
        return sessionDocs.maxByOrNull { it.lastIndexedAt }?.uri
    }

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
        private const val HEADING_ANCHOR_MAX = 5
        // T1-2 — more slots for outline + chapter/section marker chunks.
        private const val STRUCTURE_ANCHOR_MAX = 5
        // T1-3 — topic-named sections (appeal, termination, exemptions, …).
        private const val TOPIC_ANCHOR_MAX = 3
        // T1-4 — schedule / fee / tariff amount rows need more chunk slots.
        private const val TABULAR_ANCHOR_MAX = 4
        // Synthetic score for anchored chunks — above any realistic BM25
        // score so they sort first and survive topK truncation, and clearly
        // distinguishable in the debug log.
        private const val HEADING_ANCHOR_SCORE = ANCHORED_CHUNK_SCORE

        // Sentinel chunkIndex for an auto-extracted document outline —
        // headings scraped during indexing and stored as a single virtual
        // chunk that meta-queries ("what sections are there?", "summarise
        // this") rank first. Sits in the SAME table to avoid a schema
        // bump; the < 0 index is the only thing that distinguishes it
        // from a regular content chunk.
        private const val OUTLINE_CHUNK_INDEX = -1
        /** Wave 4 P20 — user-visible truncation notice for capped PDF indexing. */
        private const val INDEX_TRUNCATION_NOTICE_CHUNK_INDEX = INDEX_TRUNCATION_CHUNK_INDEX

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
            "content chi overview", "document content chi",
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
            invalidateTokenCache(sessionId)
        }

        val sessionRows = sqliteWriteWithRetry { ragChunkDao.getBySession(sessionId) }
        val fingerprints = sessionRows
            .filter { it.chunkIndex == FINGERPRINT_CHUNK_INDEX }
            .map { it.docUri to it.text }
        val aliasOf = existingUriWithStamp(fingerprints, uriKey, stamp)
        if (aliasOf != null) {
            sqliteWriteWithRetry {
                ragChunkDao.insertAll(
                    listOf(
                        RagChunkEntity(
                            sessionId = sessionId,
                            docUri = uriKey,
                            docName = file.name,
                            mimeType = file.mimeType,
                            chunkIndex = FINGERPRINT_CHUNK_INDEX,
                            text = stamp,
                        ),
                    ),
                )
            }
            logRag("index-alias nameLen=${file.name.length} sessionIdLen=${sessionId.length}")
            setActiveDocUri(sessionId, uriKey)
            return
        }

        val evict = urisToEvictForSessionCap(sessionDocUsages(sessionRows), uriKey, text.length)
        if (evict.isNotEmpty()) {
            invalidateTokenCache(sessionId)
            for (u in evict) {
                sqliteWriteWithRetry { ragChunkDao.deleteByDoc(sessionId, u) }
            }
            logRag("index-evict count=${evict.size} sessionIdLen=${sessionId.length}")
        }

        val indexedChunks = chunkText(text, file.mimeType)
        if (indexedChunks.isEmpty()) return

        val chunks = indexedChunks.map { it.text }

        var outlineText: String? = null
        extractOutline(text)?.let { outlineBody ->
            outlineText = buildString {
                extractDocumentTitle(text)?.let { title ->
                    append(title)
                    append('\n')
                }
                append(outlineBody)
            }.trimEnd()
        }
        val chapterRegistry = buildDocumentChapterRegistry(chunks, outlineText)
        val metadata = computeChunkMetadata(chunks, chapterRegistry)

        val entities = ArrayList<RagChunkEntity>(chunks.size + 3)
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

        if (outlineText != null) {
            entities.add(
                RagChunkEntity(
                    sessionId = sessionId,
                    docUri = uriKey,
                    docName = file.name,
                    mimeType = file.mimeType,
                    chunkIndex = OUTLINE_CHUNK_INDEX,
                    text = outlineText!!,
                    chunkRole = ChunkRole.OUTLINE,
                ),
            )
        }

        if (chapterRegistry.chapters.isNotEmpty()) {
            entities.add(
                RagChunkEntity(
                    sessionId = sessionId,
                    docUri = uriKey,
                    docName = file.name,
                    mimeType = file.mimeType,
                    chunkIndex = STRUCTURE_REGISTRY_CHUNK_INDEX,
                    text = encodeChapterRegistry(chapterRegistry),
                    chunkRole = ChunkRole.REGISTRY,
                ),
            )
        }

        file.indexTruncationNotice?.trim()?.takeIf { it.isNotEmpty() }?.let { notice ->
            entities.add(
                RagChunkEntity(
                    sessionId = sessionId,
                    docUri = uriKey,
                    docName = file.name,
                    mimeType = file.mimeType,
                    chunkIndex = INDEX_TRUNCATION_NOTICE_CHUNK_INDEX,
                    text = notice,
                ),
            )
        }

        indexedChunks.forEachIndexed { idx, indexed ->
            val meta = metadata[idx]
            entities.add(
                RagChunkEntity(
                    sessionId = sessionId,
                    docUri = uriKey,
                    docName = file.name,
                    mimeType = file.mimeType,
                    chunkIndex = idx,
                    text = indexed.text,
                    chapterId = meta.chapterId,
                    sectionNum = meta.sectionNum,
                    headingPath = meta.headingPath,
                    pageNum = meta.pageNum,
                    chunkRole = meta.role,
                    parentChunkIndex = indexed.parentChunkIndex,
                ),
            )
        }
        sqliteWriteWithRetry { ragChunkDao.insertAll(entities) }
        setActiveDocUri(sessionId, uriKey)
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
            .filter { (_, chunks) -> chunks.any { it.chunkIndex >= 0 } }
            .map { (uri, chunks) ->
                val newest = chunks.maxBy { it.createdAt }
                val truncation = chunks.firstOrNull {
                    it.chunkIndex == INDEX_TRUNCATION_CHUNK_INDEX
                }?.text
                SessionRagDocument(
                    uri = uri,
                    name = newest.docName,
                    lastIndexedAt = newest.createdAt,
                    indexTruncationNotice = truncation,
                )
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
         * session is excluded from the corpus for this turn. Used for
         * attach-turn scoping (G1), named-file queries, and T1-1 active-document
         * follow-ups in multi-file chats. If the restricted set has no indexed
         * chunks, search returns empty — no fallback to the full corpus.
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
         * [wholeFileChars] are returned in full document order instead of
         * a BM25 subset. Pack search leaves this false so Kisan top-K is
         * unchanged.
         */
        expandSmallFiles: Boolean = true,
        /**
         * Whole-file expansion cap. LARGE-tier prompts use a higher budget
         * so a 4–5k note still arrives intact; COMPACT stays tighter.
         */
        wholeFileChars: Int = FileContentExtractor.WHOLE_FILE_CHARS,
        /** T1-1 — logged as scope=… in the RAG search line (lowercase enum name). */
        retrievalScopeLabel: String? = null,
    ): List<RetrievedChunk> {
        val t0 = System.nanoTime()
        val sessionRows = sqliteWriteWithRetry { ragChunkDao.getBySession(sessionId) }
        // Attach-turn scoping (G1): restrict the corpus to the just-attached
        // files so their overview/summary is not answered from a mix of the
        // earlier documents' excerpts. Same-stamp aliases (re-shared URI)
        // expand the restrict set so the original content chunks are found.
        // Fingerprint rows are dropped so alias-only URIs cannot inflate
        // per-doc slot counts. No fall-back to the full corpus on an empty
        // result — see restrictDocUris kdoc.
        val fingerprints = sessionRows
            .filter { it.chunkIndex == FINGERPRINT_CHUNK_INDEX }
            .map { it.docUri to it.text }
        val expandedRestrict = expandRestrictUrisByStamp(fingerprints, restrictDocUris)
        val scoped = if (expandedRestrict.isNotEmpty()) {
            sessionRows.filter { it.docUri in expandedRestrict }
        } else {
            sessionRows
        }
        val all = scoped.filter { it.chunkIndex != FINGERPRINT_CHUNK_INDEX }
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
                    retrievalScope = retrievalScopeLabel,
                    restrictCount = expandedRestrict.size,
                ),
            )
            return emptyList()
        }
        val recencyUri = all.maxBy { it.createdAt }.docUri
        val sessionFiles = all.groupBy { it.docUri }.map { (uri, chunks) ->
            uri to chunks.first().docName
        }
        val route = routeQuery(query, sessionFiles)
        val chapterRegistries = chapterRegistriesFromEntities(all)
        val spanPreserving = isSpanPreservingQuery(query)
        val effectiveTopK = if (spanPreserving) maxOf(topK, SPAN_PRESERVING_TOP_K) else topK
        val anchorWindow = anchorWindowMax(query)
        var headingChunkCount = 0
        var ftsPrefilterUsed = false
        fun finish(hits: List<RetrievedChunk>): List<RetrievedChunk> {
            // All-zero / overview: rebuild per file (outline + contiguous
            // opening, or spaced samples for "which file"). Real BM25 hits
            // (body score > 0) are left as ranked.
            val resolved = if (hasPositiveBodyHit(hits)) hits
                else structuralSample(all, effectiveTopK, query, spaced = route.whichFile)
            val docCount = resolved.map { it.docUri }.filter { it.isNotEmpty() }.distinct().size.coerceAtLeast(1)
            val minSlots = if (route.equalSlots) (effectiveTopK / docCount).coerceAtLeast(1) else 1
            val contentEntities = all.filter { it.chunkIndex >= 0 }
            val allocated = allocatePerDocSlots(
                applySessionBoost(resolved, boostDocUris, recencyUri, route.namedDocUris),
                effectiveTopK,
                minSlots,
            )
            val condensed = collapseRedundantChunkRuns(
                allocated,
                preserveAnchoredSpans = spanPreserving,
                anchoredSpanMax = ANCHORED_SPAN_COLLAPSE_MAX,
            )
            val excerpted = coherentExcerptForLowRelevance(condensed, contentEntities)
            if (!expandSmallFiles) {
                return applyChapterRetrievalConfidence(
                    query = query,
                    retrieved = excerpted,
                    contentChunks = contentEntities,
                    expandedSpanChunks = ANCHORED_SPAN_COLLAPSE_MAX,
                )
            }
            val fullByUri = contentEntities
                .groupBy { it.docUri }
                .mapValues { (_, chunks) ->
                    chunks.sortedBy { it.chunkIndex }.map { it.toRetrieved(1.0) }
                }
            return coherentExcerptForLowRelevance(
                expandWholeSmallFiles(excerpted, fullByUri, wholeFileChars),
                contentEntities,
            ).let { expanded ->
                applyChapterRetrievalConfidence(
                    query = query,
                    retrieved = expanded,
                    contentChunks = contentEntities,
                    expandedSpanChunks = ANCHORED_SPAN_COLLAPSE_MAX,
                )
            }
        }

        // Follow-up detection: if the query STARTS with a continuation
        // token AND we have context from the prior turn, bypass meta-routing
        // and use BM25 on the combined query. This handles "also list meaning
        // of each mentioned" continuing "meaning of terms associated with
        // hazards" — the combined BM25 query surfaces the same hazard chunks
        // rather than a generic structural sample.
        val queryTokens = query.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
        val isFollowUp = !priorQuery.isNullOrBlank() && queryTokens.take(4).any { it in FOLLOW_UP_TOKENS }
        val metaReason = effectiveMetaRouteReason(query, isFollowUp)
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
                    ftsPrefilter = ftsPrefilterUsed,
                    retrievalScope = retrievalScopeLabel,
                    restrictCount = expandedRestrict.size,
                ),
            )
            val chunkCount = all.count { it.chunkIndex >= 0 }
            if (fts5IsWarranted(chunkCount, searchMs)) {
                ftsFastPathSessions[sessionId] = true
                logRag(ragFts5CandidateLogLine(chunkCount, searchMs))
            }
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
        val chapterSpanQuery = isChapterSpanQuery(query)
        val chapterSpanChunks = if (chapterSpanQuery) {
            resolveChapterSpanChunks(contentChunks, query, SPAN_ANCHOR_WINDOW)
        } else {
            emptyList()
        }
        val sectionAnchored = if (!chapterSpanQuery || chapterSpanChunks.isEmpty()) {
            anchoredSectionChunks(contentChunks, query, anchorWindow)
        } else {
            emptyList()
        }
        val penaltyPreferDocUri = if (isSectionPenaltyComboQuery(query)) {
            (chapterSpanChunks + sectionAnchored).firstOrNull()?.docUri
        } else {
            null
        }
        val structureCountHint = buildStructureCountHint(
            query,
            contentChunks,
            all.filter { it.chunkIndex == OUTLINE_CHUNK_INDEX }.map { it.text },
            chapterRegistries,
        )
        val structureListHint = buildStructureListHint(query, chapterRegistries)
        val structureHint = structureListHint ?: structureCountHint
        val tabularMax = if (spanPreserving) SPAN_ANCHOR_WINDOW else TABULAR_ANCHOR_MAX
        val anchoredEntities = chapterSpanChunks +
            (if (chapterSpanChunks.isEmpty()) {
                anchoredHeadingChunks(all, contentChunks, query, anchorWindow) +
                    sectionAnchored +
                    anchoredBodyChapterChunks(contentChunks, query, anchorWindow)
            } else {
                emptyList()
            }) +
            anchoredTopicChunks(contentChunks, query, anchorWindow) +
            anchoredTabularChunks(contentChunks, query, preferDocUri = penaltyPreferDocUri, maxChunks = tabularMax) +
            anchoredStructureListChunks(all, contentChunks, query, chapterRegistries) +
            if (requiresTabularContract(query)) {
                tabularContractChunkEntities(contentChunks, penaltyPreferDocUri)
            } else {
                emptyList()
            }
        headingChunkCount = anchoredEntities.size

        // Expand the query when following up on the prior turn.
        var effectiveQuery = if (isFollowUp && !priorQuery.isNullOrBlank()) {
            "${priorQuery.take(150)} ${route.expandedQuery}"
        } else {
            route.expandedQuery
        }
        if (isStructureListQuery(query)) {
            effectiveQuery += when (structureMarkerKind(query)) {
                "section" -> " section sections धारा"
                "part" -> " part parts"
                "heading" -> " heading headings"
                "annex" -> " annex appendix annexure"
                else -> " CHAPTER Chapter chapter अध्याय"
            }
        }
        val topicExpansion = topicAnchorQueryExpansion(query)
        if (topicExpansion.isNotEmpty()) {
            effectiveQuery += topicExpansion
        }
        if (isTabularAmountQuery(query)) {
            effectiveQuery += tabularAmountQueryExpansion()
        }

        val uniqueDocs = contentChunks.map { it.docUri }.distinct().size.coerceAtLeast(1)
        val candidateK = featureRerankCandidatePoolSize(effectiveTopK, uniqueDocs, contentChunks.size)
        val rerankCtx = buildFeatureRerankContext(query)
        var rankPool = contentChunks
        if (shouldUseFtsPrefilter(contentChunks.size, ftsFastPathSessions[sessionId] == true)) {
            val match = buildFtsMatchQuery(effectiveQuery)
            if (match != null) {
                val limit = (candidateK * FTS5_CANDIDATE_MULTIPLIER)
                    .coerceAtMost(contentChunks.size)
                    .coerceAtLeast(candidateK)
                val ftsHits = runCatching {
                    sqliteWriteWithRetry {
                        ragChunkFtsSearch.searchContent(sessionId, match, limit)
                    }
                }.getOrDefault(emptyList())
                if (ftsHits.isNotEmpty()) {
                    val idSet = ftsHits.map { it.id }.toSet()
                    val filtered = contentChunks.filter { it.id in idSet }
                    if (filtered.size >= candidateK.coerceAtMost(4)) {
                        rankPool = filtered
                        ftsPrefilterUsed = true
                    }
                }
            }
        }
        val bm25Candidates = rankContentChunks(rankPool, sessionId, effectiveQuery, candidateK)
        var ranked = filterRankedByScoreGap(
            featureRerankBm25Candidates(bm25Candidates, rankPool, query, rerankCtx),
            effectiveTopK,
        )
        val paraphraseExpansion = paraphraseQueryExpansion(query)
        if (shouldRunParaphraseRetrievalRetry(
                topOrganicScore = ranked.firstOrNull()?.score ?: 0.0,
                hasAnchoredHits = anchoredEntities.isNotEmpty(),
                paraphraseExpansion = paraphraseExpansion,
            )
        ) {
            val paraphraseQuery = "$effectiveQuery $paraphraseExpansion"
            val retryCandidates = rankContentChunks(rankPool, sessionId, paraphraseQuery, candidateK)
            val paraphraseRanked = filterRankedByScoreGap(
                featureRerankBm25Candidates(retryCandidates, rankPool, query, rerankCtx),
                effectiveTopK,
            )
            ranked = mergeRankedBm25Results(ranked, paraphraseRanked, effectiveTopK)
            logRag(
                "paraphrase-retry rules=${activeParaphraseRuleIds(query).joinToString()} " +
                    "top=${paraphraseRanked.firstOrNull()?.score ?: 0.0}",
            )
        }

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
        val sectionGroupsByDoc = buildSectionGroupsByDoc(contentChunks)
        val usedIds = LinkedHashSet<Long>()
        val bm25Hits = mutableListOf<RetrievedChunk>()
        if (structureHint != null && contentChunks.isNotEmpty()) {
            val doc = contentChunks.first()
            bm25Hits.add(
                RetrievedChunk(
                    text = structureHint,
                    docName = doc.docName,
                    score = 100.0,
                    chunkIndex = -2,
                    docUri = doc.docUri,
                ),
            )
        }
        // Seed with the anchored section chunks so BM25 dedupes against them
        // and they lead the final result.
        for (e in anchoredEntities) {
            if (usedIds.add(e.id)) {
                bm25Hits.add(e.toRetrieved(HEADING_ANCHOR_SCORE))
            }
        }
        for ((rank, scored) in ranked.withIndex()) {
            val entity = rankPool[scored.index]
            if (usedIds.add(entity.id)) {
                bm25Hits.add(entity.toRetrieved(scored.score))
            }
        }
        for ((entity, score) in expandRerankedNeighborHits(
            ranked = ranked,
            pool = rankPool,
            docChunksByUri = docChunksByUri,
            orderedIdsByDoc = orderedIdsByDoc,
        )) {
            if (usedIds.add(entity.id)) {
                bm25Hits.add(entity.toRetrieved(score))
            }
        }
        for ((entity, score) in expandHierarchicalSectionHits(
            ranked = ranked,
            pool = rankPool,
            sectionGroupsByDoc = sectionGroupsByDoc,
        )) {
            if (usedIds.add(entity.id)) {
                bm25Hits.add(entity.toRetrieved(score))
            }
        }

        // If BM25 + neighbors fully populated the slot, still run per-doc
        // allocation so a large first file cannot occupy every top-K seat.
        // All-zero never comes from BM25.rank (zeros are dropped); finish()
        // still rebuilds if only padding/outline remains.
        if (bm25Hits.size >= effectiveTopK) return done(finish(bm25Hits), RagSearchPath.bm25)

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
            val retryCandidates = rankContentChunks(retryPool, sessionId, priorQuery.take(150), candidateK)
            val retryRanked = filterRankedByScoreGap(
                featureRerankBm25Candidates(retryCandidates, retryPool, priorQuery, rerankCtx),
                effectiveTopK,
            )
            for (scored in retryRanked) {
                val entity = retryPool[scored.index]
                if (usedIds.add(entity.id)) {
                    bm25Hits.add(entity.toRetrieved(scored.score * 0.5))
                }
            }
            val retryDocChunksByUri = retryPool.groupBy { it.docUri }
                .mapValues { (_, list) -> list.sortedBy { it.chunkIndex } }
            val retryOrderedIds = retryDocChunksByUri.mapValues { (_, list) -> list.map { it.id } }
            for ((entity, score) in expandRerankedNeighborHits(
                ranked = retryRanked,
                pool = retryPool,
                docChunksByUri = retryDocChunksByUri,
                orderedIdsByDoc = retryOrderedIds,
                expansionScore = RERANK_EXPANSION_SCORE * 0.5,
            )) {
                if (usedIds.add(entity.id)) {
                    bm25Hits.add(entity.toRetrieved(score))
                }
            }
            if (bm25Hits.size >= effectiveTopK) return done(finish(bm25Hits), RagSearchPath.bm25)
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
            val perDoc = ((effectiveTopK - bm25Hits.size - padding.size + docCount - 1) / docCount).coerceAtLeast(2)
            for (s in pickStructuralSamples(sorted, perDoc)) {
                if (s.id in usedIds) continue
                padding.add(s.toRetrieved(0.0))
                usedIds.add(s.id)
                if (bm25Hits.size + padding.size >= effectiveTopK) break
            }
            if (bm25Hits.size + padding.size >= effectiveTopK) break
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
        windowMax: Int = HEADING_ANCHOR_MAX,
    ): List<RagChunkEntity> {
        val outlinesByDoc = all.filter { it.chunkIndex == OUTLINE_CHUNK_INDEX }.associateBy { it.docUri }
        for ((uri, docChunks) in contentChunks.groupBy { it.docUri }) {
            val sorted = docChunks.sortedBy { it.chunkIndex }
            val outline = outlinesByDoc[uri]
            val outlineHeadings = outline?.let { parseOutlineHeadings(it.text) } ?: emptyList()
            val bodyHeadings = extractBodyHeadingLines(sorted)
            val headings = (outlineHeadings + bodyHeadings).distinct()
            val heading = matchHeading(query, headings) ?: continue
            val substanceOnly = !usesTocForRetrieval(query)
            val window = headingAnchorWindowInBody(sorted, heading, headings, windowMax, substanceOnly)
                ?: locateHeadingLineWindow(sorted, heading, windowMax, substanceOnly)
                ?: continue
            val section = sorted.subList(window.start, window.endExclusive)
            if (section.isEmpty()) continue
            val headingBit = if (ragLogDocNames()) " heading=${heading.take(40)}" else ""
            logRag("heading-anchored headingLen=${heading.length}$headingBit → ${section.size} chunk(s)")
            return section
        }
        return emptyList()
    }

    /** Gazette-style chapter titles that the auto-outline may miss. */
    private fun extractBodyHeadingLines(chunks: List<RagChunkEntity>): List<String> {
        val lines = mutableListOf<String>()
        for (chunk in chunks) {
            for (raw in chunk.text.lines()) {
                val line = raw.trim()
                if (line.length !in 8..120) continue
                if (CHAPTER_MARKER_RX.containsMatchIn(line) ||
                    Regex("(?i)^(SPECIAL PROVISIONS|PENALTIES AND|APPEAL AND)").containsMatchIn(line)
                ) {
                    lines.add(line)
                }
            }
        }
        return lines
    }

    /** Window around the chunk that contains [heading] as a line. */
    private fun locateHeadingLineWindow(
        sorted: List<RagChunkEntity>,
        heading: String,
        maxChunks: Int,
        substanceOnly: Boolean = true,
    ): HeadingWindow? {
        val needle = heading.trim()
        if (needle.isEmpty()) return null
        val idx = locateHeadingInBodyChunks(sorted, heading, substanceOnly)
        if (idx < 0) return null
        val from = (idx - 1).coerceAtLeast(0)
        val to = minOf(from + maxChunks, sorted.size)
        return HeadingWindow(from, to)
    }

    /**
     * When the query names a numbered section/chapter/schedule (not necessarily
     * an outline heading), surface the matching body window first.
     */
    private fun anchoredSectionChunks(
        contentChunks: List<RagChunkEntity>,
        query: String,
        windowMax: Int = HEADING_ANCHOR_MAX,
    ): List<RagChunkEntity> {
        val refs = extractSectionRefs(query)
        if (refs.isEmpty()) return emptyList()
        val substanceOnly = !usesTocForRetrieval(query)
        for ((_, docChunks) in contentChunks.groupBy { it.docUri }) {
            val sorted = docChunks.sortedBy { it.chunkIndex }
            for (ref in refs) {
                val idx = locateSectionInBodyChunks(sorted, ref, substanceOnly)
                if (idx < 0) continue
                val from = (idx - 1).coerceAtLeast(0)
                val to = minOf(from + windowMax, sorted.size)
                val section = sorted.subList(from, to)
                if (section.isEmpty()) continue
                logRag(
                    "section-anchored kind=${ref.kind} refLen=${ref.token.length} → ${section.size} chunk(s)",
                )
                return section
            }
        }
        return emptyList()
    }

    /**
     * T1-3 — when the query names a topic (appeal, termination, exemption, …)
     * but outline heading match missed, surface body chunks carrying that topic.
     */
    private fun anchoredTopicChunks(
        contentChunks: List<RagChunkEntity>,
        query: String,
        windowMax: Int = HEADING_ANCHOR_MAX,
    ): List<RagChunkEntity> {
        if (isStructureListQuery(query)) return emptyList()
        val categories = activeTopicCategories(query)
        if (categories.isEmpty()) return emptyList()
        val patterns = categories.flatMap { it.bodyPatterns }
        val topicPickMax = minOf(TOPIC_ANCHOR_MAX, windowMax)
        for ((_, docChunks) in contentChunks.groupBy { it.docUri }) {
            val sorted = docChunks.sortedBy { it.chunkIndex }
            val picks = pickTopicAnchorChunkEntities(sorted, query, topicPickMax)
            if (picks.isEmpty()) continue
            val anchorChunk = picks.minByOrNull { topicAnchorLineStartTier(it.text, patterns) }
                ?: picks.first()
            val firstIdx = sorted.indexOfFirst { it.id == anchorChunk.id }
            if (firstIdx < 0) continue
            val from = (firstIdx - 1).coerceAtLeast(0)
            val to = minOf(from + windowMax + 1, sorted.size)
            val section = sorted.subList(from, to)
            if (section.isEmpty()) continue
            logRag(
                "topic-anchored categories=${categories.map { it.id }.joinToString(",")} → ${section.size} chunk(s)",
            )
            return section
        }
        return emptyList()
    }

    /**
     * When the auto-outline missed a chapter title (common on Gazette PDFs),
     * scan body lines for chapter/section titles that overlap the query and
     * surface that window before tabular/topic scatter.
     */
    private fun anchoredBodyChapterChunks(
        contentChunks: List<RagChunkEntity>,
        query: String,
        windowMax: Int = HEADING_ANCHOR_MAX,
    ): List<RagChunkEntity> {
        if (isStructureListQuery(query)) return emptyList()
        val substanceOnly = !usesTocForRetrieval(query)
        var bestSection: List<RagChunkEntity>? = null
        var bestScore = 0.0
        for ((_, docChunks) in contentChunks.groupBy { it.docUri }) {
            val sorted = docChunks.sortedBy { it.chunkIndex }
            for ((idx, chunk) in sorted.withIndex()) {
                if (substanceOnly && isTocLikeChunk(chunk)) continue
                val lineScore = chunk.text.lines().mapNotNull { raw ->
                    val line = raw.trim()
                    if (line.length !in 8..120) return@mapNotNull null
                    val isTitleLine = chapterHeaderMatchTier(line) != null ||
                        BODY_CHAPTER_TITLE_RX.containsMatchIn(line)
                    if (!isTitleLine) return@mapNotNull null
                    chapterTitleLineScore(query, line).takeIf { it > 0 }
                }.maxOrNull() ?: 0.0
                if (lineScore > bestScore) {
                    bestScore = lineScore
                    val from = (idx - 1).coerceAtLeast(0)
                    val to = minOf(from + windowMax, sorted.size)
                    bestSection = sorted.subList(from, to)
                }
            }
        }
        if (bestSection != null && bestScore >= 0.45) {
            logRag("body-chapter-anchored score=${"%.2f".format(bestScore)} → ${bestSection.size} chunk(s)")
            return bestSection
        }
        return emptyList()
    }

    /**
     * T1-4 — pull Schedule / fee-table / monetary-penalty chunks so BM25 scatter
     * does not hide amount rows. B2-1 — [preferDocUri] pins section+amount combo.
     */
    private fun anchoredTabularChunks(
        contentChunks: List<RagChunkEntity>,
        query: String,
        preferDocUri: String? = null,
        maxChunks: Int = TABULAR_ANCHOR_MAX,
    ): List<RagChunkEntity> {
        if (!isTabularAmountQuery(query)) return emptyList()
        val picked = pickTabularAmountChunkEntities(contentChunks, preferDocUri, maxChunks)
            .filter { !isTabularWeakFragment(it.text) }
            .ifEmpty {
                pickTabularAmountChunkEntities(contentChunks, preferDocUri, maxChunks)
            }
        if (picked.isNotEmpty()) {
            logRag("tabular-anchored → ${picked.size} chunk(s)")
        }
        return picked
    }

    /**
     * T1-2 — structure count/list: inject outline chunk(s) for scoped docs plus
     * body chunks that carry CHAPTER/Section/अध्याय markers so BM25 scatter
     * does not hide the table of contents.
     */
    private fun anchoredStructureListChunks(
        all: List<RagChunkEntity>,
        contentChunks: List<RagChunkEntity>,
        query: String,
        chapterRegistries: Map<String, DocumentChapterRegistry> = emptyMap(),
    ): List<RagChunkEntity> {
        if (!isStructureListQuery(query)) return emptyList()
        val docUris = contentChunks.map { it.docUri }.distinct()
        if (structureMarkerKind(query) == "chapter" && chapterRegistries.isNotEmpty()) {
            val result = mutableListOf<RagChunkEntity>()
            for (uri in docUris) {
                val registry = chapterRegistries[uri] ?: continue
                for (entry in registry.sortedByDocumentOrder().take(STRUCTURE_ANCHOR_MAX)) {
                    if (entry.startChunkIndex < 0) continue
                    val chunk = contentChunks.firstOrNull {
                        it.docUri == uri && it.chunkIndex == entry.startChunkIndex
                    }
                    if (chunk != null && result.none { it.id == chunk.id }) {
                        result.add(chunk)
                    }
                }
            }
            if (result.isNotEmpty()) {
                logRag("structure-list-registry → ${result.size} chunk(s)")
                return result.take(STRUCTURE_ANCHOR_MAX + docUris.size)
            }
        }
        val outlinesByDoc = all.filter { it.chunkIndex == OUTLINE_CHUNK_INDEX }.associateBy { it.docUri }
        val result = mutableListOf<RagChunkEntity>()
        for (uri in docUris) {
            outlinesByDoc[uri]?.let { result.add(it) }
        }
        val markerMax = (STRUCTURE_ANCHOR_MAX - result.size).coerceAtLeast(2)
        val markers = pickStructureMarkerChunkEntities(contentChunks, query, markerMax)
        for (chunk in markers) {
            if (result.none { it.id == chunk.id }) result.add(chunk)
        }
        if (result.isNotEmpty()) {
            logRag("structure-list-anchored → ${result.size} chunk(s)")
        }
        return result.take(STRUCTURE_ANCHOR_MAX + docUris.size)
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
    private fun extractOutline(text: String, maxHeadings: Int = 40): String? {
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
        val nextBlank = idx + 1 < lines.size && lines[idx + 1].trim().isBlank()
        return isLikelyHeadingLine(line, nextBlank)
    }

    /** Distinct documents indexed under [sessionId] — for the "is this chat RAG-augmented?" gate. */
    suspend fun hasIndexedDocs(sessionId: String): Boolean =
        ragChunkDao.listDocUris(sessionId).isNotEmpty()

    /** Wipe all indexed chunks for [sessionId]. Called on session-delete and clear-history. */
    suspend fun deleteForSession(sessionId: String) {
        invalidateTokenCache(sessionId)
        clearActiveDocUri(sessionId)
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

    private fun cachedTokensFor(sessionId: String, chunk: RagChunkEntity): List<String> {
        val tokens = chunkTokenCache.getOrPut(chunk.id) {
            cachedChunkIdsBySession
                .computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
                .add(chunk.id)
            Bm25Retriever.tokeniseDocument(chunk.text)
        }
        trimTokenCache(keepSession = sessionId)
        return tokens
    }

    private fun trimTokenCache(keepSession: String) {
        if (chunkTokenCache.size <= TOKEN_CACHE_MAX_CHUNKS) return
        val counts = cachedChunkIdsBySession.mapValues { it.value.size }
        for (sid in sessionsToEvictForTokenCache(counts, keepSession, chunkTokenCache.size)) {
            invalidateTokenCache(sid)
        }
    }

    private fun invalidateTokenCache(sessionId: String) {
        cachedChunkIdsBySession.remove(sessionId)?.forEach { chunkTokenCache.remove(it) }
        ftsFastPathSessions.remove(sessionId)
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
    private fun chunkText(text: String, mimeType: String = ""): List<IndexedChunkText> =
        chunkDocumentForIndexing(text, mimeType)
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
    /** Wave 4 P20 — honest cap when PDF pages/chars were not fully indexed. */
    val indexTruncationNotice: String? = null,
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

/** sizeBytes:textChars stamp so a changed re-attach can replace stale chunks. */
internal const val FINGERPRINT_CHUNK_INDEX = -2

/** Device-side cap even for Pro — BM25 + token cache stay bounded on-phone. */
internal const val MAX_SESSION_RAG_DOCS = 12

/** ~2.5× the per-file extract cap, so a handful of large PDFs still fit. */
internal const val MAX_SESSION_INDEX_CHARS = 250_000

/** Drop idle sessions' tokenised chunks before the active chat is evicted. */
internal const val TOKEN_CACHE_MAX_CHUNKS = 800

internal data class SessionDocUsage(
    val uri: String,
    val contentChars: Int,
    val firstIndexedAt: Long,
)

internal fun sessionDocUsages(rows: List<RagChunkEntity>): List<SessionDocUsage> =
    rows.groupBy { it.docUri }
        .map { (uri, chunks) ->
            SessionDocUsage(
                uri = uri,
                contentChars = chunks.filter { it.chunkIndex >= 0 }.sumOf { it.text.length },
                firstIndexedAt = chunks.minOf { it.createdAt },
            )
        }
        .filter { it.contentChars > 0 }
        .sortedBy { it.firstIndexedAt }

/**
 * Oldest content docs to drop so [incomingUri] can be indexed without
 * exceeding [maxDocs] / [maxChars]. Never evicts the incoming URI.
 */
internal fun urisToEvictForSessionCap(
    existing: List<SessionDocUsage>,
    incomingUri: String,
    incomingChars: Int,
    maxDocs: Int = MAX_SESSION_RAG_DOCS,
    maxChars: Int = MAX_SESSION_INDEX_CHARS,
): List<String> {
    val others = existing.filter { it.uri != incomingUri }
    var docs = others.size + 1
    var chars = others.sumOf { it.contentChars } + incomingChars
    if (docs <= maxDocs && chars <= maxChars) return emptyList()
    val evict = ArrayList<String>()
    for (doc in others) {
        if (docs <= maxDocs && chars <= maxChars) break
        evict += doc.uri
        docs -= 1
        chars -= doc.contentChars
    }
    return evict
}

/**
 * Other sessions whose cached tokens should be dropped so [currentSize]
 * can fall under [maxChunks]. Never evicts [keepSession].
 */
internal fun sessionsToEvictForTokenCache(
    countsBySession: Map<String, Int>,
    keepSession: String,
    currentSize: Int,
    maxChunks: Int = TOKEN_CACHE_MAX_CHUNKS,
): List<String> {
    if (currentSize <= maxChunks) return emptyList()
    var remaining = currentSize
    val evict = ArrayList<String>()
    for ((sid, n) in countsBySession) {
        if (sid == keepSession) continue
        evict += sid
        remaining -= n
        if (remaining <= maxChunks) break
    }
    return evict
}

/** Another URI in this session already indexed this exact size:chars stamp. */
internal fun existingUriWithStamp(
    fingerprints: List<Pair<String, String>>,
    uri: String,
    stamp: String,
): String? = fingerprints.firstOrNull { it.first != uri && it.second == stamp }?.first

/**
 * When [restrictUris] is non-empty, include every URI that shares a stamp
 * with a restricted file so a re-shared content URI still retrieves the
 * original chunks. Empty restrict → empty (caller uses the full corpus).
 */
internal fun expandRestrictUrisByStamp(
    fingerprints: List<Pair<String, String>>,
    restrictUris: Set<String>,
): Set<String> {
    if (restrictUris.isEmpty()) return emptySet()
    val stampByUri = fingerprints.toMap()
    val stamps = restrictUris.mapNotNull { stampByUri[it] }.toSet()
    if (stamps.isEmpty()) return restrictUris
    return restrictUris + fingerprints.filter { it.second in stamps }.map { it.first }
}

/**
 * LARGE (roomy ≥7000c) can carry a 5k note whole; STANDARD stays at the
 * historical 3k; COMPACT is tighter so a whole file cannot crowd the 1.5k
 * prompt.
 */
internal fun wholeFileCharBudget(maxPromptChars: Int): Int = when {
    maxPromptChars >= 7000 -> 5_000
    maxPromptChars >= 4500 -> FileContentExtractor.WHOLE_FILE_CHARS
    else -> 1_500
}

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

/**
 * P1 — collapse adjacent same-document hits so one BM25 peak does not fill
 * top-K with overlapping chunks from the same section. Outline rows and
 * heading-anchor scores (50) are always kept.
 *
 * Wave 1 P2 — when [preserveAnchoredSpans] is true, contiguous anchored runs
 * (score ≥ [anchoredScoreThreshold]) keep up to [anchoredSpanMax] chunks in
 * document order instead of capping at 3.
 */
internal fun collapseRedundantChunkRuns(
    retrieved: List<RetrievedChunk>,
    maxPerAdjacentRun: Int = 2,
    preserveAnchoredSpans: Boolean = false,
    anchoredSpanMax: Int = ANCHORED_SPAN_COLLAPSE_MAX,
    anchoredScoreThreshold: Double = ANCHORED_CHUNK_SCORE,
): List<RetrievedChunk> {
    if (retrieved.size <= maxPerAdjacentRun) return retrieved
    val outline = retrieved.filter { it.chunkIndex < 0 }
    val body = retrieved.filter { it.chunkIndex >= 0 }
    if (body.isEmpty()) return retrieved

    val kept = ArrayList<RetrievedChunk>(retrieved.size)
    for ((_, docHits) in body.groupBy { it.docUri }) {
        val sorted = docHits.sortedBy { it.chunkIndex }
        var runStart = 0
        while (runStart < sorted.size) {
            var runEnd = runStart + 1
            while (runEnd < sorted.size &&
                sorted[runEnd].chunkIndex - sorted[runEnd - 1].chunkIndex <= 1
            ) {
                runEnd++
            }
            val run = sorted.subList(runStart, runEnd)
            val isAnchoredRun = run.any { it.score >= anchoredScoreThreshold }
            val cap = when {
                preserveAnchoredSpans && isAnchoredRun ->
                    minOf(run.size, anchoredSpanMax)
                isAnchoredRun ->
                    maxPerAdjacentRun + 1
                else ->
                    maxPerAdjacentRun
            }
            val picked = if (preserveAnchoredSpans && isAnchoredRun) {
                run.take(cap)
            } else {
                run.sortedByDescending { it.score }.take(cap)
            }
            kept.addAll(picked)
            runStart = runEnd
        }
    }
    // Preserve cross-doc score order for the body hits we kept.
    val keptSet = kept.toSet()
    val bodyOut = retrieved.filter { it in keptSet && it.chunkIndex >= 0 }
    return outline + bodyOut
}

internal data class SectionRef(val kind: String, val token: String)

private val SECTION_REF_PATTERN = Regex(
    "(?i)(?:section|sec\\.?|§)\\s*(\\d{1,3})|(?:धारा)\\s*(\\d{1,3})",
)
private val CHAPTER_REF_PATTERN = Regex(
    "(?i)chapter\\s+([ivxlcdm]+|\\d{1,3})|(?:अध्याय)\\s*(\\d{1,3})",
)

/** Section/chapter/schedule cues that outline matching may miss (e.g. "section 15"). */
internal fun extractSectionRefs(query: String): List<SectionRef> {
    val refs = LinkedHashSet<SectionRef>()
    val lower = query.lowercase()
    SECTION_REF_PATTERN.findAll(query).forEach { m ->
        val num = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (num.isNotEmpty()) refs.add(SectionRef("section", num))
    }
    CHAPTER_REF_PATTERN.findAll(query).forEach { m ->
        val token = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (token.isNotEmpty()) refs.add(SectionRef("chapter", token.lowercase()))
    }
    if (Regex("(?i)\\bschedule\\b").containsMatchIn(query) ||
        query.contains("अनुसूची") || query.contains("अनुसुची")
    ) {
        refs.add(SectionRef("schedule", "schedule"))
    }
    return refs.toList()
}

// ── T1-3: topic / heading anchors (appeal, provisions, termination, …) ───────

internal data class TopicAnchorCategory(
    val id: String,
    val queryTokens: Set<String>,
    val querySubstrings: List<String>,
    val bodyPatterns: List<Pair<Int, Regex>>,
    val expansionTerms: String,
)

private val TOPIC_ANCHOR_CATEGORIES = listOf(
    TopicAnchorCategory(
        id = "obligation",
        queryTokens = setOf(
            "obligation", "obligations", "duty", "duties", "compliance",
        ),
        querySubstrings = listOf("दायित्व", "कर्तव्य"),
        bodyPatterns = listOf(
            0 to Regex("(?m)^\\s*CHAPTER\\s+[IVXLC\\d]+\\s+OBLIGATIONS"),
            0 to Regex("(?m)^\\s*OBLIGATIONS\\s+OF"),
            1 to Regex("(?i)\\bobligations?\\s+of\\b"),
            2 to Regex("(?i)\\bobligations?\\b"),
            2 to Regex("(?i)\\bduties\\s+of\\b"),
        ),
        expansionTerms = " obligations duties compliance दायित्व",
    ),
    TopicAnchorCategory(
        id = "penalty",
        queryTokens = setOf(
            "penalty", "penalties", "fine", "fines", "adjudication",
        ),
        querySubstrings = listOf("दंड", "जुर्माना"),
        bodyPatterns = listOf(
            0 to Regex("(?m)^\\s*CHAPTER\\s+VIII"),
            0 to Regex("(?m)^\\s*CHAPTER\\s+[IVXLC\\d]+\\s+PENALTIES"),
            0 to Regex("(?m)^\\s*PENALTIES\\s+AND\\s+ADJUDICATION"),
            0 to Regex("(?m)^\\s*THE SCHEDULE\\b"),
            1 to Regex("(?i)\\bpenalties\\s+and\\s+adjudication"),
            1 to Regex("(?i)\\bsection\\s+33\\b"),
            2 to Regex("(?i)\\bmonetary\\s+penalty"),
        ),
        expansionTerms = " penalties adjudication schedule section 33 दंड",
    ),
    TopicAnchorCategory(
        id = "appeal",
        queryTokens = setOf(
            "appeal", "appeals", "adr", "arbitration", "tribunal", "appellate", "appellant",
        ),
        querySubstrings = listOf("अपील", "पुनर्निर्धारण"),
        bodyPatterns = listOf(
            0 to Regex("(?m)^\\s*CHAPTER\\s+[IVXLC\\d]+\\s+APPEAL"),
            0 to Regex("(?i)\\bCHAPTER\\s+[IVXLC\\d]+\\s+APPEAL"),
            0 to Regex("(?m)^\\s*APPEAL\\b"),
            0 to Regex("(?m)^\\s*Appeal\\b"),
            1 to Regex("(?i)\\balternative dispute resolution"),
            1 to Regex("(?i)\\balternate dispute resolution"),
            2 to Regex("(?i)\\barbitration\\b"),
            2 to Regex("(?i)\\btribunal\\b"),
            2 to Regex("(?i)\\bappellate\\b"),
        ),
        expansionTerms = " appeal appellate tribunal arbitration dispute resolution अपील",
    ),
    TopicAnchorCategory(
        id = "provision",
        queryTokens = setOf(
            "provision", "provisions", "exception", "exceptions",
            "exemption", "exemptions",
        ),
        querySubstrings = listOf("प्रावधान", "छूट", "अपवाद"),
        bodyPatterns = listOf(
            0 to Regex("(?m)^\\s*CHAPTER\\s+[IVXLC\\d]+\\s+SPECIAL\\s+PROVISIONS"),
            0 to Regex("(?i)\\bCHAPTER\\s+[IVXLC\\d]+\\s+SPECIAL\\s+PROVISIONS"),
            0 to Regex("(?m)^\\s*SPECIAL PROVISIONS"),
            1 to Regex("(?i)\\bspecial provisions?\\b"),
            2 to Regex("(?i)\\bexemptions?\\b"),
            2 to Regex("(?i)\\bexceptions?\\b"),
        ),
        expansionTerms = " special provisions exemption exception प्रावधान",
    ),
    TopicAnchorCategory(
        id = "termination",
        queryTokens = setOf(
            "termination", "terminate", "cancellation", "cancel", "refund", "rescission",
        ),
        querySubstrings = listOf("समाप्ति", "रद्द", "धनवापसी"),
        bodyPatterns = listOf(
            0 to Regex("(?m)^\\s*TERMINATION"),
            1 to Regex("(?i)\\btermination\\b"),
            1 to Regex("(?i)\\bcancellation\\b"),
            2 to Regex("(?i)\\brefund\\b"),
        ),
        expansionTerms = " termination cancellation refund समाप्ति",
    ),
    TopicAnchorCategory(
        id = "eligibility",
        queryTokens = setOf(
            "eligibility", "eligible", "disqualification", "disqualify",
            "appointment", "qualification",
        ),
        querySubstrings = listOf("योग्यता", "अयोग्य"),
        bodyPatterns = listOf(
            0 to Regex("(?i)\\bdisqualification\\b"),
            1 to Regex("(?i)\\beligibility\\b"),
            1 to Regex("(?i)\\bqualification\\b"),
            2 to Regex("(?i)\\bappointment\\b"),
        ),
        expansionTerms = " eligibility disqualification appointment qualification",
    ),
    TopicAnchorCategory(
        id = "dispute",
        queryTokens = setOf("dispute", "disputes", "mediation", "conciliation"),
        querySubstrings = listOf("विवाद", "मध्यस्थता"),
        bodyPatterns = listOf(
            1 to Regex("(?i)\\bdispute resolution\\b"),
            2 to Regex("(?i)\\bmediation\\b"),
            2 to Regex("(?i)\\bconciliation\\b"),
        ),
        expansionTerms = " dispute mediation conciliation विवाद",
    ),
)

/** T1-3 — topic categories active for [query] (document-agnostic lexicon). */
internal fun activeTopicCategories(query: String): List<TopicAnchorCategory> {
    val lower = query.lowercase().trim()
    if (lower.isEmpty()) return emptyList()
    val tokens = lower.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
    return TOPIC_ANCHOR_CATEGORIES.filter { cat ->
        tokens.any { it in cat.queryTokens } ||
            cat.querySubstrings.any { query.contains(it) }
    }
}

/** BM25 expansion terms for active topic categories. */
internal fun topicAnchorQueryExpansion(query: String): String {
    val cats = activeTopicCategories(query)
    if (cats.isEmpty()) return ""
    return cats.map { it.expansionTerms }.distinct().joinToString("")
}

/** T1-3 — ranked body chunks whose text matches topic anchor patterns. */
internal fun pickTopicAnchorChunkEntities(
    contentChunks: List<RagChunkEntity>,
    query: String,
    max: Int,
    substanceOnly: Boolean = !usesTocForRetrieval(query),
): List<RagChunkEntity> {
    val categories = activeTopicCategories(query)
    if (categories.isEmpty()) return emptyList()
    val pool = if (substanceOnly) contentChunks.filter { !isTocLikeChunk(it) } else contentChunks
    val patterns = categories.flatMap { it.bodyPatterns }
    val ranked = pool.mapNotNull { chunk ->
        var bestTier: Int? = null
        for ((tier, pattern) in patterns) {
            if (pattern.containsMatchIn(chunk.text)) {
                bestTier = minOf(bestTier ?: Int.MAX_VALUE, tier)
            }
        }
        if (bestTier == null) null else chunk to bestTier
    }.sortedWith(
        compareBy<Pair<RagChunkEntity, Int>> { it.second }
            .thenBy { topicAnchorLineStartTier(it.first.text, patterns) }
            .thenBy { it.first.chunkIndex },
    )
    return ranked.take(max).map { it.first }
}

/** Prefer chunks where a tier-0/1 pattern hits a line-start heading, not mid-sentence. */
internal fun topicAnchorLineStartTier(text: String, patterns: List<Pair<Int, Regex>>): Int {
    var best = Int.MAX_VALUE
    for ((tier, pattern) in patterns) {
        if (tier > 1) continue
        for (line in text.lines()) {
            if (pattern.containsMatchIn(line.trim())) {
                best = minOf(best, tier)
            }
        }
    }
    return best
}

/** T1-2 — best chapter-header tier in [text]; null when no chapter marker. */
internal fun chapterHeaderMatchTier(text: String): Int? {
    val tiers = mutableListOf<Int>()
    fun add(tier: Int, pattern: Regex) {
        if (pattern.containsMatchIn(text)) tiers.add(tier)
    }
    add(0, Regex("(?m)^\\s*CHAPTER\\s+[IVXLC]+\\b"))
    add(0, Regex("(?m)^\\s*Chapter\\s+[IVXLC]+\\b"))
    add(1, Regex("(?m)^\\s*CHAPTER\\s+\\d+\\b"))
    add(1, Regex("(?m)^\\s*Chapter\\s+\\d+\\b"))
    add(0, Regex("(?m)^\\s*अध्याय\\s+[\\p{N}\\w]+"))
    add(2, Regex("(?i)\\bchapter\\s+[ivxlc]+\\b"))
    add(3, Regex("(?i)\\bchapter\\s+\\d+\\b"))
    return tiers.minOrNull()
}

/** T1-2 — score [text] for structure-marker density by [kind]. */
internal fun structureMarkerScore(text: String, kind: String): Int? = when (kind) {
    "section" -> {
        val tiers = mutableListOf<Int>()
        fun add(tier: Int, pattern: Regex) {
            if (pattern.containsMatchIn(text)) tiers.add(tier)
        }
        add(0, Regex("(?m)^\\s*Section\\s+\\d+\\b"))
        add(0, Regex("(?m)^\\s*SECTION\\s+\\d+\\b"))
        add(0, Regex("(?m)^\\s*धारा\\s*\\d+"))
        add(2, Regex("(?i)\\bsection\\s+\\d+\\b"))
        tiers.minOrNull()
    }
    "part" -> {
        val tiers = mutableListOf<Int>()
        fun add(tier: Int, pattern: Regex) {
            if (pattern.containsMatchIn(text)) tiers.add(tier)
        }
        add(0, Regex("(?m)^\\s*PART\\s+[IVXLC\\d]+\\b"))
        add(1, Regex("(?i)\\bpart\\s+[ivxlc\\d]+\\b"))
        tiers.minOrNull()
    }
    "heading" -> {
        val count = text.lines().count { line ->
            val trimmed = line.trim()
            trimmed.length in 3..80 && isLikelyHeadingLine(trimmed, nextLineBlank = false)
        }
        if (count >= 2) 1 else null
    }
    "annex" -> {
        val tiers = mutableListOf<Int>()
        fun add(tier: Int, pattern: Regex) {
            if (pattern.containsMatchIn(text)) tiers.add(tier)
        }
        add(0, Regex("(?m)^\\s*ANNEX(?:URE)?\\s+[IVXLC\\d]+\\b"))
        add(1, Regex("(?i)\\bannex(?:ure)?\\s+[ivxlc\\d]+\\b"))
        tiers.minOrNull()
    }
    else -> chapterHeaderMatchTier(text)
}

/** T1-2 — ranked body chunks carrying chapter/section/part markers. */
internal fun pickStructureMarkerChunkEntities(
    contentChunks: List<RagChunkEntity>,
    query: String,
    max: Int,
): List<RagChunkEntity> {
    val kind = structureMarkerKind(query)
    val ranked = contentChunks.mapNotNull { chunk ->
        val tier = structureMarkerScore(chunk.text, kind) ?: return@mapNotNull null
        val markerCount = countStructureMarkersInText(chunk.text, kind)
        chunk to StructureMarkerRank(tier, markerCount)
    }.sortedWith(
        compareBy<Pair<RagChunkEntity, StructureMarkerRank>> { it.second.tier }
            .thenByDescending { it.second.markerCount }
            .thenBy { it.first.chunkIndex },
    )
    return ranked.take(max).map { it.first }
}

/** Tier + how many line-start structure markers live in the chunk (TOC density). */
internal data class StructureMarkerRank(val tier: Int, val markerCount: Int)

/** Count distinct line-start structure markers in [text] for [kind]. */
internal fun countStructureMarkersInText(text: String, kind: String): Int =
    distinctStructureMarkersFromText(text, kind).size

private fun distinctStructureMarkersFromText(text: String, kind: String): List<String> {
    val found = linkedSetOf<String>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        when (kind) {
            "section" -> {
                val m = SECTION_MARKER_RX.find(trimmed) ?: continue
                found.add(m.groupValues[1])
            }
            "part" -> {
                val m = PART_MARKER_RX.find(trimmed) ?: continue
                found.add(m.groupValues[1].uppercase())
            }
            "annex" -> {
                val m = Regex("(?im)^\\s*ANNEX(?:URE)?\\s+([IVXLC\\d]+)\\b").find(trimmed) ?: continue
                found.add(m.groupValues[1].uppercase())
            }
            else -> {
                val m = CHAPTER_MARKER_RX.find(trimmed)
                if (m != null) {
                    found.add(m.groupValues[1].uppercase())
                }
                // Gazette PDFs often embed "CHAPTER IV …" mid-line, not at line start.
                for (inline in CHAPTER_INLINE_RX.findAll(trimmed)) {
                    found.add(inline.groupValues[1].uppercase())
                }
            }
        }
    }
    return found.toList()
}

private val CHAPTER_MARKER_RX = Regex(
    "(?im)^\\s*(?:CHAPTER|Chapter|अध्याय)\\s+([IVXLC\\d]+)(?:\\s+.+)?\\s*$",
)
/** Mid-line chapter markers (common in Indian gazette / legal PDF headers). */
private val CHAPTER_INLINE_RX = Regex("(?i)\\bCHAPTER\\s+([IVXLC\\d]+)\\b")
private val SECTION_MARKER_RX = Regex("(?im)^\\s*(?:Section|SECTION|धारा)\\s+(\\d+)\\b")
private val PART_MARKER_RX = Regex("(?im)^\\s*PART\\s+([IVXLC\\d]+)\\b")
private val BODY_CHAPTER_TITLE_RX = Regex(
    "(?i)(CHAPTER|PENALTIES\\s+AND|APPEAL\\s+AND|OBLIGATIONS\\s+OF|SPECIAL\\s+PROVISION)",
)

/** T1-2 — distinct structure markers across the full scoped corpus (not top-k). */
internal fun distinctStructureMarkers(
    contentChunks: List<RagChunkEntity>,
    kind: String,
    extraTexts: List<String> = emptyList(),
): List<String> {
    val found = linkedSetOf<String>()
    for (chunk in contentChunks) {
        found.addAll(distinctStructureMarkersFromText(chunk.text, kind))
    }
    for (text in extraTexts) {
        found.addAll(distinctStructureMarkersFromText(text, kind))
    }
    return found.toList()
}

/** T1-2 — corpus-wide count hint so the model does not guess from partial excerpts. */
internal fun buildStructureCountHint(
    query: String,
    contentChunks: List<RagChunkEntity>,
    extraTexts: List<String> = emptyList(),
    chapterRegistries: Map<String, DocumentChapterRegistry> = emptyMap(),
): String? {
    if (!isStructureCountQuery(query)) return null
    val kind = structureMarkerKind(query)
    if (kind == "chapter" && chapterRegistries.isNotEmpty()) {
        val chapters = registryChaptersInOrder(chapterRegistries)
        if (chapters.isNotEmpty()) {
            return buildRegistryCountHint(chapters, kind)
        }
    }
    val markers = distinctStructureMarkers(contentChunks, kind, extraTexts)
    if (markers.isEmpty()) return null
    val unit = when (kind) {
        "section" -> "sections"
        "part" -> "parts"
        "annex" -> "annexes"
        else -> "chapters"
    }
    val preview = markers.take(12).joinToString(", ")
    val ellipsis = if (markers.size > 12) ", …" else ""
    return buildString {
        append("Document structure scan ($unit): ")
        append(markers.size)
        append(" distinct $unit markers detected (")
        append(preview)
        append(ellipsis)
        append("). Use this corpus-wide count; do not guess from a partial excerpt.")
    }
}

/** Score query ↔ chapter-title line overlap (0..1). */
internal fun chapterTitleLineScore(query: String, line: String): Double {
    val qTokens = headingTokens(query)
    val lineTokens = headingTokens(line)
    if (qTokens.isEmpty() || lineTokens.isEmpty()) return 0.0
    val overlap = qTokens.count { it in lineTokens }
    if (overlap < 2) return 0.0
    val denom = maxOf(qTokens.size, lineTokens.size)
    return overlap.toDouble() / denom
}

/** T1-4 — BM25 expansion for fee/schedule/amount retrieval. */
internal fun tabularAmountQueryExpansion(): String =
    " Schedule THE SCHEDULE monetary penalty fee tariff charge rupee crore lakh ₹ शुल्क अनुसूची"

/** T1-4 — tier for tabular / amount-heavy chunk text; lower = stronger signal. */
internal fun tabularChunkTier(text: String, mimeType: String = ""): Int? {
    val tiers = mutableListOf<Int>()
    fun add(tier: Int, pattern: Regex) {
        if (pattern.containsMatchIn(text)) tiers.add(tier)
    }
    val amountLineRx = Regex(
        "(?i)(₹|rs\\.?|inr|rupee|rupees|\\d+[,.]?\\d*\\s*(crore|lakh|lakhs|%|usd|eur))",
    )
    val amountLines = text.lines().count { amountLineRx.containsMatchIn(it) }
    add(0, Regex("(?im)^\\s*CHAPTER\\s+VIII\\b"))
    add(0, Regex("(?im)PENALTIES\\s+AND\\s+ADJUDICATION"))
    add(0, Regex("(?im)^\\s*33\\.\\s*Penalties"))
    add(0, Regex("(?im)\\bschedule\\b[^\\n]{0,80}monetary\\s+penalty"))
    add(0, Regex("(?m)^\\s*THE SCHEDULE\\b"))
    if (amountLines >= 3) tiers.add(1)
    add(1, Regex("(?i)\\bschedule\\b"))
    add(2, Regex("(?i)monetary\\s+penalty"))
    add(2, Regex("(?i)fee\\s+structure"))
    add(2, Regex("(?i)\\btariff\\b"))
    add(2, Regex("(?i)rate\\s+card"))
    add(2, Regex("(?i)charges?\\s+table"))
    add(3, Regex("(?i)chapter\\s+viii"))
    if (amountLines >= 2) tiers.add(3)
    if (amountLines >= 1) tiers.add(4)
    if (mimeType.contains("csv", ignoreCase = true) && amountLines >= 1) {
        tiers.add(1)
    }
    return tiers.minOrNull()
}

/**
 * T1-4 — cross-reference fragments that mention penalties/fees without a
 * chapter/schedule header or amount table — common BM25 false positives.
 */
internal fun isTabularWeakFragment(text: String): Boolean {
    if (Regex("(?im)^(THE SCHEDULE|CHAPTER\\s+)").containsMatchIn(text.trimStart())) return false
    if (Regex("(?im)PENALTIES\\s+AND\\s+ADJUDICATION").containsMatchIn(text)) return false
    if (Regex("(?i)section\\s+33").containsMatchIn(text)) return false
    if (tabularAmountLineCount(text) >= 2) return false
    if (Regex("(?im)^\\s*33\\.\\s*Penalties").containsMatchIn(text)) return false
    val trimmed = text.trimStart()
    if (trimmed.length < 320 &&
        trimmed.firstOrNull()?.isLowerCase() == true &&
        Regex("(?i)penalt").containsMatchIn(text)
    ) {
        return true
    }
    if (Regex("(?i)chapter\\s+[ivxlc\\d]+").containsMatchIn(text) &&
        !Regex("(?im)^\\s*CHAPTER").containsMatchIn(text) &&
        tabularAmountLineCount(text) < 2
    ) {
        return true
    }
    return Regex("(?i)penalt").containsMatchIn(text) &&
        !Regex("(?i)(schedule|chapter\\s+[ivxlc\\d]+|section\\s+\\d+)").containsMatchIn(text) &&
        tabularAmountLineCount(text) < 1
}

/** T1-4 — ranked tabular / schedule / amount chunk pick; optional same-doc preference. */
internal fun pickTabularAmountChunkEntities(
    contentChunks: List<RagChunkEntity>,
    preferDocUri: String?,
    max: Int,
): List<RagChunkEntity> {
    val ranked = contentChunks.mapNotNull { chunk ->
        val tier = tabularChunkTier(chunk.text, chunk.mimeType) ?: return@mapNotNull null
        chunk to tier
    }.sortedWith(
        compareBy<Pair<RagChunkEntity, Int>> { if (isTabularWeakFragment(it.first.text)) 1 else 0 }
            .thenBy { it.second }
            .thenByDescending { tabularAmountLineCount(it.first.text) }
            .thenBy { it.first.chunkIndex },
    )
    if (preferDocUri.isNullOrEmpty()) {
        return ranked.take(max).map { it.first }
    }
    val sameDoc = ranked.filter { it.first.docUri == preferDocUri }
    val picked = sameDoc.take(max).map { it.first }
    if (picked.size >= max) return picked
    val rest = ranked.filter { it.first.docUri != preferDocUri }
    return picked + rest.take(max - picked.size).map { it.first }
}

/** Count of lines carrying currency / amount signals — ranks richer tables first. */
internal fun tabularAmountLineCount(text: String): Int {
    val amountLineRx = Regex(
        "(?i)(₹|rs\\.?|inr|rupee|rupees|\\d+[,.]?\\d*\\s*(crore|lakh|lakhs|%))",
    )
    return text.lines().count { amountLineRx.containsMatchIn(it) }
}

/** B1/B2-1 — backward-compatible alias for tabular amount pick. */
internal fun pickPenaltyScheduleChunkEntities(
    contentChunks: List<RagChunkEntity>,
    preferDocUri: String?,
    max: Int,
): List<RagChunkEntity> = pickTabularAmountChunkEntities(contentChunks, preferDocUri, max)

/**
 * B2-1 — best section-header tier in [text] for [num]. Lower tier = stronger
 * header signal; null = no match. Used to prefer "Section 15" over a bare
 * "15." list item or mid-sentence cross-reference.
 */
internal fun sectionHeaderMatchTier(text: String, num: String): Int? {
    val tiers = mutableListOf<Int>()
    fun add(tier: Int, pattern: Regex) {
        if (pattern.containsMatchIn(text)) tiers.add(tier)
    }
    add(0, Regex("(?m)^\\s*Section\\s+$num\\b"))
    add(0, Regex("(?m)^\\s*SECTION\\s+$num\\b"))
    add(0, Regex("(?m)^\\s*धारा\\s*$num\\b"))
    add(1, Regex("(?m)^\\s*Sec\\.?\\s+$num\\b"))
    add(1, Regex("(?m)^\\s*§\\s*$num\\b"))
    add(2, Regex("(?i)\\bSection\\s+$num\\b"))
    add(3, Regex("(?i)\\bsec\\.?\\s+$num\\b"))
    add(3, Regex("(?i)§\\s*$num\\b"))
    add(2, Regex("(?m)(?:^|\\n)\\s*धारा\\s*$num\\b"))
    // Numbered act-style heading: "15. Duties…" — title-shaped, not "15. minor fix"
    add(4, Regex("(?m)(?:^|\\n)\\s*$num\\.\\s+[\\p{Lu}A-Z]"))
    add(5, Regex("(?m)(?:^|\\n)\\s*$num\\.\\s+\\p{L}"))
    return tiers.minOrNull()
}

internal fun locateSectionInChunks(chunkTexts: List<String>, ref: SectionRef): Int {
    if (chunkTexts.isEmpty()) return -1
    when (ref.kind) {
        "section" -> {
            val num = ref.token
            var bestIdx = -1
            var bestTier = Int.MAX_VALUE
            chunkTexts.forEachIndexed { idx, text ->
                val tier = sectionHeaderMatchTier(text, num) ?: return@forEachIndexed
                if (tier < bestTier) {
                    bestTier = tier
                    bestIdx = idx
                } else if (tier == bestTier && idx < bestIdx) {
                    bestIdx = idx
                }
            }
            return bestIdx
        }
        "chapter" -> {
            val aliases = chapterIdAliases(ref.token)
            return findChapterTitleChunkIndex(chunkTexts, aliases)
        }
        "schedule" -> {
            return chunkTexts.indexOfFirst { text ->
                Regex("(?i)\\bschedule\\b").containsMatchIn(text) ||
                    text.contains("अनुसूची") || text.contains("SCHEDULE")
            }
        }
        else -> return -1
    }
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
internal val META_DEVANAGARI_PATTERN = Regex(
    "(अनुक्रम|सारांश|विषयसूची|अवलोकन|संक्षेप|आढावा|दस्तऐवर)",
)

internal fun isDevanagariMetaTrigger(query: String): Boolean =
    META_DEVANAGARI_PATTERN.containsMatchIn(query)

/**
 * After BM25 ranking, drop tail hits far below the top score so narrow QA
 * does not pack every weakly related chunk (P0 #2).
 */
internal fun filterRankedByScoreGap(
    ranked: List<Bm25Retriever.Scored>,
    maxKeep: Int,
    gapRatio: Double = 0.35,
): List<Bm25Retriever.Scored> {
    if (ranked.isEmpty()) return ranked
    val top = ranked.first().score
    if (top <= 0.0) return ranked.take(maxKeep)
    val threshold = top * gapRatio
    return ranked.filter { it.score >= threshold }.take(maxKeep.coerceAtLeast(1))
}

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
    "contents", "provide", "show", "list", "alternate", "alternative",
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

/**
 * True when [line] looks like a document heading. Structural ingest markers
 * (Page / Slide / Sheet / CSV / Rows) are never headings.
 *
 * Numbered and short-line rules accept any Indic letter, not just Devanagari,
 * so a Tamil/Bengali DOCX outline is detected the same way as Hindi.
 * Latin all-caps is restricted to lines that actually contain A–Z — Indic
 * scripts have no case, so `uppercase()` would otherwise flag every short
 * body line as a heading.
 */
internal fun isLikelyHeadingLine(line: String, nextLineBlank: Boolean): Boolean {
    if (line.length < 3 || line.length > 80) return false
    if (line.startsWith("---") && line.endsWith("---")) return false

    if (line.matches(Regex("(?i)^(chapter|section|part|appendix|annexure|article|unit)\\s+[\\w\\d.]+.*"))) {
        return true
    }

    val hasLatinUpper = line.any { it in 'A'..'Z' }
    if (hasLatinUpper && line == line.uppercase() && line.length <= 60) return true

    val numbered = Regex("^\\d+(\\.\\d+)*[\\s.:]+(.+)$").matchEntire(line)
    if (numbered != null) {
        val rest = numbered.groupValues[2].trim()
        val firstLetter = rest.firstOrNull { it.isLetter() }
        if (firstLetter != null && rest.length >= 3 &&
            (firstLetter.isUpperCase() || isIndicLetter(firstLetter))
        ) {
            return true
        }
    }

    val words = line.split(Regex("\\s+"))
    if (words.size in 1..7 &&
        !line.endsWith(".") && !line.endsWith(",") &&
        line.last() != '।' && line.last() != '॥'
    ) {
        val headingShaped = words.all { w ->
            if (w.isEmpty()) return@all true
            val c = w.firstOrNull { it.isLetter() } ?: return@all true
            c.isUpperCase() || c.isDigit() || isIndicLetter(c)
        }
        if (headingShaped && nextLineBlank && line.any { it.isLetter() }) return true
    }
    return false
}

/** Heading lines ("- …") parsed out of the auto-extracted outline chunk. */
internal fun parseOutlineHeadings(outlineText: String): List<String> =
    outlineText.lines()
        .map { it.trim() }
        .filter { it.startsWith("- ") }
        .map { it.removePrefix("- ").trim() }
        .filter { it.isNotEmpty() }

/**
 * Best heading from [headings] that the [query] names, or null. Tries strict
 * all-token match first, then fuzzy overlap (T1-3) for OCR / wording gaps.
 */
internal fun matchHeading(query: String, headings: List<String>): String? =
    matchHeadingStrict(query, headings)
        ?: matchHeadingKeywordBridge(query, headings)
        ?: matchHeadingFuzzy(query, headings)

/**
 * Single-keyword bridge when the query names a topic but not the full heading
 * (e.g. "what does the document say about penalties" → "PENALTIES AND ADJUDICATION").
 */
internal fun matchHeadingKeywordBridge(query: String, headings: List<String>): String? {
    val lower = query.lowercase()
    val bridges = listOf(
        Regex("(?i)\\bpenalt") to Regex("(?i)(PENALTIES\\s+AND|CHAPTER\\s+VIII)"),
        Regex("(?i)\\badjudicat") to Regex("(?i)PENALTIES\\s+AND"),
        Regex("(?i)\\bobligat") to Regex("(?i)OBLIGATIONS\\s+OF"),
        Regex("(?i)\\bspecial\\s+provision") to Regex("(?i)SPECIAL\\s+PROVISIONS"),
        Regex("(?i)\\bappeal") to Regex("(?i)APPEAL\\s+AND"),
        Regex("(?i)\\blegitimate\\s+use") to Regex("(?i)LEGITIMATE\\s+USE"),
    )
    for ((queryRx, headingRx) in bridges) {
        if (!queryRx.containsMatchIn(lower)) continue
        val match = headings.firstOrNull { headingRx.containsMatchIn(it) }
        if (match != null) return match
    }
    return null
}

/**
 * Conservative match: every significant heading token must appear in the query.
 */
internal fun matchHeadingStrict(query: String, headings: List<String>): String? {
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

/**
 * T1-3 — partial overlap when strict match fails: at least two significant
 * tokens and ≥50% of the heading's significant tokens present in the query.
 */
internal fun matchHeadingFuzzy(query: String, headings: List<String>): String? {
    val qTokens = headingTokens(query)
    if (qTokens.isEmpty()) return null
    var best: String? = null
    var bestScore = 0.0
    for (h in headings) {
        val significant = headingTokens(h).filter { it.length >= 4 }
        if (significant.isEmpty() || significant.sumOf { it.length } < 6) continue
        val overlap = significant.count { it in qTokens }
        if (overlap < 2) continue
        val ratio = overlap.toDouble() / significant.size
        if (ratio < 0.5) continue
        val score = overlap * ratio
        if (score > bestScore) {
            bestScore = score
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
                // Statement / table rows: snap to a complete row and keep a
                // contiguous run together instead of cutting mid-row or mixing
                // the last row into the following prose sentence.
                val tableEnd = findTableBlockEnd(cleaned, start, hardEnd)
                if (tableEnd > start) {
                    tableEnd
                } else {
                    val sentenceEnd = findSentenceBoundary(cleaned, start + chunkSize / 2, hardEnd)
                    if (sentenceEnd > 0) sentenceEnd
                    else findWordBoundary(cleaned, hardEnd)
                }
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

/**
 * If [start] sits in a contiguous run of table/statement rows, return the
 * exclusive end of the last complete row that fits in [hardEnd]. Never cuts
 * mid-row. Returns 0 when the window is not a table block so prose chunking
 * is unchanged.
 */
internal fun findTableBlockEnd(text: String, start: Int, hardEnd: Int): Int {
    if (start >= hardEnd || start >= text.length) return 0
    var lineStart = start
    while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--

    val rowEnds = ArrayList<Int>(8)
    var i = lineStart
    while (i < text.length) {
        val nl = text.indexOf('\n', i)
        val lineEnd = if (nl < 0) text.length else nl + 1
        val line = text.substring(i, minOf(lineEnd, text.length)).trim()
        if (line.isEmpty()) {
            if (rowEnds.size >= 2) break
            if (rowEnds.isEmpty()) {
                i = lineEnd
                continue
            }
            break
        }
        if (!looksLikeTableRow(line)) {
            if (rowEnds.size >= 2) break
            return 0
        }
        rowEnds.add(lineEnd)
        i = lineEnd
        if (lineEnd >= hardEnd) break
    }
    if (rowEnds.size < 2) return 0
    val fitted = rowEnds.lastOrNull { it <= hardEnd } ?: rowEnds.first()
    return fitted.coerceAtMost(text.length)
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
