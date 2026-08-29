package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import com.saarthi.core.rag.Bm25Retriever

/**
 * Tier 1.1 — answerability gate: entity-focused questions must retrieve corpus
 * that mentions the asked subject (or a close alias) before generation.
 * When the first BM25 pass returns related-but-wrong chunks, widen the query
 * once with focus entities + domain-agnostic alias terms, then merge hits.
 */

private val FOCUS_TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}\\p{M}]+")

/** Generic stopwords for extracted focus phrases — not document-specific. */
private val FOCUS_STOPWORDS = setOf(
    "the", "this", "that", "these", "those", "what", "which", "how", "does", "do",
    "are", "is", "was", "were", "been", "being", "have", "has", "had",
    "earth", "climate", "system", "document", "guide", "file", "pdf", "attached",
    "main", "purpose", "overview", "section", "chapter", "topics", "topic",
    "related", "according", "describe", "explain", "discuss", "mentioned",
    "components", "component", "parts", "part", "types", "type", "kind", "kinds",
    "some", "any", "all", "key", "specific", "general", "different", "various",
)

private val FOCUS_PATTERNS = listOf(
    Regex("(?i)how (?:does|do) (?:the )?(.+?) (?:affect|influence|impact|shape|drive|regulate|control|change)"),
    Regex("(?i)what role (?:does|do) (?:the )?(.+?) play"),
    Regex("(?i)role of (?:the )?(.+?) (?:in|on|within|regulating|affecting)"),
    Regex("(?i)effect of (?:the )?(.+?) on"),
    Regex("(?i)influence of (?:the )?(.+?) on"),
    Regex("(?i)how (?:does|do) (?:the )?(.+?) (?:work|function|operate|relate)"),
    Regex("(?i)what (?:does|do) (?:the )?(.+?) (?:do|mean|contribute)"),
)

/**
 * Cross-domain alias clusters — when the user asks about one term, retrieval
 * should also try close synonyms used in edu, legal, policy, and science docs.
 */
private val ENTITY_ALIAS_GROUPS = listOf(
    setOf("ocean", "oceans", "hydrosphere", "marine", "seawater", "sea"),
    setOf("atmosphere", "atmospheric", "air", "gaseous"),
    setOf("glacier", "glaciers", "icesheet", "ice", "sealevel", "sea"),
    setOf("sun", "solar", "sunlight", "sunshine"),
    setOf("greenhouse", "emission", "emissions", "methane", "dioxide", "carbon"),
    setOf("carbon", "sequestration", "weathering", "volcanic", "cycle"),
    setOf("feedback", "feedbacks", "amplifying", "loop", "loops"),
    setOf("wind", "winds", "circulation", "current", "currents", "convection"),
    setOf("temperature", "thermal", "warming", "cooling", "heat", "energy"),
    setOf("biosphere", "life", "organism", "organisms", "ecosystem", "ecosystems"),
    setOf("hydrologic", "water", "vapor", "precipitation", "rainfall"),
    setOf("land", "landscape", "surface", "terrestrial", "soil"),
    setOf("cloud", "clouds", "aerosol", "aerosols"),
    setOf("model", "models", "simulation", "simulations", "prediction"),
    setOf("observation", "observations", "measurement", "measurements", "monitoring"),
    setOf("fiduciary", "processor", "principal", "consent", "breach", "manager"),
    setOf("employer", "workmen", "worker", "workers", "labour", "union", "trade"),
    setOf("penalty", "penalties", "fine", "fines", "sanction", "sanctions"),
    setOf("appeal", "appeals", "tribunal", "grievance", "redressal"),
    setOf("children", "child", "minor", "minors", "parental"),
)

internal fun extractQueryFocusEntities(query: String): List<String> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val fromPatterns = LinkedHashSet<String>()
    for (pattern in FOCUS_PATTERNS) {
        val match = pattern.find(trimmed)
        if (match != null) {
            val phrase = match.groupValues[1].trim()
            if (phrase.length >= 3) fromPatterns.add(phrase)
        }
    }
    if (fromPatterns.isNotEmpty()) {
        return fromPatterns.map { normalizeFocusPhrase(it) }.filter { it.isNotBlank() }.distinct()
    }
    return emptyList()
}

private fun normalizeFocusPhrase(phrase: String): String {
    var cleaned = phrase
        .replace(Regex("(?i)'s\\b"), "")
        .replace(Regex("(?i)\\b(the|a|an)\\b"), "")
        .trim()
    cleaned = cleaned.replace(Regex("\\s+"), " ")
    val tokens = cleaned.lowercase().split(FOCUS_TOKEN_SPLIT).filter { it.isNotEmpty() }
    val kept = tokens.filter { token ->
        token.length >= 3 && token !in FOCUS_STOPWORDS
    }
    if (kept.isEmpty()) return ""
    return kept.joinToString(" ")
}

internal fun focusEntityTokens(entityPhrase: String): Set<String> =
    entityPhrase.lowercase()
        .split(FOCUS_TOKEN_SPLIT)
        .filter { token ->
            when {
                token.isEmpty() -> false
                token in FOCUS_STOPWORDS -> false
                token.all { it.isDigit() } -> token.length >= 2
                token.any { it > '\u007f' } -> token.length >= 2
                else -> token.length >= 4
            }
        }
        .toSet()

