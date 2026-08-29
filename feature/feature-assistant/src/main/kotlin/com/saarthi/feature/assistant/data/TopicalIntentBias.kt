package com.saarthi.feature.assistant.data

/**
 * Phase 5.2 — indexed-session topical bias with weak-match downstream gates.
 * Retrieves on short topical asks without explicit "document/file" cues, but
 * suppresses strong-match / Sources when lexical evidence is weak.
 */

/** Substance cues for policy/legal questions without naming "document/file". */
private val TOPICAL_SUBJECT_PATTERN = Regex(
    "(?i)\\b(" +
        "applicab|applies to|apply to|coverage|covers|consent|breach|notification|" +
        "processing|children|child|minor|minors|timeline|obligation|obligations|" +
        "principal|fiduciary|penalt|appeal|duties|rights|data protection|" +
        "personal data|lawful|compliance|breach notification" +
        ")\\b",
)

private val DEICTIC_TOPICAL_PATTERN = Regex(
    "(?i)\\b(this act|this law|this bill|does it|is it|will it|can it)\\b",
)

private val HINGLISH_TOPICAL_PATTERN = Regex(
    "(?i)(lagu hota|lagta hai|ke liye|par lagu|bachchon|bacchon|dastavaz|dastavez)",
)

/**
 * Topical question about indexed content without explicit document-scope phrases.
 */
internal fun isIndexedSessionTopicalWithoutDocCues(query: String): Boolean =
    isIndexedSessionTopicalQuestion(query) && !hasDocumentQueryCues(query)

/**
 * Expand topical detection beyond question-mark / WH-lead heuristics.
 * Called from [isIndexedSessionTopicalQuestion] in QueryRouting.
 */
internal fun hasAmbiguousTopicalSubjectCues(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return false
    if (TOPICAL_SUBJECT_PATTERN.containsMatchIn(trimmed)) return true
    if (HINGLISH_TOPICAL_PATTERN.containsMatchIn(trimmed)) return true
    if (DEICTIC_TOPICAL_PATTERN.containsMatchIn(trimmed)) return true
    return false
}

/** Deterministic miss when topical intent fired but retrieval is empty or anchor-only weak. */
internal fun shouldEmitIndexedTopicalWeakMiss(
    query: String,
    turnMode: RagTurnMode,
    retrieved: List<RetrievedChunk>,
): Boolean {
    if (!shouldRetrieveForRagTurnMode(turnMode)) return false
    if (!isIndexedSessionTopicalWithoutDocCues(query)) return false
    if (isDocumentMetaOverviewQuery(query)) return false
    if (retrieved.isEmpty()) return true
    if (!topicalSubjectCoveredInRetrieval(query, retrieved)) return true
    return isLowConfidenceAnchorOnlyRetrieval(query, retrieved)
}

/** Topical subject tokens from the question should appear in retrieved body text. */
internal fun topicalSubjectCoveredInRetrieval(
    query: String,
    retrieved: List<RetrievedChunk>,
): Boolean {
    val subjectTokens = topicalSubjectTokensFromQuery(query)
    if (subjectTokens.isEmpty()) return true
    val body = retrieved.filter { it.chunkIndex >= 0 && !it.isStructuralAnchor() }
    if (body.isEmpty()) return false
    val corpusTokens = significantTokensForClaimOverlap(buildRetrievalCorpus(body))
    val searchTokens = expandEntityAliasTokens(subjectTokens)
    return searchTokens.any { token -> token in corpusTokens }
}

internal fun topicalSubjectTokensFromQuery(query: String): Set<String> {
    val tokens = LinkedHashSet<String>()
    for (match in TOPICAL_SUBJECT_PATTERN.findAll(query)) {
        val raw = match.value.lowercase()
        raw.split(Regex("[^a-z]+")).filter { it.length >= 4 }.forEach { tokens.add(it) }
        if (raw.length >= 4) tokens.add(raw)
    }
    return tokens
}

internal fun buildIndexedTopicalWeakMissMessage(query: String): String =
    "I couldn't find a clear answer to that in the attached document(s). " +
        "Try naming a chapter, section, or a specific phrase from the file."

/** Topical indexed-session turn eligible for document citation when hits are strong enough. */
internal fun isIndexedTopicalCitationIntent(
    query: String,
    turnMode: RagTurnMode,
): Boolean =
    turnMode == RagTurnMode.DOCUMENT_GROUNDED &&
        isIndexedSessionTopicalWithoutDocCues(query) &&
        hasAmbiguousTopicalSubjectCues(query)
