package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage

/**
 * R4 follow-up — when to run on-device Tesseract for Bengali/Tamil/Telugu/etc.
 *
 * ML Kit only ships Latin + Devanagari OCR models. Regional scans need Tesseract
 * with script-specific traineddata (bundled under assets/tessdata).
 */
internal object IndicOcrPolicy {

    /** Tesseract traineddata codes for all non-Latin Saarthi UI scripts. */
    const val COMBINED_REGIONAL_LANGS = "ben+tam+tel+kan+guj+pan+ori"

    private const val MIN_SCRIPT_CHARS = 6
    private const val MIN_INDIC_CHARS = 8

    /**
     * True when ML Kit output is too weak and a Tesseract pass may recover text.
     */
    fun needsRegionalTesseractPass(mlKitText: String, userLang: SupportedLanguage): Boolean {
        val trimmed = mlKitText.trim()
        if (trimmed.isEmpty()) return true

        if (userLang.usesRegionalTesseractFirst()) {
            return countScriptLetters(trimmed, userLang) < MIN_SCRIPT_CHARS
        }

        if (userLang == SupportedLanguage.HINDI || userLang == SupportedLanguage.MARATHI) {
            return countDevanagariLetters(trimmed) < MIN_INDIC_CHARS && trimmed.length < 48
        }

        // English UI — run Tesseract when ML Kit did not recover real Indic text.
        if (IndicOcrMerger.countIndicLetters(trimmed) >= MIN_INDIC_CHARS) return false
        if (hasAnyRegionalScript(trimmed, MIN_SCRIPT_CHARS)) return false
        return true
    }

    /** Language pack(s) for the Tesseract pass on this page. */
    fun tesseractLanguages(userLang: SupportedLanguage, documentHint: String = ""): String {
        val detected = detectRegionalTesseractCodes(documentHint)
        if (detected.isNotEmpty()) return detected.joinToString("+")
        // Script unknown (empty / Latin-only ML Kit). Prefer the UI language
        // first, then the rest of the combined pack, so a Tamil-UI user
        // attaching a Bengali scan still gets Bengali — and a Tamil scan still
        // tries Tamil first.
        return preferUserThenCombined(userLang.tesseractCode())
    }

    /**
     * Tesseract codes for regional scripts actually present in [text].
     * Empty when ML Kit recovered no regional script (or only Devanagari,
     * which Tesseract is not used for).
     */
    fun detectRegionalTesseractCodes(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>(4)
        for (lang in REGIONAL_LANGUAGES) {
            if (countScriptLetters(text, lang) >= MIN_SCRIPT_CHARS) {
                lang.tesseractCode()?.let { out.add(it) }
            }
        }
        return out
    }

    private fun preferUserThenCombined(preferred: String?): String {
        if (preferred == null) return COMBINED_REGIONAL_LANGS
        val rest = COMBINED_REGIONAL_LANGS.split('+').filter { it != preferred }
        return (listOf(preferred) + rest).joinToString("+")
    }

    private val REGIONAL_LANGUAGES = listOf(
        SupportedLanguage.BENGALI,
        SupportedLanguage.TAMIL,
        SupportedLanguage.TELUGU,
        SupportedLanguage.KANNADA,
        SupportedLanguage.GUJARATI,
        SupportedLanguage.PUNJABI,
        SupportedLanguage.ODIA,
    )

    private fun hasAnyRegionalScript(text: String, minChars: Int): Boolean =
        REGIONAL_LANGUAGES.any { countScriptLetters(text, it) >= minChars }
}
