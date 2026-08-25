package com.saarthi.feature.assistant.data

/**
 * R4 — merge Latin + Devanagari ML Kit OCR on the same page, plus optional
 * Tesseract output for regional scripts (R4 follow-up).
 *
 * Scanned Indian documents are often bilingual (English headers + Indic body).
 * Digital PDFs never hit this path — PdfBox reads the embedded text layer for
 * all scripts (Bengali, Tamil, etc.).
 */
internal object IndicOcrMerger {

    /** Minimum Indic + Latin letter counts before treating a page as bilingual. */
    private const val BILINGUAL_LATIN_MIN = 12
    private const val BILINGUAL_INDIC_MIN = 12

    fun merge(latin: String, devanagari: String): String = mergeAll(listOf(latin, devanagari))

    /** Merge any number of OCR passes (ML Kit Latin, Devanagari, Tesseract, …). */
    fun mergeAll(parts: List<String>): String {
        val trimmed = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmed.isEmpty()) return ""
        return trimmed.drop(1).fold(trimmed.first()) { acc, next -> mergePair(acc, next) }
    }

    private fun mergePair(latin: String, devanagari: String): String {
        val l = latin.trim()
        val d = devanagari.trim()
        if (l.isEmpty()) return d
        if (d.isEmpty()) return l

        val lLatin = countLatinLetters(l)
        val lIndic = countIndicLetters(l)
        val dLatin = countLatinLetters(d)
        val dIndic = countIndicLetters(d)

        // Typical Indian govt form: English labels + Hindi/Devanagari paragraphs.
        if (lLatin >= BILINGUAL_LATIN_MIN &&
            dIndic >= BILINGUAL_INDIC_MIN &&
            dIndic > lIndic &&
            lLatin > dLatin
        ) {
            return mergeDistinctLines(l, d)
        }

        val lScore = lLatin + lIndic
        val dScore = dLatin + dIndic
        return if (dScore > lScore) d else l
    }

    /** Union lines from [secondary] that are not already present in [primary]. */
    internal fun mergeDistinctLines(primary: String, secondary: String): String {
        val seen = primary.lines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
        val out = StringBuilder(primary.trim())
        for (line in secondary.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.lowercase()
            if (key in seen) continue
            // Drop near-duplicates where one line is a substring of another.
            if (seen.any { key in it || it in key }) continue
            if (out.isNotEmpty()) out.appendLine()
            out.append(trimmed)
            seen.add(key)
        }
        return out.toString()
    }

    internal fun countLatinLetters(text: String): Int =
        text.count { it.isLetter() && it.code <= 0x024F }

    internal fun countIndicLetters(text: String): Int =
        text.count(::isIndicLetter)
}

/** True for Brahmic / Indic script letters used by Saarthi's supported languages. */
internal fun isIndicLetter(c: Char): Boolean = when (c.code) {
    in 0x0900..0x097F -> true // Devanagari (Hindi, Marathi)
    in 0x0980..0x09FF -> true // Bengali
    in 0x0A00..0x0A7F -> true // Gurmukhi (Punjabi)
    in 0x0A80..0x0AFF -> true // Gujarati
    in 0x0B00..0x0B7F -> true // Odia
    in 0x0B80..0x0BFF -> true // Tamil
    in 0x0C00..0x0C7F -> true // Telugu
    in 0x0C80..0x0CFF -> true // Kannada
    else -> false
}
