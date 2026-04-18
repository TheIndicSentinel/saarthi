package com.saarthi.core.i18n

enum class SupportedLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val flag: String,
) {
    ENGLISH(code = "en", nativeName = "English", englishName = "English", flag = "🇬🇧"),
    HINDI(code = "hi", nativeName = "हिन्दी", englishName = "Hindi", flag = "🇮🇳"),
    TAMIL(code = "ta", nativeName = "தமிழ்", englishName = "Tamil", flag = "🇮🇳"),
    TELUGU(code = "te", nativeName = "తెలుగు", englishName = "Telugu", flag = "🇮🇳"),
    BENGALI(code = "bn", nativeName = "বাংলা", englishName = "Bengali", flag = "🇮🇳"),
    MARATHI(code = "mr", nativeName = "मराठी", englishName = "Marathi", flag = "🇮🇳"),
    KANNADA(code = "kn", nativeName = "ಕನ್ನಡ", englishName = "Kannada", flag = "🇮🇳"),
    GUJARATI(code = "gu", nativeName = "ગુજરાતી", englishName = "Gujarati", flag = "🇮🇳"),
    PUNJABI(code = "pa", nativeName = "ਪੰਜਾਬੀ", englishName = "Punjabi", flag = "🇮🇳"),
    ODIA(code = "or", nativeName = "ଓଡ଼ିଆ", englishName = "Odia", flag = "🇮🇳");

    companion object {
        fun fromCode(code: String): SupportedLanguage =
            entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}
