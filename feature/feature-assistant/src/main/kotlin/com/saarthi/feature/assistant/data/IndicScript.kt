package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage

/** Unicode block for each Saarthi UI language that uses a Brahmic script. */
internal fun SupportedLanguage.scriptRange(): IntRange? = when (this) {
    SupportedLanguage.HINDI, SupportedLanguage.MARATHI -> 0x0900..0x097F // Devanagari
    SupportedLanguage.BENGALI -> 0x0980..0x09FF
    SupportedLanguage.PUNJABI -> 0x0A00..0x0A7F // Gurmukhi
    SupportedLanguage.GUJARATI -> 0x0A80..0x0AFF
    SupportedLanguage.ODIA -> 0x0B00..0x0B7F
    SupportedLanguage.TAMIL -> 0x0B80..0x0BFF
    SupportedLanguage.TELUGU -> 0x0C00..0x0C7F
    SupportedLanguage.KANNADA -> 0x0C80..0x0CFF
    SupportedLanguage.ENGLISH -> null
}

/** Tesseract traineddata code for regional scripts (bundled in assets/tessdata). */
internal fun SupportedLanguage.tesseractCode(): String? = when (this) {
    SupportedLanguage.BENGALI -> "ben"
    SupportedLanguage.TAMIL -> "tam"
    SupportedLanguage.TELUGU -> "tel"
    SupportedLanguage.KANNADA -> "kan"
    SupportedLanguage.GUJARATI -> "guj"
    SupportedLanguage.PUNJABI -> "pan"
    SupportedLanguage.ODIA -> "ori"
    SupportedLanguage.HINDI, SupportedLanguage.MARATHI -> "hin"
    SupportedLanguage.ENGLISH -> null
}

/** Languages whose primary script is not covered well by ML Kit Devanagari alone. */
internal fun SupportedLanguage.usesRegionalTesseractFirst(): Boolean = when (this) {
    SupportedLanguage.BENGALI,
    SupportedLanguage.TAMIL,
    SupportedLanguage.TELUGU,
    SupportedLanguage.KANNADA,
    SupportedLanguage.GUJARATI,
    SupportedLanguage.PUNJABI,
    SupportedLanguage.ODIA,
    -> true
    else -> false
}

internal fun countScriptLetters(text: String, language: SupportedLanguage): Int {
    val range = language.scriptRange() ?: return 0
    return text.count { it.code in range }
}

internal fun countDevanagariLetters(text: String): Int =
    text.count { it.code in 0x0900..0x097F }
