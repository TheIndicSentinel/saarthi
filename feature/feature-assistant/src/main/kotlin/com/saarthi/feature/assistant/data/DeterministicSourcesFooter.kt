package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.CitationDisplayLabels

/**
 * A1 — system-built Sources footer from retrieval metadata, not model text.
 * A3 — localized Sources header and page/overview labels.
 * A6 — multi-file compare: at least one source line per contributing document.
 */
internal const val DETERMINISTIC_SOURCES_MAX = 3

internal fun citationChunkDocKey(chunk: RetrievedChunk): String =
    chunk.docUri.ifEmpty { chunk.docName }

/**
 * Multi-file Sources fairness when the user compares documents or retrieval
 * spans multiple files with real hits (A6 point 1).
 */
internal fun shouldFairMultiFileSources(
    equalSlots: Boolean,
    chunks: List<RetrievedChunk>,
): Boolean {
    if (equalSlots) return true
    val contributing = chunks
        .filter { it.score > 0.0 }
        .map { citationChunkDocKey(it) }
        .distinct()
    return contributing.size >= 2
}

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
    multiFileFairSources: Boolean = false,
): String {
    if (chunks.isEmpty() || maxSources <= 0) return ""
    val ordered = interleaveExcerptsByDoc(chunks)
    val lines = ArrayList<String>(maxSources)
    val seenLines = LinkedHashSet<String>()

    fun lineFor(chunk: RetrievedChunk): String? {
        val line = formatUserCitationLine(chunk, outlineByDocName, labels)
        val label = line.substringBefore('·').trim()
        if (looksLikeInternalCitationLabel(label)) return null
        return line
    }

    fun tryAdd(chunk: RetrievedChunk): Boolean {
        val line = lineFor(chunk) ?: return false
        if (line in seenLines) return false
        seenLines += line
        lines += line
        return true
    }

    val distinctDocs = ordered.map { citationChunkDocKey(it) }.distinct()
    val multiFile = multiFileFairSources && distinctDocs.size >= 2

    if (multiFile) {
        for (docKey in distinctDocs) {
            if (lines.size >= maxSources) break
            val reserveChunk = ordered.firstOrNull { citationChunkDocKey(it) == docKey && it.score > 0.0 }
                ?: ordered.firstOrNull { citationChunkDocKey(it) == docKey }
            if (reserveChunk != null) tryAdd(reserveChunk)
        }
    }

    for (chunk in ordered) {
        if (lines.size >= maxSources) break
        tryAdd(chunk)
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
    multiFileFairSources: Boolean = false,
): String {
    if (chunks.isEmpty()) return modelText
    val body = stripModelSourcesBlock(modelText, labels)
    val footer = buildDeterministicSourcesFooter(
        chunks,
        outlineByDocName,
        labels,
        maxSources,
        multiFileFairSources,
    )
    if (footer.isEmpty()) return body
    return if (body.isBlank()) footer else "$body\n\n$footer"
}
