package com.saarthi.feature.assistant.data

/**
 * Wave 6 P26 — domain-agnostic query rewrite lexicon applied before BM25
 * (complements Indic/romanized hints in [expandRetrievalQuery]; embeddings last).
 */

internal data class QueryRewriteRule(
    val id: String,
    val pattern: Regex,
    val terms: List<String>,
)

/** Single-token triggers → synonymous document vocabulary. */
private val QUERY_REWRITE_TOKEN_LEXICON: Map<String, List<String>> = mapOf(
    "appeal" to listOf("appeal", "review", "adjudication", "grievance"),
    "grievance" to listOf("grievance", "complaint", "appeal"),
    "complaint" to listOf("complaint", "grievance", "appeal"),
    "consent" to listOf("consent", "notice", "purpose", "verifiable"),
    "permission" to listOf("consent", "notice", "purpose"),
    "breach" to listOf("breach", "violation", "penalty", "contravention"),
    "violation" to listOf("violation", "breach", "penalty"),
    "privacy" to listOf("privacy", "personal", "data", "protection"),
    "confidential" to listOf("confidential", "disclosure", "secrecy"),
    "termination" to listOf("termination", "cancellation", "rescission"),
    "cancellation" to listOf("cancellation", "termination", "refund"),
    "eligibility" to listOf("eligibility", "qualification", "disqualification"),
    "nomination" to listOf("nomination", "nominee", "nominated"),
    "nominee" to listOf("nominee", "nomination", "exercise", "rights"),
    "correction" to listOf("correction", "erasure", "accuracy", "completeness"),
    "erasure" to listOf("erasure", "deletion", "correction", "erase"),
    "deletion" to listOf("deletion", "erasure", "correction"),
    "duties" to listOf("duties", "obligations", "fiduciary", "principal"),
    "duty" to listOf("duty", "duties", "obligations", "fiduciary"),
    "obligations" to listOf("obligations", "duties", "fiduciary", "shall"),
    "fiduciary" to listOf("fiduciary", "obligations", "duties", "safeguards"),
    "research" to listOf("research", "archiving", "statistical", "exempt"),
    "transfer" to listOf("transfer", "cross-border", "notified", "countries"),
    "overseas" to listOf("overseas", "foreign", "cross-border", "transfer"),
    "children" to listOf("children", "child", "parental", "consent"),
    "child" to listOf("child", "children", "parental", "verifiable"),
    "penalty" to listOf("penalty", "penalties", "fine", "monetary", "adjudication"),
    "penalties" to listOf("penalties", "penalty", "fine", "schedule", "monetary"),
    "fine" to listOf("fine", "penalty", "monetary", "jurmana"),
    "schedule" to listOf("schedule", "monetary", "penalty", "amount"),
    "salary" to listOf("salary", "credit", "payment", "amount"),
    "payment" to listOf("payment", "amount", "credit", "debit"),
    "interest" to listOf("interest", "rate", "percent", "annual"),
    "term" to listOf("term", "duration", "months", "effective"),
    "clause" to listOf("clause", "section", "article", "provision"),
    "provision" to listOf("provision", "section", "clause", "special"),
)

/** Multi-word / colloquial patterns not covered by single-token map. */
private val QUERY_REWRITE_PATTERN_RULES = listOf(
    QueryRewriteRule(
        id = "data_handler",
        pattern = Regex("(?i)\\b(data\\s+boss|data\\s+owner|data\\s+handler|data\\s+controller)\\b"),
        terms = listOf("Data", "Fiduciary", "obligations", "safeguards"),
    ),
    QueryRewriteRule(
        id = "my_data_rights",
        pattern = Regex("(?i)\\b(my\\s+data\\s+rights|user\\s+rights|customer\\s+rights)\\b"),
        terms = listOf("Data", "Principal", "rights", "access", "correction", "erasure"),
    ),
    QueryRewriteRule(
        id = "special_rules",
        pattern = Regex("(?i)\\b(special\\s+case|special\\s+rules?|exceptions?)\\b"),
        terms = listOf("special", "provisions", "exempt", "notified"),
    ),
    QueryRewriteRule(
        id = "government_processing",
        pattern = Regex("(?i)\\b(government\\s+use|state\\s+processing|public\\s+authority)\\b"),
        terms = listOf("State", "instrumentalities", "notified", "purposes"),
    ),
)

private val LEXICON_QUERY_SPLIT = Regex("[^\\p{L}\\p{N}']+")

internal fun activeQueryRewriteRuleIds(query: String): List<String> {
    val ids = mutableListOf<String>()
    val lower = query.lowercase()
    val tokens = lower.split(LEXICON_QUERY_SPLIT).filter { it.isNotEmpty() }
    for ((token, _) in QUERY_REWRITE_TOKEN_LEXICON) {
        if (tokens.contains(token)) ids.add("token:$token")
    }
    for (rule in QUERY_REWRITE_PATTERN_RULES) {
        if (rule.pattern.containsMatchIn(query)) ids.add(rule.id)
    }
    return ids
}

/** Extra BM25 terms to append before ranking; empty when nothing matches. */
internal fun queryRewriteLexiconExpansion(query: String): String {
    val lower = query.lowercase().trim()
    if (lower.isEmpty()) return ""
    val tokens = lower.split(LEXICON_QUERY_SPLIT).filter { it.isNotEmpty() }
    val extra = LinkedHashSet<String>()
    for (token in tokens) {
        QUERY_REWRITE_TOKEN_LEXICON[token]?.let { extra.addAll(it) }
    }
    for (rule in QUERY_REWRITE_PATTERN_RULES) {
        if (rule.pattern.containsMatchIn(query)) {
            extra.addAll(rule.terms)
        }
    }
    extra.removeAll { it.length < 4 && it.all { c -> c.code < 128 } }
    if (extra.isEmpty()) return ""
    return extra.joinToString(" ")
}
