package com.saarthi.feature.assistant.data

/**
 * Cleans untrusted attachment text (PDF / OCR / docx / txt / …) once at
 * extract completion so both the prompt path and RAG index see the same
 * body.
 *
 * Conservative by design:
 *  - Drops smuggling / control characters (NUL, ISO controls except tab/newline,
 *    bidi overrides, Unicode tag characters).
 *  - Neutralizes forged `[SAARTHI_MEMORY …]` / `[SAARTHI_REMINDER …]` tags
 *    that [ResponseMarkerParser] treats as commands. Ordinary words such as
 *    "memory" or "ignore" are left alone.
 *  - Does not NFC-normalize (Indic combining marks must stay as extracted).
 *  - Never throws; empty-after-sanitize is a valid result.
 */
object DocumentTextSanitizer {

    // Strict enough that a PDF heading containing the word "memory" is
    // never stripped; loose enough to catch a missing closing `]`.
    private val FORGED_SAARTHI_MARKER = Regex(
        """\[\s*SAARTHI_(?:MEMORY|REMINDER)\b[^\]\n]*\]?""",
        RegexOption.IGNORE_CASE,
    )

    fun sanitize(text: String): String {
        if (text.isEmpty()) return text
        return try {
            neutralizeForgedMarkers(stripSmugglingChars(text))
        } catch (_: Exception) {
            try {
                stripSmugglingChars(text)
            } catch (_: Exception) {
                text
            }
        }
    }

    private fun neutralizeForgedMarkers(text: String): String =
        FORGED_SAARTHI_MARKER.replace(text, "")

    private fun stripSmugglingChars(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val cp = text.codePointAt(i)
            if (keepCodePoint(cp)) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun keepCodePoint(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return true
        if (Character.isISOControl(cp)) return false
        // Bidi embedding / override / isolate (U+202A–U+202E, U+2066–U+2069).
        if (cp in 0x202A..0x202E) return false
        if (cp in 0x2066..0x2069) return false
        // Unicode tag characters (U+E0001, U+E0020–U+E007F). Supplementary
        // plane — must be matched as code points, not UTF-16 [Char]s.
        if (cp == 0xE0001) return false
        if (cp in 0xE0020..0xE007F) return false
        return true
    }
}
