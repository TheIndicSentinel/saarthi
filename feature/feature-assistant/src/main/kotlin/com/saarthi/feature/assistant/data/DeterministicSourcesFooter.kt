package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.CitationDisplayLabels

/**
 * A1 — system-built Sources footer from retrieval metadata, not model text.
 * A3 — localized Sources header and page/overview labels.
 */
internal const val DETERMINISTIC_SOURCES_MAX = 3

private val SOURCES_TAIL_MARKERS_BASE = listOf("\nSources:", "\nस्रोत:", "\nस्त्रोत:", "\nSOURCES:")
private val CITATION_INDEX_IN_TAIL = Regex("""\[\d{1,2}\]""")

/**
 * Removes a trailing model-written Sources block when it looks like citations
 * (numbered refs, page dots, hash filenames) — not user prose mentioning "sources".
 */
internal fun stripModelSourcesBlock(text: String, labels: CitationDisplayLabels): String {
    var result = text.trimEnd()
    val markers = SOURCES_TAIL_MARKERS_BASE + "\n${labels.sourcesHeader}"
    for (marker in markers) {
        val idx = result.lastIndexOf(marker, ignoreCase = true)
        if (idx < 0) continue
        val tail = result.substring(idx)
        if (!looksLikeAutomatedSourcesTail(tail)) continue
        result = result.substring(0, idx).trimEnd()
    }
    return result
}

private fun looksLikeAutomatedSourcesTail(tail: String): Boolean {
    val lower = tail.lowercase()
    if (!lower.contains("sources:") && !tail.contains("स्रोत")) return false
    val afterLabel = tail.substringAfter(':', "").trim()
    if (afterLabel.isEmpty()) return true
    return CITATION_INDEX_IN_TAIL.containsMatchIn(afterLabel) ||
        afterLabel.contains("· p.") ||
        afterLabel.contains("· page") ||
        afterLabel.contains("· pages") ||
        afterLabel.contains("· पृष्ठ") ||
        afterLabel.contains("· पृष्ठा") ||
        afterLabel.lines().any { line -> looksLikeContentStamp(line) }
}

/** User-facing page label from [extractPageRange] (`p.17` → localized `page 17`). */
internal fun formatPageRangeForUser(pageRange: String, labels: CitationDisplayLabels): String = when {
    pageRange.startsWith("pp.") -> "${labels.pagesPlural} ${pageRange.removePrefix("pp.")}"
    pageRange.startsWith("p.") -> "${labels.pageSingle} ${pageRange.removePrefix("p.")}"
    else -> pageRange
}

internal fun formatCitationLocation(chunk: RetrievedChunk, labels: CitationDisplayLabels): String = when {
    chunk.chunkIndex < 0 -> labels.overview
    else -> extractPageRange(chunk.text)?.let { formatPageRangeForUser(it, labels) }
        ?: labels.locationUnknown
}

internal fun formatUserCitationLine(
    chunk: RetrievedChunk,
    outlineByDocName: Map<String, String>,
    labels: CitationDisplayLabels,
): String {
    val name = displayDocName(
        chunk.docName,
        outlineByDocName[chunk.docName],
        chunk.text,
    )
    val location = formatCitationLocation(chunk, labels)
    return "$name · $location"
}

internal fun buildDeterministicSourcesFooter(
    chunks: List<RetrievedChunk>,
    outlineByDocName: Map<String, String>,
    labels: CitationDisplayLabels,
    maxSources: Int = DETERMINISTIC_SOURCES_MAX,
): String {
    if (chunks.isEmpty() || maxSources <= 0) return ""
    val ordered = interleaveExcerptsByDoc(chunks)
    val lines = ArrayList<String>(maxSources)
    val seen = LinkedHashSet<String>()
    for (chunk in ordered) {
        val line = formatUserCitationLine(chunk, outlineByDocName, labels)
        val label = line.substringBefore('·').trim()
        if (looksLikeInternalCitationLabel(label)) continue
        if (line in seen) continue
        seen += line
        lines += line
        if (lines.size >= maxSources) break
    }
    if (lines.isEmpty()) return ""
    return buildString {
        append(labels.sourcesHeader)
        for (line in lines) {
            append('\n')
            append(line)
        }
    }
}

internal fun applyDeterministicSourcesFooter(
    modelText: String,
    chunks: List<RetrievedChunk>,
    outlineByDocName: Map<String, String>,
    labels: CitationDisplayLabels,
    maxSources: Int = DETERMINISTIC_SOURCES_MAX,
): String {
    if (chunks.isEmpty()) return modelText
    val body = stripModelSourcesBlock(modelText, labels)
    val footer = buildDeterministicSourcesFooter(chunks, outlineByDocName, labels, maxSources)
    if (footer.isEmpty()) return body
    return if (body.isBlank()) footer else "$body\n\n$footer"
}
