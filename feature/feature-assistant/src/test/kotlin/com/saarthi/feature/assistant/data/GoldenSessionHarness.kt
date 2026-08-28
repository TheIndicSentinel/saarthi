package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import com.saarthi.core.inference.prompt.SystemPromptProvider
import com.saarthi.core.memory.db.RagChunkEntity
import com.saarthi.core.rag.Bm25Retriever

/**
 * Wave 5 P22 — JVM golden session harness: index fixtures → retrieve → prompt assembly
 * → citation gating. Exercises anchors, collapse, ragChars, and turn modes without Room.
 */
internal data class GoldenTurnSpec(
    val query: String,
    val priorQuery: String? = null,
    val attachmentsThisTurn: Boolean = false,
    val boostDocUris: Set<String> = emptySet(),
)

internal data class GoldenPromptMetrics(
    val retrieved: List<RetrievedChunk>,
    val turnMode: RagTurnMode,
    val ragChars: Int,
    val chunkCount: Int,
    val anchoredChunkCount: Int,
    val shouldCite: Boolean,
    val strongMatch: Boolean,
)

internal fun goldenDocsToEntities(
    docs: List<GoldenDoc>,
    sessionId: String = "golden-session",
): List<RagChunkEntity> {
    val entities = ArrayList<RagChunkEntity>()
    var id = 1L
    for (doc in docs) {
        if (extractionFailureMessage(doc.text) != null) continue
        val chunks = chunkDocumentTextForIndexing(doc.text, mimeType = "application/pdf")
        if (chunks.isEmpty()) continue
        val registry = buildDocumentChapterRegistry(chunks, outlineText = null)
        val metadata = computeChunkMetadata(chunks, registry)
        if (registry.chapters.isNotEmpty()) {
            entities.add(
                RagChunkEntity(
                    id = id++,
                    sessionId = sessionId,
                    docUri = doc.uri,
                    docName = doc.name,
                    mimeType = "application/pdf",
                    chunkIndex = STRUCTURE_REGISTRY_CHUNK_INDEX,
                    text = encodeChapterRegistry(registry),
                    chunkRole = ChunkRole.REGISTRY,
                ),
            )
        }
        chunks.forEachIndexed { idx, text ->
            val meta = metadata[idx]
            entities.add(
                RagChunkEntity(
                    id = id++,
                    sessionId = sessionId,
                    docUri = doc.uri,
                    docName = doc.name,
                    mimeType = "application/pdf",
                    chunkIndex = idx,
                    text = text,
                    chapterId = meta.chapterId,
                    sectionNum = meta.sectionNum,
                    headingPath = meta.headingPath,
                    pageNum = meta.pageNum,
                    chunkRole = meta.role,
                ),
            )
        }
    }
    return entities
}

