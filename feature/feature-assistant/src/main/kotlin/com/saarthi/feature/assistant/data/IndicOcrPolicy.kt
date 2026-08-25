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

    private fun hasAnyRegionalScript(text: String, minChars: Int): Boolean =
        listOf(
            SupportedLanguage.BENGALI,
            SupportedLanguage.TAMIL,
            SupportedLanguage.TELUGU,
            SupportedLanguage.KANNADA,
            SupportedLanguage.GUJARATI,
            SupportedLanguage.PUNJABI,
            SupportedLanguage.ODIA,
        ).any { countScriptLetters(text, it) >= minChars }

    /** Language pack(s) for the Tesseract pass on this page. */
    fun tesseractLanguages(userLang: SupportedLanguage): String =
        userLang.tesseractCode() ?: COMBINED_REGIONAL_LANGS
}
