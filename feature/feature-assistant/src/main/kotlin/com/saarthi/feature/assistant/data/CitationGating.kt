package com.saarthi.feature.assistant.data

/**
 * Wave 3 P11 — citation gating: deterministic Sources only when excerpts were
 * placed, the turn is document-grounded, and retrieval matches the query type.
 * Phase 2.4 — outline/system chunks gated via [SystemChunkGuardrail].
 */

internal fun isDocumentMetaOverviewQuery(query: String): Boolean {
    val q = query.trim()
    return q.equals(ATTACH_BRIEF_OVERVIEW_QUERY, ignoreCase = true) ||
        effectiveMetaRouteReason(q, isFollowUp = false) != null
}

/**
 * Pinned files ≠ this question is about the document (Wave 3 P14 preview).
 * Citations require explicit doc intent, attach turn, meta/structure route, or mixed doc slice.
 */
internal fun isQueryAboutDocumentForCitation(
    query: String,
    turnMode: RagTurnMode,
    attachmentsThisTurn: Boolean,
): Boolean {
    if (turnMode == RagTurnMode.MIXED) return true
    if (attachmentsThisTurn) return true
    if (hasDocumentQueryCues(query)) return true
    if (effectiveMetaRouteReason(query, isFollowUp = false) != null) return true
    if (isStructureCountQuery(query) || isStructureListQuery(query)) return true
    if (isFollowUpContinuationQuery(query)) return true
    return false
}

/** Chapter-typed asks must not cite when only a wrong chapter or miss hint was retrieved. */
internal fun isRetrievalOnTypeForCitation(query: String, retrieved: List<RetrievedChunk>): Boolean {
    if (!isChapterTypedQuery(query)) return true
    val chapterRef = chapterRefFromQuery(query) ?: return true
    val requestedNum = chapterNumericId(chapterRef) ?: return true
    if (retrieved.any { it.chunkIndex == RETRIEVAL_HINT_CHUNK_INDEX }) return false
    return hasRequestedChapterTitleLine(retrieved, requestedNum)
}

/**
 * Weak BM25 padding without anchors should not produce Sources; anchor hits require
 * [isQueryAboutDocumentForCitation] so off-topic queries do not cite pinned files.
 */
internal fun isRetrievalStrongEnoughForCitation(
    query: String,
    retrieved: List<RetrievedChunk>,
    turnMode: RagTurnMode,
    attachmentsThisTurn: Boolean,
): Boolean {
    if (!isQueryAboutDocumentForCitation(query, turnMode, attachmentsThisTurn)) return false

    val citable = citableRetrievalChunks(retrieved, query)
    if (citable.isEmpty()) return false

    if (isStructureCountQuery(query) || isStructureListQuery(query)) return true
    if (isDocumentMetaOverviewQuery(query)) return true

    return hasStrongLexicalRetrievalHit(query, retrieved)
}

internal fun shouldAttachDeterministicSources(
    turnMode: RagTurnMode,
    ragBlockChars: Int,
    retrieved: List<RetrievedChunk>,
    query: String,
    attachmentsThisTurn: Boolean,
): Boolean {
    if (turnMode == RagTurnMode.PLAIN_CHAT || turnMode == RagTurnMode.GENERAL_KNOWLEDGE) return false
    if (ragBlockChars <= 0) return false
    if (retrieved.isEmpty()) return false
    if (!isRetrievalOnTypeForCitation(query, retrieved)) return false
    if (!isRetrievalStrongEnoughForCitation(query, retrieved, turnMode, attachmentsThisTurn)) return false
    return citableRetrievalChunks(retrieved, query).isNotEmpty()
}

/** Organic BM25 strength or lexical overlap — structural anchor alone is not enough. */
internal fun hasHighConfidenceRetrievalHit(
    query: String,
    retrieved: List<RetrievedChunk>,
): Boolean = hasStrongLexicalRetrievalHit(query, retrieved)

/**
 * Wave 3 P14 — pinned files and anchor scores do not alone force “answer from excerpts”.
 * Strong-match prompt rules require both confident retrieval and document intent.
 */
internal fun shouldUseStrongMatchPromptRules(
    retrieved: List<RetrievedChunk>,
    query: String,
    turnMode: RagTurnMode,
    attachmentsThisTurn: Boolean,
): Boolean {
    if (turnMode == RagTurnMode.GENERAL_KNOWLEDGE || turnMode == RagTurnMode.PLAIN_CHAT) return false
    if (isDocumentOptOutQuery(query)) return false
    if (isLowConfidenceAnchorOnlyRetrieval(query, retrieved)) return false
    return hasStrongLexicalRetrievalHit(query, retrieved) &&
        isQueryAboutDocumentForCitation(query, turnMode, attachmentsThisTurn)
}