internal fun goldenSessionRetrieve(
    query: String,
    entities: List<RagChunkEntity>,
    sessionFiles: List<Pair<String, String>>,
    priorQuery: String? = null,
    boostDocUris: Set<String> = emptySet(),
    attachmentsThisTurn: Boolean = false,
): List<RetrievedChunk> {
    val all = entities
    val contentChunks = all.filter { it.chunkIndex >= 0 }
    if (contentChunks.isEmpty()) return emptyList()

    val route = routeQuery(query, sessionFiles)
    val queryTokens = query.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
    val isFollowUp = !priorQuery.isNullOrBlank() && queryTokens.take(4).any { it in FOLLOW_UP_TOKENS }
    val metaReason = effectiveMetaRouteReason(query, isFollowUp)
    val spanPreserving = isSpanPreservingQuery(query)
    val shape = detectRagAnswerShape(query, metaOverview = metaReason != null)
    val effectiveTopK = effectiveRetrievalTopK(query, shape, route.equalSlots)
    val recencyUri = contentChunks.maxByOrNull { it.id }?.docUri.orEmpty()

    if (metaReason != null && !isFollowUp && !bypassMetaForSubstanceQuery(query)) {
        val opening = contentChunks.groupBy { it.docUri }.flatMap { (_, chunks) ->
            chunks.sortedBy { it.chunkIndex }.take(6).map { e ->
                RetrievedChunk(e.text, e.docName, 0.0, e.chunkIndex, e.docUri)
            }
        }.take(effectiveTopK)
        return finishGoldenRetrieve(
            opening,
            query,
            contentChunks,
            effectiveTopK,
            boostDocUris,
            route,
            recencyUri,
            spanPreserving,
        )
    }

    val chapterSpanChunks = if (isChapterSpanQuery(query)) {
        resolveChapterSpanChunks(contentChunks, query, SPAN_ANCHOR_WINDOW)
    } else {
        emptyList()
    }
    val topicAnchored = pickTopicAnchorChunkEntities(contentChunks, query, TOPIC_ANCHOR_MAX)
    val tabularContract = if (requiresTabularContract(query)) {
        tabularContractChunkEntities(contentChunks)
    } else {
        emptyList()
    }
    val anchoredEntities = chapterSpanChunks + topicAnchored + tabularContract

    var effectiveQuery = if (isFollowUp && !priorQuery.isNullOrBlank()) {
        "${priorQuery.take(150)} ${route.expandedQuery}"
    } else {
        route.expandedQuery
    }
    if (isTabularAmountQuery(query)) {
        effectiveQuery += tabularAmountQueryExpansion()
    }
    val topicExpansion = topicAnchorQueryExpansion(query)
    if (topicExpansion.isNotEmpty()) effectiveQuery += topicExpansion

    val uniqueDocs = contentChunks.map { it.docUri }.distinct().size.coerceAtLeast(1)
    val candidateK = featureRerankCandidatePoolSize(effectiveTopK, uniqueDocs, contentChunks.size)
    val rerankCtx = buildFeatureRerankContext(query)
    val tokenised = contentChunks.map { Bm25Retriever.tokeniseDocument(it.text) }
    val bm25Candidates = Bm25Retriever.rankTokenised(tokenised, effectiveQuery, candidateK)
    val ranked = filterRankedByScoreGap(
        featureRerankBm25Candidates(bm25Candidates, contentChunks, query, rerankCtx),
        effectiveTopK,
    )

    val docChunksByUri = contentChunks.groupBy { it.docUri }.mapValues { (_, list) -> list.sortedBy { it.chunkIndex } }
    val orderedIdsByDoc = docChunksByUri.mapValues { (_, list) -> list.map { it.id } }
    val usedIds = LinkedHashSet<Long>()
    val hits = mutableListOf<RetrievedChunk>()

    for (e in anchoredEntities) {
        if (usedIds.add(e.id)) {
            hits.add(e.toRetrieved(ANCHORED_CHUNK_SCORE))
        }
    }
    for (scored in ranked) {
        val entity = contentChunks[scored.index]
        if (usedIds.add(entity.id)) {
            hits.add(entity.toRetrieved(scored.score))
        }
    }
    for ((entity, score) in expandRerankedNeighborHits(
        ranked = ranked,
        pool = contentChunks,
        docChunksByUri = docChunksByUri,
        orderedIdsByDoc = orderedIdsByDoc,
    )) {
        if (usedIds.add(entity.id)) {
            hits.add(entity.toRetrieved(score))
        }
    }

    return finishGoldenRetrieve(
        hits,
        query,
        contentChunks,
        effectiveTopK,
        boostDocUris,
        route,
        recencyUri,
        spanPreserving,
    )
}

private fun finishGoldenRetrieve(
    hits: List<RetrievedChunk>,
    query: String,
    contentChunks: List<RagChunkEntity>,
    effectiveTopK: Int,
    boostDocUris: Set<String>,
    route: QueryRoute,
    recencyUri: String,
    spanPreserving: Boolean,
): List<RetrievedChunk> {
    val docCount = hits.map { it.docUri }.filter { it.isNotEmpty() }.distinct().size.coerceAtLeast(1)
    val minSlots = if (route.equalSlots) (effectiveTopK / docCount).coerceAtLeast(1) else 1
    val allocated = allocatePerDocSlots(
        applySessionBoost(hits, boostDocUris, recencyUri, route.namedDocUris),
        effectiveTopK,
        minSlots,
    )
    val condensed = collapseRedundantChunkRuns(
        allocated,
        preserveAnchoredSpans = spanPreserving,
        anchoredSpanMax = ANCHORED_SPAN_COLLAPSE_MAX,
    )
    return applyChapterRetrievalConfidence(
        query = query,
        retrieved = condensed,
        contentChunks = contentChunks,
        expandedSpanChunks = ANCHORED_SPAN_COLLAPSE_MAX,
    )
}

