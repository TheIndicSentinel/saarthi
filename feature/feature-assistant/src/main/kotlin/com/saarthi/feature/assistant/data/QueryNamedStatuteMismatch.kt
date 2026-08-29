package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.CitationDisplayLabels

/**
 * Tier 1.4 — when the user names a specific statute/act in the question but the
 * scoped attached document is a different law, return a deterministic miss
 * instead of letting the model answer from parametric knowledge.
 */

private val ACT_PHRASE_IN_QUERY = Regex(
    "(?i)(?:the\\s+)?([\\p{L}\\p{N}'\\s,-]{8,120}?)\\s+act\\b",
)

private val STATUTE_TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}\\p{M}]+")

private val STATUTE_GENERIC_TOKENS = setOf(
    "the", "this", "that", "indian", "india", "national", "central", "state",
    "amendment", "amended", "bill", "act", "acts", "law", "laws", "rules",
    "code", "regulation", "ordinance", "notification", "schedule", "statute",
    "section", "sections", "chapter", "chapters", "article", "articles",
    "provision", "provisions", "clause", "paragraph", "part", "parts",
    "under", "about", "regarding", "related", "mentioned", "attached",
    "document", "file", "pdf", "what", "which", "how", "does", "are", "is",
)

private data class NamedStatuteQueryMarker(
    val phrases: List<String>,
    val distinctiveTokens: Set<String>,
)

/** High-precision query phrases → tokens expected in a matching document title/outline. */
private val NAMED_STATUTE_QUERY_MARKERS = listOf(
    NamedStatuteQueryMarker(
        phrases = listOf(
            "digital personal data",
            "dpdpa",
            "dpdp act",
            "data protection act, 2023",
            "data protection act 2023",
        ),
        distinctiveTokens = setOf("digital", "dpdpa", "dpdp", "protection"),
    ),
    NamedStatuteQueryMarker(
        phrases = listOf(
            "industrial disputes",
            "industrial dispute",
            "labour code",
            "labour relations",
            "trade unions act",
        ),
        distinctiveTokens = setOf("industrial", "disputes", "labour", "workmen", "unions"),
    ),
    NamedStatuteQueryMarker(
        phrases = listOf("income tax", "income-tax"),
        distinctiveTokens = setOf("income", "tax"),
    ),
    NamedStatuteQueryMarker(
        phrases = listOf("companies act", "company act"),
        distinctiveTokens = setOf("companies", "company"),
    ),
    NamedStatuteQueryMarker(
        phrases = listOf("motor vehicles", "motor vehicle act"),
        distinctiveTokens = setOf("motor", "vehicles", "vehicle"),
    ),
    NamedStatuteQueryMarker(
        phrases = listOf("information technology", "it act"),
        distinctiveTokens = setOf("information", "technology"),
    ),
    NamedStatuteQueryMarker(
        phrases = listOf("consumer protection"),
        distinctiveTokens = setOf("consumer", "protection"),
    ),
    NamedStatuteQueryMarker(
        phrases = listOf("contract act", "indian contract"),
        distinctiveTokens = setOf("contract"),
    ),
)

internal fun extractNamedStatuteSignalsFromQuery(query: String): Set<String> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptySet()
    val lower = trimmed.lowercase()
    val signals = LinkedHashSet<String>()
    for (marker in NAMED_STATUTE_QUERY_MARKERS) {
        if (marker.phrases.any { phrase -> lower.contains(phrase) }) {
            signals.addAll(marker.distinctiveTokens)
        }
    }
    for (match in ACT_PHRASE_IN_QUERY.findAll(trimmed)) {
        signals.addAll(distinctiveStatuteTokens(match.groupValues[1]))
    }
    return signals.filter { it.length >= 3 }.toSet()
}

private fun distinctiveStatuteTokens(phrase: String): Set<String> =
    phrase.lowercase()
        .split(STATUTE_TOKEN_SPLIT)
        .filter { token ->
            token.length >= 4 && token !in STATUTE_GENERIC_TOKENS
        }
        .toSet()

