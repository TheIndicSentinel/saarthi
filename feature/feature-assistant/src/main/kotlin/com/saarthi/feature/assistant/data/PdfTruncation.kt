package com.saarthi.feature.assistant.data

/**
 * Wave 4 P20 — honest PDF index caps: surface when only the first N pages or
 * char budget were indexed so the model does not claim missing chapters/schedules
 * "are not in the document".
 */

internal const val INDEX_TRUNCATION_CHUNK_INDEX = -4

internal data class PdfTruncationMeta(
    val totalPages: Int = 0,
    val indexedPages: Int = 0,
    val charCapped: Boolean = false,
)

internal fun buildPdfTruncationNotice(
    meta: PdfTruncationMeta,
    maxExtractedChars: Int = FileContentExtractor.MAX_EXTRACTED_CHARS,
): String? {
    val parts = mutableListOf<String>()
    if (meta.totalPages > 0 && meta.indexedPages in 1 until meta.totalPages) {
        parts.add("Indexed pages 1–${meta.indexedPages} of ${meta.totalPages}")
    }
    if (meta.charCapped) {
        val k = maxExtractedChars / 1000
        parts.add("indexed only the first ~${k}k characters of this file")
    }
    if (parts.isEmpty()) return null
    return parts.joinToString("; ") +
        ". Content beyond that was not searched — tell the user if their question may refer to later pages or sections."
}

/** Prompt line(s) for truncated indexes — shown beside the session manifest. */
internal fun indexTruncationNoticeLine(notices: List<String>): String {
    val clean = notices.map { it.trim() }.filter { it.isNotEmpty() }
    if (clean.isEmpty()) return ""
    return clean.joinToString("\n") + "\n\n"
}

internal fun capExtractedText(text: String, maxChars: Int): Pair<String, Boolean> {
    if (text.length <= maxChars) return text to false
    return text.take(maxChars) to true
}
