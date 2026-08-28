package com.saarthi.feature.assistant.data

import com.saarthi.core.rag.Bm25Retriever

/**
 * Wave 6 P25 — paraphrase miss gate: when BM25 + rerank scores stay weak and no
 * anchors fired, retry with a small domain-agnostic lexicon (embeddings deferred).
 */

internal data class ParaphraseRule(
    val id: String,
    val pattern: Regex,
    val expansion: String,
)

/** High-precision paraphrase → statutory vocabulary (legal/admin docs). */
private val PARAPHRASE_RULES = listOf(
    ParaphraseRule(
        id = "data_fiduciary",
        pattern = Regex(
            "(?i)\\b(" +
                "data\\s+boss|data\\s+owner|data\\s+handler|data\\s+controller|" +
                "who\\s+handles\\s+(my\\s+)?data|company\\s+data\\s+duties" +
                ")\\b",
        ),
        expansion = " Data Fiduciary obligations duties safeguards accuracy completeness",
    ),
    ParaphraseRule(
        id = "data_principal_rights",
        pattern = Regex(
            "(?i)\\b(" +
                "my\\s+data\\s+rights|user\\s+rights|customer\\s+rights|" +
                "what\\s+can\\s+i\\s+ask\\s+about\\s+my\\s+data" +
                ")\\b",
        ),
        expansion = " Data Principal rights access correction erasure nomination",
    ),
    ParaphraseRule(
        id = "children_data",
        pattern = Regex(
            "(?i)\\b(" +
                "kids?\\s+data|children\\s+data|child\\s+privacy|parental\\s+consent" +
                ")\\b",
        ),
        expansion = " children parental consent verifiable processing monitoring",
    ),
    ParaphraseRule(
        id = "cross_border",
        pattern = Regex(
            "(?i)\\b(" +
                "foreign\\s+transfer|outside\\s+india|cross\\s+border|overseas\\s+data" +
                ")\\b",
        ),
        expansion = " cross-border transfer notified countries Central Government",
    ),
)

internal fun activeParaphraseRuleIds(query: String): List<String> =
    activeParaphraseRules(query).map { it.id }

internal fun activeParaphraseRules(query: String): List<ParaphraseRule> =
    PARAPHRASE_RULES.filter { it.pattern.containsMatchIn(query) }

/** BM25 expansion terms for paraphrase retry — empty when no rule matches. */
internal fun paraphraseQueryExpansion(query: String): String {
    val rules = activeParaphraseRules(query)
    if (rules.isEmpty()) return ""
    return rules.map { it.expansion.trim() }.distinct().joinToString(" ")
}

/**
 * Retry BM25 when the first pass is weak and structure anchors did not fire.
 * Skips when top organic score already clears [STRONG_RAG_MATCH_SCORE].
 */
internal fun shouldRunParaphraseRetrievalRetry(
    topOrganicScore: Double,
    hasAnchoredHits: Boolean,
    paraphraseExpansion: String,
): Boolean {
    if (paraphraseExpansion.isBlank()) return false
    if (hasAnchoredHits) return false
    if (topOrganicScore >= STRONG_RAG_MATCH_SCORE) return false
    return true
}

/** Merge two BM25 ranked lists; keep best score per chunk index. */
internal fun mergeRankedBm25Results(
    primary: List<Bm25Retriever.Scored>,
    secondary: List<Bm25Retriever.Scored>,
    maxKeep: Int,
): List<Bm25Retriever.Scored> {
    if (secondary.isEmpty()) return primary
    val byIndex = LinkedHashMap<Int, Bm25Retriever.Scored>()
    for (scored in primary) {
        byIndex[scored.index] = scored
    }
    for (scored in secondary) {
        val existing = byIndex[scored.index]
        if (existing == null || scored.score > existing.score) {
            byIndex[scored.index] = scored
        }
    }
    return byIndex.values.sortedByDescending { it.score }.take(maxKeep.coerceAtLeast(1))
}
