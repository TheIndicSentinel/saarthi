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
    val attachmentUris: List<String> = emptyList(),
    val activeDocUri: String? = null,
)

internal data class GoldenPromptMetrics(
    val retrieved: List<RetrievedChunk>,
    val turnMode: RagTurnMode,
    val ragChars: Int,
    val chunkCount: Int,
    val anchoredChunkCount: Int,
    val shouldCite: Boolean,
    val strongMatch: Boolean,
    val retrievalScopeLabel: String,
    val restrictDocUris: Set<String>,
    val routeEqualSlots: Boolean,
    val answerShape: RagAnswerShape,
    val unattachedExternalActive: Boolean,
)

internal data class GoldenRetrieveResult(
    val retrieved: List<RetrievedChunk>,
    val scopeLabel: String,
    val restrictDocUris: Set<String>,
    val routeEqualSlots: Boolean,
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
    activeDocUri: String? = null,
    attachmentUris: List<String> = emptyList(),
): GoldenRetrieveResult {
    val all = entities
    val rawContent = all.filter { it.chunkIndex >= 0 }
    if (rawContent.isEmpty()) {
        return GoldenRetrieveResult(
            retrieved = emptyList(),
            scopeLabel = RetrievalScope.SESSION.name,
            restrictDocUris = emptySet(),
            routeEqualSlots = false,
        )
    }

    val priorForCarry = priorQuery?.takeIf { shouldPassPriorQueryToRetrieval(query, it) }
    val routingQuery = followUpScopeRoutingQuery(query, priorForCarry)
    val route = routeQuery(routingQuery, sessionFiles)
    val recencyUri = rawContent.maxByOrNull { it.id }?.docUri.orEmpty()
    val effectiveAttachmentUris = when {
        attachmentsThisTurn -> attachmentUris.ifEmpty { boostDocUris.toList() }
        else -> emptyList()
    }
    val scopeDecision = resolveRetrievalScope(
        query = routingQuery,
        sessionDocs = sessionFiles,
        attachmentUris = effectiveAttachmentUris,
        activeDocUri = activeDocUri,
        route = route,
        recencyDocUri = recencyUri.takeIf { it.isNotEmpty() },
    )
    val scopedAll = if (scopeDecision.restrictUris.isNotEmpty()) {
        all.filter { it.docUri in scopeDecision.restrictUris }
    } else {
        all
    }
    val scopedRawContent = scopedAll.filter { it.chunkIndex >= 0 }
    if (scopedRawContent.isEmpty()) {
        return GoldenRetrieveResult(
            retrieved = emptyList(),
            scopeLabel = scopeDecision.scope.name,
            restrictDocUris = scopeDecision.restrictUris,
            routeEqualSlots = route.equalSlots,
        )
    }

    val isFollowUp = shouldMergePriorQueryInSearch(query, priorQuery)
    val metaReason = effectiveMetaRouteReason(query, isFollowUp)
    val spanPreserving = isSpanPreservingQuery(query)
    val shape = detectRagAnswerShape(query, metaOverview = metaReason != null)
    val effectiveTopK = effectiveRetrievalTopK(query, shape, route.equalSlots)
    val docRoles = documentRolesByUri(scopedAll)

    if (metaReason != null && !isFollowUp && !bypassMetaForSubstanceQuery(query)) {
        val opening = scopedRawContent.groupBy { it.docUri }.flatMap { (_, chunks) ->
            chunks.sortedBy { it.chunkIndex }.take(6).map { e ->
                RetrievedChunk(e.text, e.docName, 0.0, e.chunkIndex, e.docUri)
            }
        }.take(effectiveTopK)
        return GoldenRetrieveResult(
            retrieved = finishGoldenRetrieve(
                opening,
                query,
                scopedRawContent,
                effectiveTopK,
                boostDocUris,
                route,
                recencyUri,
                spanPreserving,
            ),
            scopeLabel = scopeDecision.scope.name,
            restrictDocUris = scopeDecision.restrictUris,
            routeEqualSlots = route.equalSlots,
        )
    }

    val contentChunks = filterSubstanceContentChunks(
        scopedRawContent,
        docRoles,
        query,
        route,
        isFollowUp,
    )
    if (contentChunks.isEmpty()) {
        return GoldenRetrieveResult(
            retrieved = emptyList(),
            scopeLabel = scopeDecision.scope.name,
            restrictDocUris = scopeDecision.restrictUris,
            routeEqualSlots = route.equalSlots,
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

    val focusEntities = extractQueryFocusEntities(query)
    if (shouldRunAnswerabilityRetrievalRetry(
            query = query,
            ranked = finalRanked,
            pool = contentChunks,
            anchoredEntities = anchoredEntities,
            priorQuery = priorQuery,
        )
    ) {
        val focusExpansion = answerabilityQueryExpansion(focusEntities, priorQuery)
        val answerabilityQuery = "$effectiveQuery $focusExpansion"
        val retryCandidates = Bm25Retriever.rankTokenised(tokenised, answerabilityQuery, candidateK)
        val answerabilityRanked = filterRankedByScoreGap(
            featureRerankBm25Candidates(retryCandidates, contentChunks, query, rerankCtx),
            effectiveTopK,
        )
        finalRanked = mergeRankedBm25Results(finalRanked, answerabilityRanked, effectiveTopK)
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

    return GoldenRetrieveResult(
        retrieved = finishGoldenRetrieve(
            hits,
            query,
            contentChunks,
            effectiveTopK,
            boostDocUris,
            route,
            recencyUri,
            spanPreserving,
        ),
        scopeLabel = scopeDecision.scope.name,
        restrictDocUris = scopeDecision.restrictUris,
        routeEqualSlots = route.equalSlots,
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
    val retrievedResult = goldenSessionRetrieve(
        query = spec.query,
        entities = entities,
        sessionFiles = sessionFiles,
        priorQuery = spec.priorQuery,
        boostDocUris = spec.boostDocUris,
        attachmentsThisTurn = spec.attachmentsThisTurn,
        activeDocUri = spec.activeDocUri,
        attachmentUris = spec.attachmentUris,
    )
    val retrieved = retrievedResult.retrieved
    val metaOverview = effectiveMetaRouteReason(spec.query, isFollowUp = false) != null
    val shape = detectRagAnswerShape(spec.query, metaOverview = metaOverview)
    val unattachedExternal = detectUnattachedExternalQuery(spec.query, docs.map { it.name })
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
        retrievalScopeLabel = retrievedResult.scopeLabel,
        restrictDocUris = retrievedResult.restrictDocUris,
        routeEqualSlots = retrievedResult.routeEqualSlots,
        answerShape = shape,
        unattachedExternalActive = unattachedExternal.active,
    )
}

internal fun goldenAnswerShapeInstruction(
    query: String,
    docNames: List<String>,
    retrieved: List<RetrievedChunk>,
    turnMode: RagTurnMode,
    attachmentsThisTurn: Boolean,
): String {
    val metaOverview = effectiveMetaRouteReason(query, isFollowUp = false) != null
    val shape = detectRagAnswerShape(query, metaOverview = metaOverview)
    val strongMatch = shouldUseStrongMatchPromptRules(
        retrieved = retrieved,
        query = query,
        turnMode = turnMode,
        attachmentsThisTurn = attachmentsThisTurn,
    )
    val unattachedExternal = detectUnattachedExternalQuery(query, docNames)
    return ragAnswerShapeInstruction(
        shape = shape,
        tabularAmount = isTabularAmountQuery(query),
        unattachedExternal = unattachedExternal,
        strongMatch = strongMatch,
        structureCountQuery = isStructureCountQuery(query),
        structureListQuery = isStructureListQuery(query),
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
