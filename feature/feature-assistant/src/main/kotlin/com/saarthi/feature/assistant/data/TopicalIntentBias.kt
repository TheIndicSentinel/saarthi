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
    return isLowConfidenceAnchorOnlyRetrieval(query, retrieved)
}

/** Topical indexed-session turn eligible for document citation when hits are strong enough. */
internal fun isIndexedTopicalCitationIntent(
    query: String,
    turnMode: RagTurnMode,
): Boolean =
    turnMode == RagTurnMode.DOCUMENT_GROUNDED &&
        isIndexedSessionTopicalWithoutDocCues(query) &&
        hasAmbiguousTopicalSubjectCues(query)
