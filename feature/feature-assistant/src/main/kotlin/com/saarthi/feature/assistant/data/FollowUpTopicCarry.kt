package com.saarthi.feature.assistant.data

/**
 * Phase 5.1 — follow-up topic carry: merge prior topical intent with the
 * current continuation for retrieval routing and BM25, without forcing RAG
 * on general chat.
 */
internal const val FOLLOW_UP_PRIOR_QUERY_MAX_CHARS = 150

internal fun mergeFollowUpRetrievalQuery(
    priorQuery: String,
    currentQuery: String,
    maxPriorChars: Int = FOLLOW_UP_PRIOR_QUERY_MAX_CHARS,
): String {
    val prior = priorQuery.trim().take(maxPriorChars)
    val current = currentQuery.trim()
    return when {
        prior.isEmpty() -> current
        current.isEmpty() -> prior
        else -> "$prior $current"
    }
}

/**
 * Whether [priorQuery] should be passed into [RagDocumentRepository.search] for
 * BM25 merge / meta bypass on this turn.
 */
internal fun shouldPassPriorQueryToRetrieval(query: String, priorQuery: String?): Boolean {
    if (priorQuery.isNullOrBlank()) return false
    val prior = priorQuery.trim()
    if (prior.length < 8) return false
    if (prior.equals(query.trim(), ignoreCase = true)) return false
    return isFollowUpContinuationQuery(query) || isFollowUpTopicCarry(query, prior)
}

/** Repository-side merge when the caller passed a gated [priorQuery]. */
internal fun shouldMergePriorQueryInSearch(query: String, priorQuery: String?): Boolean {
    if (priorQuery.isNullOrBlank()) return false
    return shouldPassPriorQueryToRetrieval(query, priorQuery)
}

/**
 * Follow-up adds explicit doc structure (section/chapter) after a topical prior
 * that lacked those cues — upgrade to document-grounded retrieval.
 */
internal fun isFollowUpScopeUpgrade(query: String, priorQuery: String): Boolean {
    if (!isFollowUpContinuationQuery(query)) return false
    val queryRefs = extractSectionRefs(query)
    if (queryRefs.isEmpty()) return false
    return extractSectionRefs(priorQuery).isEmpty()
}

/** Merged query for filename / scope routing on carry turns. */
internal fun followUpScopeRoutingQuery(query: String, priorQuery: String?): String {
    if (!shouldPassPriorQueryToRetrieval(query, priorQuery)) return query
    return mergeFollowUpRetrievalQuery(priorQuery!!, query)
}