internal fun expandEntityAliasTokens(tokens: Set<String>): Set<String> {
    if (tokens.isEmpty()) return emptySet()
    val expanded = LinkedHashSet<String>()
    expanded.addAll(tokens)
    for (token in tokens) {
        val stems = listOf(token, token.removeSuffix("s"), token.removeSuffix("es"))
            .filter { it.length >= 3 }
            .distinct()
        for (group in ENTITY_ALIAS_GROUPS) {
            val hit = stems.any { stem ->
                group.contains(stem) ||
                    group.any { alias ->
                        alias == stem ||
                            (stem.length >= 4 && alias.startsWith(stem)) ||
                            (alias.length >= 4 && stem.startsWith(alias))
                    }
            }
            if (hit) expanded.addAll(group)
        }
    }
    return expanded.filter { it.length >= 3 && it !in FOCUS_STOPWORDS }.toSet()
}

internal fun answerabilityQueryExpansion(
    focusEntities: List<String>,
    priorQuery: String? = null,
): String {
    val phrases = LinkedHashSet<String>()
    focusEntities.forEach { if (it.isNotBlank()) phrases.add(it) }
    extractQueryFocusEntities(priorQuery.orEmpty()).forEach { phrases.add(it) }
    if (phrases.isEmpty()) return ""
    val terms = LinkedHashSet<String>()
    for (phrase in phrases) {
        val base = focusEntityTokens(phrase)
        terms.addAll(base)
        terms.addAll(expandEntityAliasTokens(base))
    }
    return terms.joinToString(" ")
}

internal fun focusEntitiesCoveredInCorpus(
    focusEntities: List<String>,
    corpus: String,
): Boolean {
    if (focusEntities.isEmpty()) return true
    if (corpus.isBlank()) return false
    val corpusTokens = significantTokensForClaimOverlap(corpus)
    val corpusLower = corpus.lowercase()
    for (phrase in focusEntities) {
        val baseTokens = focusEntityTokens(phrase)
        if (baseTokens.isEmpty()) continue
        val searchTokens = expandEntityAliasTokens(baseTokens)
        val covered = searchTokens.any { token ->
            token in corpusTokens ||
                (token.length >= 4 && corpusLower.contains(token))
        }
        if (!covered) return false
    }
    return true
}

internal fun focusEntitiesCoveredInTopOrganic(
    focusEntities: List<String>,
    retrieved: List<RetrievedChunk>,
    topOrganic: Int = 4,
): Boolean {
    val body = retrieved
        .filter { it.chunkIndex >= 0 && !it.isStructuralAnchor() }
        .sortedByDescending { it.score }
        .take(topOrganic)
    if (body.isEmpty()) return false
    return focusEntitiesCoveredInCorpus(focusEntities, buildRetrievalCorpus(body))
}

internal fun isRetrievalAnswerableForQuery(
    query: String,
    retrieved: List<RetrievedChunk>,
): Boolean {
    if (isDocumentMetaOverviewQuery(query)) return true
    if (isStructureListQuery(query) || isStructureCountQuery(query)) return true
    val focus = extractQueryFocusEntities(query)
    if (focus.isEmpty()) return true
  return focusEntitiesCoveredInTopOrganic(focus, retrieved)
}

internal fun shouldRunAnswerabilityRetrievalRetry(
    query: String,
    ranked: List<Bm25Retriever.Scored>,
    pool: List<RagChunkEntity>,
    anchoredEntities: List<RagChunkEntity>,
    priorQuery: String? = null,
): Boolean {
    if (isDocumentMetaOverviewQuery(query)) return false
    if (isStructureListQuery(query) || isStructureCountQuery(query)) return false
    val focus = extractQueryFocusEntities(query)
    if (focus.isEmpty()) return false
    val preview = buildAnswerabilityPreviewChunks(ranked, pool, anchoredEntities)
    if (preview.none { it.chunkIndex >= 0 }) return false
    if (isRetrievalAnswerableForQuery(query, preview)) return false
    return answerabilityQueryExpansion(focus, priorQuery).isNotBlank()
}

internal fun buildAnswerabilityPreviewChunks(
    ranked: List<Bm25Retriever.Scored>,
    pool: List<RagChunkEntity>,
    anchoredEntities: List<RagChunkEntity>,
): List<RetrievedChunk> {
    val hits = ArrayList<RetrievedChunk>()
    // Phase A3 — answerability gate uses organic BM25 hits only, not heading anchors.
    for (scored in ranked.take(10)) {
        if (scored.index in pool.indices) {
            hits.add(pool[scored.index].toRetrievedChunk(scored.score))
        }
    }
    return hits
}

/**
 * After answerability retry, if focus entities still aren't in corpus but the
 * model would get weak related chunks, return a deterministic miss.
 */
internal fun shouldEmitAnswerabilityRetrievalMiss(
    query: String,
    turnMode: RagTurnMode,
    retrieved: List<RetrievedChunk>,
): Boolean {
    if (!shouldRetrieveForRagTurnMode(turnMode)) return false
    if (isDocumentMetaOverviewQuery(query)) return false
    if (isStructureListQuery(query) || isStructureCountQuery(query)) return false
    val focus = extractQueryFocusEntities(query)
    if (focus.isEmpty()) return false
    if (retrieved.isEmpty()) return true
    if (isRetrievalAnswerableForQuery(query, retrieved)) return false
    return true
}

internal fun buildAnswerabilityRetrievalMissMessage(query: String): String {
    val focus = extractQueryFocusEntities(query).firstOrNull()
    return if (focus.isNullOrBlank()) {
        "I couldn't find clear support for that in the attached document(s). " +
            "Try naming the section, page, or a more specific phrase from the document."
    } else {
        "I couldn't find clear information about $focus in the attached document(s). " +
            "Try asking with a section name, page number, or a related term used in the file."
    }
}
