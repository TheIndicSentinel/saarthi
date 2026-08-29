package com.saarthi.feature.assistant.data

/**
 * Phase 2.1 — citation ↔ claim pairing: each Sources line must share meaningful
 * tokens with the answer span (document block only in MIXED). Outline chunks
 * only bypass overlap on structure/overview queries.
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

private val MIXED_GENERAL_BLOCK = Regex("(?im)^\\s*General:\\s*")
private val MIXED_FROM_DOCUMENT = Regex("(?im)From document:\\s*")

/** Structure / overview answers often paraphrase without lexical overlap with hints. */
internal fun shouldFilterSourcesByClaimOverlap(
    query: String?,
    turnMode: RagTurnMode?,
): Boolean {
    if (turnMode == null || query.isNullOrBlank()) return false
    if (turnMode == RagTurnMode.GENERAL_KNOWLEDGE || turnMode == RagTurnMode.PLAIN_CHAT) return false
    if (isStructureCountQuery(query) || isStructureListQuery(query)) return false
    if (isDocumentMetaOverviewQuery(query)) return false
    // Phase C — shape routes paraphrase; pairing would drop all Sources lines.
    if (isInDocConceptComparisonQuery(query)) return false
    if (isSetEnumerationQuery(query)) return false
    if (isAbsenceInventoryQuery(query)) return false
    if (turnMode == RagTurnMode.MIXED && !hasDocumentQueryCues(query)) return false
    return true
}

internal fun claimOverlapPairingCorpus(answerBody: String, query: String?): String {
    val body = answerBody.trim()
    val q = query?.trim().orEmpty()
    return when {
        body.isEmpty() -> q
        q.isEmpty() -> body
        else -> "$body $q"
    }
}

internal fun effectiveClaimOverlapMinShared(
    chunk: RetrievedChunk,
    turnMode: RagTurnMode?,
): Int {
    if (turnMode == RagTurnMode.DOCUMENT_GROUNDED && chunk.score >= STRONG_RAG_MATCH_SCORE) {
        return 1
    }
    if (chunk.isStructuralAnchor()) return 1
    return CLAIM_OVERLAP_MIN_SHARED_TOKENS
}

internal fun outlineChunkExemptFromClaimOverlap(query: String?): Boolean {
    if (query.isNullOrBlank()) return false
    if (isStructureCountQuery(query) || isStructureListQuery(query)) return true
    if (isDocumentMetaOverviewQuery(query)) return true
    return false
}

/** MIXED answers pair citations only against the document slice, not General knowledge. */
internal fun answerBodyForClaimOverlap(body: String, turnMode: RagTurnMode?): String {
    if (turnMode != RagTurnMode.MIXED) return body.trim()
    val fromMatch = MIXED_FROM_DOCUMENT.find(body)
    if (fromMatch == null) {
        return if (MIXED_GENERAL_BLOCK.find(body) != null) "" else body.trim()
    }
    val start = fromMatch.range.last + 1
    val generalMatch = MIXED_GENERAL_BLOCK.find(body, start)
    val end = generalMatch?.range?.first ?: body.length
    return body.substring(start, end).trim()
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
    query: String? = null,
): Boolean {
    if (chunk.chunkIndex < 0 && outlineChunkExemptFromClaimOverlap(query)) return true
    return chunkSharesLexicalOverlapWithAnswerBody(chunk, answerBody, minShared)
}

private fun chunkSharesLexicalOverlapWithAnswerBody(
    chunk: RetrievedChunk,
    answerBody: String,
    minShared: Int,
): Boolean {
    val answerTokens = significantTokensForClaimOverlap(answerBody)
    if (answerTokens.isEmpty()) return false
    val chunkTokens = significantTokensForClaimOverlap(chunk.text)
    return sharedSignificantTokenCount(answerTokens, chunkTokens) >= minShared
}

internal fun filterChunksByClaimOverlap(
    chunks: List<RetrievedChunk>,
    answerBody: String,
    minShared: Int = CLAIM_OVERLAP_MIN_SHARED_TOKENS,
    query: String? = null,
    turnMode: RagTurnMode? = null,
): List<RetrievedChunk> {
    if (chunks.isEmpty()) return chunks
    val pairingCorpus = claimOverlapPairingCorpus(answerBody, query)
    if (pairingCorpus.isBlank()) {
        return if (outlineChunkExemptFromClaimOverlap(query)) {
            chunks.filter { it.chunkIndex < 0 }
        } else {
            emptyList()
        }
    }
    return chunks.filter { chunk ->
        val min = effectiveClaimOverlapMinShared(chunk, turnMode).coerceAtMost(minShared)
        chunkSharesTokensWithAnswer(chunk, pairingCorpus, min, query)
    }
}
