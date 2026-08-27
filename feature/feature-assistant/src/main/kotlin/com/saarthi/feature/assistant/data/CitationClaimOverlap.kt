package com.saarthi.feature.assistant.data

/**
 * Wave 3 P13 — heuristic claim overlap: drop Sources lines whose chunk text
 * does not share meaningful tokens with the model answer body.
 */
internal const val CLAIM_OVERLAP_MIN_SHARED_TOKENS = 2

private val CLAIM_OVERLAP_STOPWORDS = setOf(
    "this", "that", "with", "from", "have", "will", "your", "what", "when",
    "which", "their", "there", "about", "would", "could", "should", "also",
    "into", "than", "then", "them", "they", "been", "being", "were", "was",
    "are", "for", "and", "the", "not", "can", "may", "any", "all", "each",
    "such", "under", "only", "other", "some", "more", "most", "very", "just",
    "like", "over", "after", "before", "between", "through", "during", "without",
    "within", "along", "following", "according", "provided", "mentioned",
    "document", "documents", "attached", "file", "files", "sources", "source",
    "saarthi", "overview", "excerpt", "excerpts",
)

/** Structure / overview answers often paraphrase without lexical overlap with hints. */
internal fun shouldFilterSourcesByClaimOverlap(
    query: String?,
    turnMode: RagTurnMode?,
): Boolean {
    if (query.isNullOrBlank()) return true
    if (turnMode == RagTurnMode.GENERAL_KNOWLEDGE || turnMode == RagTurnMode.PLAIN_CHAT) return false
    if (isStructureCountQuery(query) || isStructureListQuery(query)) return false
    if (isDocumentMetaOverviewQuery(query)) return false
  if (turnMode == RagTurnMode.MIXED && !hasDocumentQueryCues(query)) return false
    return true
}

internal fun significantTokensForClaimOverlap(text: String): Set<String> =
    text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { token ->
            when {
                token.isEmpty() -> false
                token in CLAIM_OVERLAP_STOPWORDS -> false
                token.all { it.isDigit() } -> token.length >= 2
                token.any { it > '\u007f' } -> token.length >= 2
                else -> token.length >= 4
            }
        }
        .toSet()

internal fun sharedSignificantTokenCount(left: Set<String>, right: Set<String>): Int =
    left.count { it in right }

internal fun chunkSharesTokensWithAnswer(
    chunk: RetrievedChunk,
    answerBody: String,
    minShared: Int = CLAIM_OVERLAP_MIN_SHARED_TOKENS,
): Boolean {
    if (chunk.chunkIndex < 0) return true
    val answerTokens = significantTokensForClaimOverlap(answerBody)
    if (answerTokens.isEmpty()) return false
    val chunkTokens = significantTokensForClaimOverlap(chunk.text)
    return sharedSignificantTokenCount(answerTokens, chunkTokens) >= minShared
}

internal fun filterChunksByClaimOverlap(
    chunks: List<RetrievedChunk>,
    answerBody: String,
    minShared: Int = CLAIM_OVERLAP_MIN_SHARED_TOKENS,
): List<RetrievedChunk> {
    if (chunks.isEmpty() || answerBody.isBlank()) return chunks
    return chunks.filter { chunkSharesTokensWithAnswer(it, answerBody, minShared) }
}