internal fun runGoldenTurn(
    spec: GoldenTurnSpec,
    docs: List<GoldenDoc>,
    charBudget: Int = 4000,
): GoldenPromptMetrics {
    val entities = goldenDocsToEntities(docs)
    val sessionFiles = docs.map { it.uri to it.name }
    val sessionDocCount = docs.size
    val turnMode = classifyRagTurnMode(
        query = spec.query,
        sessionDocCount = sessionDocCount,
        attachmentsThisTurn = spec.attachmentsThisTurn,
    )
    val retrieved = goldenSessionRetrieve(
        query = spec.query,
        entities = entities,
        sessionFiles = sessionFiles,
        priorQuery = spec.priorQuery,
        boostDocUris = spec.boostDocUris,
        attachmentsThisTurn = spec.attachmentsThisTurn,
    )
    val shape = detectRagAnswerShape(spec.query, metaOverview = false)
    val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()
    val assembly = assembleRagPromptBlock(
        retrieved = retrieved,
        unreadableThisTurn = emptyList(),
        tier = SystemPromptProvider.ModelTier.STANDARD,
        charBudget = charBudget,
        sessionDocs = docs.map { SessionRagDocument(it.uri, it.name, 0L) },
        answerShape = shape,
        citationLabels = labels,
        forceGroundedDelivery = turnMode == RagTurnMode.DOCUMENT_GROUNDED,
        turnMode = turnMode,
        ragQuery = spec.query,
        attachmentsThisTurn = spec.attachmentsThisTurn,
    )
    val ragChars = assembly.block.length
    val shouldCite = shouldAttachDeterministicSources(
        turnMode = turnMode,
        ragBlockChars = ragChars,
        retrieved = retrieved,
        query = spec.query,
        attachmentsThisTurn = spec.attachmentsThisTurn,
    )
    val strongMatch = shouldUseStrongMatchPromptRules(
        retrieved = retrieved,
        query = spec.query,
        turnMode = turnMode,
        attachmentsThisTurn = spec.attachmentsThisTurn,
    )
    return GoldenPromptMetrics(
        retrieved = retrieved,
        turnMode = turnMode,
        ragChars = ragChars,
        chunkCount = retrieved.count { it.chunkIndex >= 0 },
        anchoredChunkCount = retrieved.count { it.score >= ANCHORED_CHUNK_SCORE },
        shouldCite = shouldCite,
        strongMatch = strongMatch,
    )
}

private fun RagChunkEntity.toRetrieved(score: Double) = RetrievedChunk(
    text = text,
    docName = docName,
    score = score,
    chunkIndex = chunkIndex,
    docUri = docUri,
)

/** Organic BM25/rerank primary doc — excludes anchored contract chunks for BM25 parity. */
internal fun goldenPipelinePrimaryDocUri(retrieved: List<RetrievedChunk>): String? {
    val organic = retrieved.filter { it.chunkIndex >= 0 && it.score < ANCHORED_CHUNK_SCORE }
    if (organic.isNotEmpty()) return organic.maxByOrNull { it.score }?.docUri
    return retrieved.filter { it.chunkIndex >= 0 }.maxByOrNull { it.score }?.docUri
}

/** Chapter IDs present in retrieved content chunks — regression span lens for golden harness. */
internal fun goldenRetrievedChapterIds(
    entities: List<RagChunkEntity>,
    retrieved: List<RetrievedChunk>,
): Set<String> {
    val byKey = entities
        .filter { it.chunkIndex >= 0 && !it.chapterId.isNullOrBlank() }
        .associateBy { it.docUri to it.chunkIndex }
    return retrieved.mapNotNull { r ->
        if (r.chunkIndex < 0 || r.docUri.isEmpty()) return@mapNotNull null
        byKey[r.docUri to r.chunkIndex]?.chapterId
    }.toSet()
}

/** Topic anchor cap — mirrors RagDocumentRepository companion. */
private const val TOPIC_ANCHOR_MAX = 3

/** Follow-up tokens — mirrors RagDocumentRepository. */
private val FOLLOW_UP_TOKENS = setOf(
    "also", "additionally", "and", "plus", "further", "another", "else", "more",
)
