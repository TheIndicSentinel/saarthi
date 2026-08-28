package com.saarthi.feature.assistant.data

/**
 * Phase 1 — pre-generation evidence gate, low-confidence suppression, and
 * deterministic miss responses for explicit lookups.
 */
internal fun isExplicitLookupQuery(query: String): Boolean {
    if (extractSectionRefs(query).isNotEmpty()) return true
    if (isChapterTypedQuery(query)) return true
    if (isTabularAmountQuery(query)) return true
    if (isStructureListQuery(query)) return true
    return EXPLICIT_LOOKUP_PATTERN.containsMatchIn(query)
}

private val EXPLICIT_LOOKUP_PATTERN = Regex(
    "(?i)\\b(" +
        "address|email|phone|mobile|account number|ifsc|pan|gstin|" +
        "salary|balance|total|amount|fee|fine|penalt|invoice|transaction|" +
        "date of|deadline|timeline|notification period|within \\d+ days" +
        ")\\b",
)

/** Top hits are structural anchors without meaningful lexical overlap or BM25 body strength. */
internal fun isLowConfidenceAnchorOnlyRetrieval(
    query: String,
    retrieved: List<RetrievedChunk>,
): Boolean {
    if (hasStrongLexicalRetrievalHit(query, retrieved)) return false
    val organicBody = retrieved.filter { it.chunkIndex >= 0 && !it.isStructuralAnchor() && it.score > 0.0 }
    if (organicBody.any { it.score >= STRONG_RAG_MATCH_SCORE }) return false
    return retrieved.any { it.isStructuralAnchor() }
}

internal fun shouldEmitDeterministicRetrievalMiss(
    query: String,
    turnMode: RagTurnMode,
    retrieved: List<RetrievedChunk>,
): Boolean {
    if (!shouldRetrieveForRagTurnMode(turnMode)) return false
    if (!isExplicitLookupQuery(query)) return false
    if (isDocumentMetaOverviewQuery(query)) return false
    if (isStructureCountQuery(query)) return false
    if (retrieved.isEmpty()) return true
    if (hasStrongLexicalRetrievalHit(query, retrieved)) return false
  return isLowConfidenceAnchorOnlyRetrieval(query, retrieved) ||
        !hasPositiveBodyRetrievalHit(retrieved.filter { !it.isStructuralAnchor() })
}

internal fun buildDeterministicRetrievalMissMessage(query: String): String =
    "I couldn't find clear support for that in the attached document(s). " +
        "If you know the chapter, section, or page, try asking with that detail."