private val DOC_STATUTE_TITLE_PATTERN = Regex(
    "(?i)\\b(act|agreement|policy|law|rules|regulation|code|ordinance|notification|amendment)\\b",
)

internal fun extractNamedStatuteSignalsFromDocument(
    docName: String,
    outlinePreview: String?,
    bodyPreview: String?,
): Set<String> {
    val signals = LinkedHashSet<String>()
    val candidates = listOfNotNull(
        outlinePreview?.lines()?.firstOrNull { line ->
            DOC_STATUTE_TITLE_PATTERN.containsMatchIn(line) && line.length in 8..200
        },
        outlinePreview?.lines()?.firstOrNull()?.takeIf { it.length in 8..200 },
        bodyPreview?.lines()?.firstOrNull { line ->
            DOC_STATUTE_TITLE_PATTERN.containsMatchIn(line) && line.length in 8..200
        },
    )
    candidates.forEach { line ->
        signals.addAll(distinctiveStatuteTokens(line))
    }
    shortDocName(docName)
        .lowercase()
        .split(STATUTE_TOKEN_SPLIT)
        .filter { it.length >= 4 && it !in STATUTE_GENERIC_TOKENS }
        .forEach { signals.add(it) }
    return signals
}

internal fun namedStatuteSignalOverlap(
    querySignals: Set<String>,
    docSignals: Set<String>,
): Int {
    if (querySignals.isEmpty() || docSignals.isEmpty()) return 0
    return querySignals.count { queryToken ->
        docSignals.any { docToken ->
            queryToken == docToken ||
                (queryToken.length >= 5 && docToken.contains(queryToken)) ||
                (docToken.length >= 5 && queryToken.contains(docToken))
        }
    }
}

/**
 * True when the question names one statute but scoped doc title/outline signals another.
 */
internal fun shouldEmitNamedStatuteDocumentMismatch(
    query: String,
    turnMode: RagTurnMode,
    restrictDocUris: Set<String>,
    sessionDocs: List<Pair<String, String>>,
    outlineByDocName: Map<String, String>,
    retrieved: List<RetrievedChunk>,
): Boolean {
    if (!shouldRetrieveForRagTurnMode(turnMode)) return false
    if (restrictDocUris.isEmpty()) return false
    if (restrictDocUris.size > 2) return false
    val querySignals = extractNamedStatuteSignalsFromQuery(query)
    if (querySignals.size < 2) return false
    val scopedDocs = sessionDocs.filter { (uri, _) -> uri in restrictDocUris }
    if (scopedDocs.isEmpty()) return false
    val docSignals = scopedDocs.flatMap { (uri, name) ->
        val outline = outlineByDocName[name]
        val bodyPreview = retrieved.firstOrNull { chunk ->
            chunk.docUri == uri || chunk.docName == name
        }?.text
        extractNamedStatuteSignalsFromDocument(name, outline, bodyPreview)
    }.toSet()
    if (docSignals.isEmpty()) return false
    return namedStatuteSignalOverlap(querySignals, docSignals) < 2
}

internal fun buildNamedStatuteDocumentMismatchMessage(
    query: String,
    restrictDocUris: Set<String>,
    sessionDocs: List<Pair<String, String>>,
    outlineByDocName: Map<String, String>,
    labels: CitationDisplayLabels,
): String {
    val attachedName = sessionDocs
        .firstOrNull { (uri, _) -> uri in restrictDocUris }
        ?.second
    val display = attachedName?.let { name ->
        displayCitationDocName(
            name,
            outlineByDocName[name],
            outlineByDocName[name],
            outlineByDocName[name]?.length,
            labels,
        )
    } ?: FALLBACK_ATTACHED_DOC_LABEL
    val queryActHint = extractNamedStatuteSignalsFromQuery(query)
        .filter { it.length >= 5 }
        .take(3)
        .joinToString(" ")
        .ifBlank { "that law" }
    return "Your question looks like it is about $queryActHint, but the attached document appears to be $display. " +
        "Ask about the attached file, or attach the document for the act you mean."
}
