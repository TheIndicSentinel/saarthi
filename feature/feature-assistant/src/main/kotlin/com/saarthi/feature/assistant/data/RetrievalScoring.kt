package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity

/**
 * Phase 0.1 — separate structural anchor confidence from BM25 organic scores.
 * [RetrievedChunk.score] is always the organic BM25/rerank score (0 when anchor-only).
 * [structuralAnchor] marks span/heading/tabular injections for collapse ordering.
 */

/** Floor for sort allocation only — below [STRONG_RAG_MATCH_SCORE], above zero-padding. */
internal const val STRUCTURAL_ANCHOR_RANK_FLOOR = 2.5

/** Phase 0.1 — structural anchor tag for ranking/collapse (not a BM25 score). */
enum class StructuralAnchorKind {
    CHAPTER_SPAN,
    HEADING,
    SECTION,
    BODY_CHAPTER,
    TOPIC,
    TABULAR,
    STRUCTURE_LIST,
    TABULAR_CONTRACT,
    NEIGHBOR_EXPAND,
    HIERARCHICAL_SECTION,
    STRUCTURE_HINT,
    RETRIEVAL_HINT,
}

internal fun RetrievedChunk.isStructuralAnchor(): Boolean = structuralAnchor != null

/** Sort/interleave score — never conflates anchor injection with strong BM25 hits. */
internal fun RetrievedChunk.rankingScore(): Double =
    if (structuralAnchor != null) {
        maxOf(score, STRUCTURAL_ANCHOR_RANK_FLOOR)
    } else {
        score
    }

internal fun topOrganicRetrievalScore(retrieved: List<RetrievedChunk>): Double =
    retrieved.filter { it.chunkIndex >= 0 && !it.isStructuralAnchor() }
        .maxOfOrNull { it.score }
        ?: retrieved.filter { it.chunkIndex >= 0 }.maxOfOrNull { it.score }
        ?: 0.0

internal fun significantQueryTermsForRetrieval(query: String): Set<String> =
    significantTokensForClaimOverlap(query)

internal fun chunkSharesQueryTerms(
    chunk: RetrievedChunk,
    queryTerms: Set<String>,
    minShared: Int = 2,
): Boolean {
    if (queryTerms.isEmpty()) return false
    val chunkTokens = significantTokensForClaimOverlap(chunk.text)
    val shared = sharedSignificantTokenCount(queryTerms, chunkTokens)
    if (shared >= minShared) return true
    // One long shared token (e.g. obligations, penalties) is enough for retrieval gating.
    return shared == 1 && queryTerms.any { term ->
        term.length >= 8 && term in chunkTokens
    }
}

/**
 * Lexical strong match: organic BM25 ≥ threshold, or meaningful query-token overlap
 * in body chunks — not structural-anchor score alone.
 */
internal fun hasStrongLexicalRetrievalHit(
    query: String,
    retrieved: List<RetrievedChunk>,
): Boolean {
    val body = citableRetrievalChunks(retrieved, query).filter { it.chunkIndex >= 0 }
    if (body.isEmpty()) return false
    if (body.any { it.score >= STRONG_RAG_MATCH_SCORE }) return true
    val terms = significantQueryTermsForRetrieval(query)
    if (terms.isEmpty()) return false
    return body.any { chunkSharesQueryTerms(it, terms) }
}

internal fun hasPositiveBodyRetrievalHit(hits: List<RetrievedChunk>): Boolean =
    hits.any { it.chunkIndex >= 0 && (it.score > 0.0 || it.isStructuralAnchor()) }

internal fun RagChunkEntity.toRetrievedChunk(
    organicScore: Double,
    structuralAnchor: StructuralAnchorKind? = null,
) = RetrievedChunk(
    text = text,
    docName = docName,
    score = organicScore,
    chunkIndex = chunkIndex,
    docUri = docUri,
    structuralAnchor = structuralAnchor,
)
