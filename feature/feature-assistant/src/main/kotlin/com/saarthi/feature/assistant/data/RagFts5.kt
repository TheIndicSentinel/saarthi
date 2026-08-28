package com.saarthi.feature.assistant.data

import com.saarthi.core.rag.Bm25Retriever

/** Max query terms in an FTS5 OR-match (P3 — keeps MATCH clauses bounded). */
internal const val FTS5_MATCH_TERM_CAP = 12

/** FTS candidate pool vs BM25 rankK — recall headroom before score-gap trim. */
internal const val FTS5_CANDIDATE_MULTIPLIER = 3

/**
 * Build an FTS5 MATCH expression: OR of tokenised query terms (BM25-aligned
 * tokenisation). Returns null when the query has no searchable terms.
 */
internal fun buildFtsMatchQuery(query: String): String? {
    val tokens = Bm25Retriever.tokeniseDocument(query).take(FTS5_MATCH_TERM_CAP)
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" OR ") { it.replace("'", "''") }
}

/** Use FTS prefilter when the session corpus is huge — not for a typical single act. */
internal const val FTS5_TYPICAL_DOC_MAX_CHUNKS = 200

/**
 * Wave 4 P21 — FTS5 is a scale path for 500+ chunks, not hybrid search for a
 * typical attached PDF. Fast-path only applies once the corpus exceeds a single
 * large document.
 */
internal fun shouldUseFtsPrefilter(chunkCount: Int, sessionFastPath: Boolean): Boolean =
    when {
        chunkCount > FTS5_CHUNK_THRESHOLD -> true
        sessionFastPath && chunkCount > FTS5_TYPICAL_DOC_MAX_CHUNKS -> true
        else -> false
    }
