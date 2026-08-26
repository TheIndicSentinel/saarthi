package com.saarthi.feature.assistant.data

/**
 * A1 — system-built Sources footer from retrieval metadata, not model text.
 * Strips model-authored Sources blocks and appends readable file + page lines.
 */
internal const val DETERMINISTIC_SOURCES_LABEL = "Sources:"
internal const val DETERMINISTIC_SOURCES_MAX = 3

private val SOURCES_TAIL_MARKERS = listOf("\nSources:", "\nस्रोत:", "\nSOURCES:")
private val CITATION_INDEX_IN_TAIL = Regex("""\[\d{1,2}\]""")

/**
 * Removes a trailing model-written Sources block when it looks like citations
 * (numbered refs, page dots, hash filenames) — not user prose mentioning "sources".
 */
internal fun stripModelSourcesBlock(text: String): String {
    var result = text.trimEnd()
    for (marker in SOURCES_TAIL_MARKERS) {
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
        afterLabel.lines().any { line -> looksLikeContentStamp(line) }
}

/** User-facing page label from [extractPageRange] (`p.17` → `page 17`). */
internal fun formatPageRangeForUser(pageRange: String): String = when {
    pageRange.startsWith("pp.") -> "pages ${pageRange.removePrefix("pp.")}"
    pageRange.startsWith("p.") -> "page ${pageRange.removePrefix("p.")}"
    else -> pageRange
}

internal fun formatCitationLocation(chunk: RetrievedChunk): String = when {
    chunk.chunkIndex < 0 -> "overview"
    else -> extractPageRange(chunk.text)?.let { formatPageRangeForUser(it) }
        ?: "location not marked in file"
}

internal fun formatUserCitationLine(
    chunk: RetrievedChunk,
    outlineByDocName: Map<String, String>,
): String {
    val name = displayDocName(
        chunk.docName,
        outlineByDocName[chunk.docName],
        chunk.text,
    )
    val location = formatCitationLocation(chunk)
    return "$name · $location"
}

internal fun buildDeterministicSourcesFooter(
    chunks: List<RetrievedChunk>,
    outlineByDocName: Map<String, String>,
    maxSources: Int = DETERMINISTIC_SOURCES_MAX,
): String {
    if (chunks.isEmpty() || maxSources <= 0) return ""
    val ordered = interleaveExcerptsByDoc(chunks)
    val lines = ArrayList<String>(maxSources)
    val seen = LinkedHashSet<String>()
    for (chunk in ordered) {
        val line = formatUserCitationLine(chunk, outlineByDocName)
        val label = line.substringBefore('·').trim()
        if (looksLikeInternalCitationLabel(label)) continue
        if (line in seen) continue
        seen += line
        lines += line
        if (lines.size >= maxSources) break
    }
    if (lines.isEmpty()) return ""
    return buildString {
        append(DETERMINISTIC_SOURCES_LABEL)
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
    maxSources: Int = DETERMINISTIC_SOURCES_MAX,
): String {
    if (chunks.isEmpty()) return modelText
    val body = stripModelSourcesBlock(modelText)
    val footer = buildDeterministicSourcesFooter(chunks, outlineByDocName, maxSources)
    if (footer.isEmpty()) return body
    return if (body.isBlank()) footer else "$body\n\n$footer"
}
