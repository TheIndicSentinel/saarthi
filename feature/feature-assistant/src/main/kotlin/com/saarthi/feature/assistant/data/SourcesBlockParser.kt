package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.allCitationSourcesHeaders

/** One readable source line for UI chips (A5). */
data class DisplaySource(val docTitle: String, val location: String)

/** Assistant message split into answer body + optional Sources footer. */
data class ParsedAssistantContent(
    val body: String,
    val sourcesHeader: String?,
    val sources: List<DisplaySource>,
)

private val INLINE_CITATION_INDEX = Regex("""\s*\[\d{1,2}\]""")
private val SOURCE_LINE_INDEX_PREFIX = Regex("""^\[\d{1,2}\]\s*""")
private val SOURCES_HEADER_EXTRA = listOf("SOURCES:")

/**
 * Splits a completed assistant message into markdown body and structured sources.
 * Recognizes deterministic footers (A1) and legacy model `Sources:` / `स्रोत:` blocks.
 */
internal fun parseAssistantMessageForDisplay(text: String): ParsedAssistantContent {
    val block = findTrailingSourcesBlock(text)
    if (block == null) {
        return ParsedAssistantContent(text, null, emptyList())
    }
    val sources = block.lines.mapNotNull { parseDisplaySourceLine(it) }
    if (sources.isEmpty()) {
        return ParsedAssistantContent(text, null, emptyList())
    }
    val body = stripInlineCitationIndices(block.body.trimEnd())
    return ParsedAssistantContent(body, block.header, sources)
}

private data class SourcesBlockSlice(val body: String, val header: String, val lines: List<String>)

private fun findTrailingSourcesBlock(text: String): SourcesBlockSlice? {
    val trimmed = text.trimEnd()
    if (trimmed.isEmpty()) return null
    val headers = allCitationSourcesHeaders() + SOURCES_HEADER_EXTRA
    var bestStart: Int? = null
    var bestHeader: String? = null
    for (header in headers) {
        val newlineIdx = trimmed.lastIndexOf("\n$header")
        if (newlineIdx >= 0) {
            if (bestStart == null || newlineIdx > bestStart) {
                bestStart = newlineIdx
                bestHeader = header
            }
        }
        if (trimmed.startsWith(header)) {
            if (bestStart == null || trimmed.length < bestStart) {
                bestStart = 0
                bestHeader = header
            }
        }
    }
    val start = bestStart ?: return null
    val header = bestHeader ?: return null
    val tailStart = if (start == 0) 0 else start + 1 // skip leading newline before header
    val tail = trimmed.substring(tailStart)
    if (!tail.startsWith(header)) return null
    val afterHeader = tail.removePrefix(header).trimStart()
    val citeLines = afterHeader.lines().map { it.trim() }.filter { it.isNotBlank() }
    if (citeLines.isEmpty()) return null
    val parsedLines = citeLines.mapNotNull { parseDisplaySourceLine(it) }
    if (parsedLines.isEmpty()) return null
    val body = trimmed.substring(0, start).trimEnd()
    return SourcesBlockSlice(body, header, citeLines)
}

internal fun parseDisplaySourceLine(line: String): DisplaySource? {
    val cleaned = line.trim().replace(SOURCE_LINE_INDEX_PREFIX, "").trim()
    val dotIdx = cleaned.indexOf('·')
    if (dotIdx <= 0 || dotIdx >= cleaned.lastIndex) return null
    val title = cleaned.substring(0, dotIdx).trim()
    val location = cleaned.substring(dotIdx + 1).trim()
    if (title.isBlank() || location.isBlank()) return null
    if (looksLikeInternalCitationLabel(title)) return null
    return DisplaySource(title, location)
}

/** Removes inline [1]…[9] markers from the answer body when chips carry citations (A5). */
internal fun stripInlineCitationIndices(body: String): String =
    INLINE_CITATION_INDEX.replace(body, "")
