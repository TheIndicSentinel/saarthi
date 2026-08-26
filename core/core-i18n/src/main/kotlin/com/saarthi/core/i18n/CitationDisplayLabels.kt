package com.saarthi.core.i18n

/** User-facing strings for deterministic RAG citation footers (A3). */
data class CitationDisplayLabels(
    val sourcesHeader: String,
    val pageSingle: String,
    val pagesPlural: String,
    val overview: String,
    val locationUnknown: String,
)

fun SupportedLanguage.citationDisplayLabels(): CitationDisplayLabels = when (this) {
    SupportedLanguage.ENGLISH -> CitationDisplayLabels(
        sourcesHeader = "Sources:",
        pageSingle = "page",
        pagesPlural = "pages",
        overview = "overview",
        locationUnknown = "location not marked in file",
    )
    SupportedLanguage.HINDI -> CitationDisplayLabels(
        sourcesHeader = "स्रोत:",
        pageSingle = "पृष्ठ",
        pagesPlural = "पृष्ठ",
        overview = "अवलोकन",
        locationUnknown = "फ़ाइल में पृष्ठ अंकित नहीं",
    )
    SupportedLanguage.TAMIL -> CitationDisplayLabels(
        sourcesHeader = "மூலம்:",
        pageSingle = "பக்கம்",
        pagesPlural = "பக்கங்கள்",
        overview = "மேலோட்டம்",
        locationUnknown = "கோப்பில் பக்கம் குறிக்கப்படவில்லை",
    )
    SupportedLanguage.TELUGU -> CitationDisplayLabels(
        sourcesHeader = "మూలాలు:",
        pageSingle = "పేజీ",
        pagesPlural = "పేజీలు",
        overview = "అవలోకనం",
        locationUnknown = "ఫైల్‌లో పేజీ గుర్తించబడలేదు",
    )
    SupportedLanguage.BENGALI -> CitationDisplayLabels(
        sourcesHeader = "সূত্র:",
        pageSingle = "পৃষ্ঠা",
        pagesPlural = "পৃষ্ঠা",
        overview = "সারাংশ",
        locationUnknown = "ফাইলে পৃষ্ঠা চিহ্নিত নেই",
    )
    SupportedLanguage.MARATHI -> CitationDisplayLabels(
        sourcesHeader = "स्त्रोत:",
        pageSingle = "पृष्ठ",
        pagesPlural = "पृष्ठ",
        overview = "आढावा",
        locationUnknown = "फाइलमध्ये पृष्ठ चिन्हांकित नाही",
    )
    SupportedLanguage.KANNADA -> CitationDisplayLabels(
        sourcesHeader = "ಮೂಲಗಳು:",
        pageSingle = "ಪುಟ",
        pagesPlural = "ಪುಟಗಳು",
        overview = "ಅವಲೋಕನ",
        locationUnknown = "ಫೈಲ್‌ನಲ್ಲಿ ಪುಟ ಗುರುತಿಸಲಾಗಿಲ್ಲ",
    )
    SupportedLanguage.GUJARATI -> CitationDisplayLabels(
        sourcesHeader = "સ્રોત:",
        pageSingle = "પૃષ્ઠ",
        pagesPlural = "પૃષ્ઠો",
        overview = "અવલોકન",
        locationUnknown = "ફાઇલમાં પૃષ્ઠ ચિહ્નિત નથી",
    )
    SupportedLanguage.PUNJABI -> CitationDisplayLabels(
        sourcesHeader = "ਸਰੋਤ:",
        pageSingle = "ਪੰਨਾ",
        pagesPlural = "ਪੰਨੇ",
        overview = "ਜਾਣ-ਪਛਾਣ",
        locationUnknown = "ਫਾਈਲ ਵਿੱਚ ਪੰਨਾ ਨਹੀਂ ਦਰਸਾਇਆ",
    )
    SupportedLanguage.ODIA -> CitationDisplayLabels(
        sourcesHeader = "ସୂତ୍ର:",
        pageSingle = "ପୃଷ୍ଠା",
        pagesPlural = "ପୃଷ୍ଠା",
        overview = "ସାରାଂଶ",
        locationUnknown = "ଫାଇଲରେ ପୃଷ୍ଠା ଚିହ୍ନିତ ନାହିଁ",
    )
}
