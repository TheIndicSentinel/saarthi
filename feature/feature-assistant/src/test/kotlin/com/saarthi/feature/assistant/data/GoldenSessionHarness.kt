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
        val indexedChunks = chunkDocumentForIndexing(doc.text, mimeType = "application/pdf")
        if (indexedChunks.isEmpty()) continue
        val chunks = indexedChunks.map { it.text }
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
        indexedChunks.forEachIndexed { idx, indexed ->
            val meta = metadata[idx]
            entities.add(
                RagChunkEntity(
                    id = id++,
                    sessionId = sessionId,
                    docUri = doc.uri,
                    docName = doc.name,
                    mimeType = "application/pdf",
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
    val rawContent = all.filter { it.chunkIndex >= 0 }
    if (rawContent.isEmpty()) return emptyList()

    val priorForCarry = priorQuery?.takeIf { shouldPassPriorQueryToRetrieval(query, it) }
    val routingQuery = followUpScopeRoutingQuery(query, priorForCarry)
    val route = routeQuery(routingQuery, sessionFiles)
    val isFollowUp = shouldMergePriorQueryInSearch(query, priorQuery)
    val metaReason = effectiveMetaRouteReason(query, isFollowUp)
    val spanPreserving = isSpanPreservingQuery(query)
    val shape = detectRagAnswerShape(query, metaOverview = metaReason != null)
    val effectiveTopK = effectiveRetrievalTopK(query, shape, route.equalSlots)
    val recencyUri = rawContent.maxByOrNull { it.id }?.docUri.orEmpty()
    val docRoles = documentRolesByUri(all)

    if (metaReason != null && !isFollowUp && !bypassMetaForSubstanceQuery(query)) {
        val opening = rawContent.groupBy { it.docUri }.flatMap { (_, chunks) ->
            chunks.sortedBy { it.chunkIndex }.take(6).map { e ->
                RetrievedChunk(e.text, e.docName, 0.0, e.chunkIndex, e.docUri)
            }
        }.take(effectiveTopK)
        return finishGoldenRetrieve(
            opening,
            query,
            rawContent,
            effectiveTopK,
            boostDocUris,
            route,
            recencyUri,
            spanPreserving,
        )
    }

    val contentChunks = filterSubstanceContentChunks(
        rawContent,
        docRoles,
        query,
        route,
        isFollowUp,
    )
    if (contentChunks.isEmpty()) return emptyList()

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
    val anchorKinds = LinkedHashMap<Long, StructuralAnchorKind>()
    chapterSpanChunks.forEach { anchorKinds[it.id] = StructuralAnchorKind.CHAPTER_SPAN }
    topicAnchored.forEach { anchorKinds[it.id] = StructuralAnchorKind.TOPIC }
    tabularContract.forEach { anchorKinds[it.id] = StructuralAnchorKind.TABULAR_CONTRACT }
    val anchoredEntities = chapterSpanChunks + topicAnchored + tabularContract

    var effectiveQuery = if (isFollowUp && !priorQuery.isNullOrBlank()) {
        mergeFollowUpRetrievalQuery(priorQuery!!, route.expandedQuery)
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
    var finalRanked = ranked
    val paraphraseExpansion = paraphraseQueryExpansion(query)
    if (shouldRunParaphraseRetrievalRetry(
            topOrganicScore = ranked.firstOrNull()?.score ?: 0.0,
            hasAnchoredHits = anchoredEntities.isNotEmpty(),
            paraphraseExpansion = paraphraseExpansion,
        )
    ) {
        val paraphraseQuery = "$effectiveQuery $paraphraseExpansion"
        val retryCandidates = Bm25Retriever.rankTokenised(tokenised, paraphraseQuery, candidateK)
        val paraphraseRanked = filterRankedByScoreGap(
            featureRerankBm25Candidates(retryCandidates, contentChunks, query, rerankCtx),
            effectiveTopK,
        )
        finalRanked = mergeRankedBm25Results(ranked, paraphraseRanked, effectiveTopK)
    }

    val docChunksByUri = contentChunks.groupBy { it.docUri }.mapValues { (_, list) -> list.sortedBy { it.chunkIndex } }
    val orderedIdsByDoc = docChunksByUri.mapValues { (_, list) -> list.map { it.id } }
    val sectionGroupsByDoc = buildSectionGroupsByDoc(contentChunks)
    val usedIds = LinkedHashSet<Long>()
    val hits = mutableListOf<RetrievedChunk>()
    val organicByEntityId = finalRanked.associate { contentChunks[it.index].id to it.score }

    for (e in anchoredEntities) {
        if (usedIds.add(e.id)) {
            val kind = anchorKinds[e.id] ?: StructuralAnchorKind.HEADING
            val organic = organicByEntityId[e.id] ?: 0.0
            hits.add(e.toRetrievedChunk(organic, kind))
        }
    }
    for (scored in finalRanked) {
        val entity = contentChunks[scored.index]
        if (usedIds.add(entity.id)) {
            hits.add(entity.toRetrievedChunk(scored.score))
        }
    }
    for ((entity, score) in expandRerankedNeighborHits(
        ranked = finalRanked,
        pool = contentChunks,
        docChunksByUri = docChunksByUri,
        orderedIdsByDoc = orderedIdsByDoc,
    )) {
        if (usedIds.add(entity.id)) {
            hits.add(entity.toRetrievedChunk(score, StructuralAnchorKind.NEIGHBOR_EXPAND))
        }
    }
    for ((entity, score) in expandHierarchicalSectionHits(
        ranked = finalRanked,
        pool = contentChunks,
        sectionGroupsByDoc = sectionGroupsByDoc,
        anchorSeeds = anchoredEntities,
    )) {
        if (usedIds.add(entity.id)) {
            hits.add(entity.toRetrievedChunk(score, StructuralAnchorKind.HIERARCHICAL_SECTION))
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
        sessionDocNames = docs.map { it.name },
        priorQuery = spec.priorQuery,
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
        anchoredChunkCount = retrieved.count { it.isStructuralAnchor() },
        shouldCite = shouldCite,
        strongMatch = strongMatch,
    )
}

private fun RagChunkEntity.toRetrieved(score: Double) = toRetrievedChunk(score)

/** Organic BM25/rerank primary doc — excludes structural anchor injections for BM25 parity. */
internal fun goldenPipelinePrimaryDocUri(retrieved: List<RetrievedChunk>): String? {
    val organic = retrieved.filter { it.chunkIndex >= 0 && !it.isStructuralAnchor() }
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
