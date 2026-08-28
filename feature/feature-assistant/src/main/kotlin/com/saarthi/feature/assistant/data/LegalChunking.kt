package com.saarthi.feature.assistant.data

/**
 * Wave 4 P19 — section-bound chunking for legal/gazette documents.
 *
 * Fixed 600c windows split chapter headings from operative clauses (penalties vs
 * Schedule, special provisions vs body). Prose sections chunk at ~1800c; table /
 * schedule blocks stay at 600c so amount rows stay intact.
 */

internal const val LEGAL_PROSE_CHUNK_SIZE = 1800
internal const val LEGAL_PROSE_OVERLAP = 120
internal const val LEGAL_TABLE_CHUNK_SIZE = 600
internal const val LEGAL_TABLE_OVERLAP = 80

private val LEGAL_SECTION_LINE_RX = Regex(
    "(?im)^\\s*(?:(?:CHAPTER|Chapter|Section|SECTION|Part|अध्याय|धारा)\\s+|THE SCHEDULE\\b)",
)

private val LEGAL_GAZETTE_SIGNAL_RX = Regex(
    "(?im)(PENALTIES\\s+AND\\s+ADJUDICATION|THE SCHEDULE|SPECIAL\\s+PROVISIONS|Gazette|Official\\s+Gazette)",
)

/** Act / gazette style: multiple structural headings or strong legal signals. */
internal fun isLegalGazetteStyleDocument(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.length < 400) return false
    val sectionStarts = countLegalSectionStartLines(trimmed)
    if (sectionStarts >= 3) return true
    if (sectionStarts >= 2 && LEGAL_GAZETTE_SIGNAL_RX.containsMatchIn(trimmed)) return true
    return sectionStarts >= 1 && LEGAL_GAZETTE_SIGNAL_RX.containsMatchIn(trimmed) &&
        Regex("(?m)^\\s*\\d{1,3}\\.\\s+[\\p{Lu}A-Z]").containsMatchIn(trimmed)
}

internal fun countLegalSectionStartLines(text: String): Int {
    var count = 0
    var i = 0
    while (i < text.length) {
        val lineEnd = text.indexOf('\n', i).let { if (it < 0) text.length else it }
        val line = text.substring(i, lineEnd)
        if (line.isNotBlank() && isLegalSectionStartLine(line)) count++
        i = if (lineEnd < text.length) lineEnd + 1 else text.length
    }
    return count
}

internal fun isLegalSectionStartLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.startsWith("---") && trimmed.endsWith("---")) return false
    if (LEGAL_SECTION_LINE_RX.containsMatchIn(trimmed)) return true
    if (isLikelyHeadingLine(trimmed, nextLineBlank = false)) return true
    return Regex("^\\d{1,3}\\.\\s+[\\p{Lu}A-Z]").matches(trimmed) && trimmed.length <= 80
}

/** Split only at major boundaries — not every numbered clause (33. Penalties). */
internal fun isLegalSectionSplitLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.startsWith("---") && trimmed.endsWith("---")) return false
    return LEGAL_SECTION_LINE_RX.containsMatchIn(trimmed)
}

/** Split at line-start chapter/section/schedule boundaries. */
internal fun splitLegalGazetteSections(text: String): List<String> {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return emptyList()
    val starts = mutableListOf(0)
    var i = 0
    while (i < cleaned.length) {
        val lineEnd = cleaned.indexOf('\n', i).let { if (it < 0) cleaned.length else it }
        val line = cleaned.substring(i, lineEnd)
        if (line.isNotBlank() && isLegalSectionSplitLine(line) && i > 0) {
            starts.add(i)
        }
        i = if (lineEnd < cleaned.length) lineEnd + 1 else cleaned.length
    }
    val sections = ArrayList<String>(starts.size)
    for (idx in starts.indices) {
        val from = starts[idx]
        val to = starts.getOrNull(idx + 1) ?: cleaned.length
        val piece = cleaned.substring(from, to).trim()
        if (piece.isNotEmpty()) sections.add(piece)
    }
    return sections
}

internal fun isTableHeavyLegalSection(text: String): Boolean {
    if (Regex("(?m)^\\s*THE SCHEDULE\\b").containsMatchIn(text)) return true
    if (tabularAmountLineCount(text) >= 2) return true
    val lines = text.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return false
    val tableRows = lines.count { looksLikeTableRow(it) }
    return tableRows >= 4 && tableRows.toDouble() / lines.size > 0.35
}

internal fun chunkLegalGazetteDocument(text: String): List<String> {
    val sections = splitLegalGazetteSections(text)
    if (sections.size <= 1) {
        return chunkDocumentText(text, LEGAL_PROSE_CHUNK_SIZE, LEGAL_PROSE_OVERLAP)
    }
    val chunks = ArrayList<String>()
    for (section in sections) {
        val size = if (isTableHeavyLegalSection(section)) LEGAL_TABLE_CHUNK_SIZE else LEGAL_PROSE_CHUNK_SIZE
        val overlap = if (isTableHeavyLegalSection(section)) LEGAL_TABLE_OVERLAP else LEGAL_PROSE_OVERLAP
        chunks.addAll(chunkDocumentText(section, size, overlap))
    }
    return chunks
}

/** Index-time router: legal/gazette vs default prose chunking. */
internal fun chunkDocumentTextForIndexing(text: String, mimeType: String = ""): List<String> {
    if (mimeType.contains("pdf", ignoreCase = true) || mimeType.contains("doc", ignoreCase = true)) {
        if (isLegalGazetteStyleDocument(text)) {
            return chunkLegalGazetteDocument(text)
        }
    }
    if (isLegalGazetteStyleDocument(text)) {
        return chunkLegalGazetteDocument(text)
    }
    return chunkDocumentText(text, 600, 80)
}
