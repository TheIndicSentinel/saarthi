package com.saarthi.core.i18n

/** User-facing strings for deterministic RAG citation footers (A3) and prompt rules (A4). */
data class CitationDisplayLabels(
    val sourcesHeader: String,
    val pageSingle: String,
    val pagesPlural: String,
    val overview: String,
    val locationUnknown: String,
    /** Locale-specific document title for the worked Sources footer example in RAG prompts (A4). */
    val rulesExampleDocTitle: String,
) {
    /** Full multi-line example matching deterministic footer shape: header + one file line. */
    fun citationRulesFooterExample(): String =
        "${sourcesHeader}\n$rulesExampleDocTitle · $pageSingle 17"
}

fun SupportedLanguage.citationDisplayLabels(): CitationDisplayLabels = when (this) {
    SupportedLanguage.ENGLISH -> CitationDisplayLabels(
        sourcesHeader = "Sources:",
        pageSingle = "page",
        pagesPlural = "pages",
        overview = "overview",
        locationUnknown = "location not marked in file",
        rulesExampleDocTitle = "Digital Personal Data Protection Act 2023",
    )
    SupportedLanguage.HINDI -> CitationDisplayLabels(
        sourcesHeader = "स्रोत:",
        pageSingle = "पृष्ठ",
        pagesPlural = "पृष्ठ",
        overview = "अवलोकन",
        locationUnknown = "फ़ाइल में पृष्ठ अंकित नहीं",
        rulesExampleDocTitle = "डिजिटल पर्सनल डेटा प्रोटेक्शन अधिनियम 2023",
    )
    SupportedLanguage.TAMIL -> CitationDisplayLabels(
        sourcesHeader = "மூலம்:",
        pageSingle = "பக்கம்",
        pagesPlural = "பக்கங்கள்",
        overview = "மேலோட்டம்",
        locationUnknown = "கோப்பில் பக்கம் குறிக்கப்படவில்லை",
        rulesExampleDocTitle = "டிஜிட்டல் தனிப்பட்ட தரவு பாதுகாப்பு சட்டம் 2023",
    )
    SupportedLanguage.TELUGU -> CitationDisplayLabels(
        sourcesHeader = "మూలాలు:",
        pageSingle = "పేజీ",
        pagesPlural = "పేజీలు",
        overview = "అవలోకనం",
        locationUnknown = "ఫైల్‌లో పేజీ గుర్తించబడలేదు",
        rulesExampleDocTitle = "డిజిటల్ వ్యక్తిగత డేటా రక్షణ చట్టం 2023",
    )
    SupportedLanguage.BENGALI -> CitationDisplayLabels(
        sourcesHeader = "সূত্র:",
        pageSingle = "পৃষ্ঠা",
        pagesPlural = "পৃষ্ঠা",
        overview = "সারাংশ",
        locationUnknown = "ফাইলে পৃষ্ঠা চিহ্নিত নেই",
        rulesExampleDocTitle = "ডিজিটাল ব্যক্তিগত ডেটা সুরক্ষা আইন 2023",
    )
    SupportedLanguage.MARATHI -> CitationDisplayLabels(
        sourcesHeader = "स्त्रोत:",
        pageSingle = "पृष्ठ",
        pagesPlural = "पृष्ठ",
        overview = "आढावा",
        locationUnknown = "फाइलमध्ये पृष्ठ चिन्हांकित नाही",
        rulesExampleDocTitle = "डिजिटल वैयक्तिक डेटा संरक्षण अधिनियम 2023",
    )
    SupportedLanguage.KANNADA -> CitationDisplayLabels(
        sourcesHeader = "ಮೂಲಗಳು:",
        pageSingle = "ಪುಟ",
        pagesPlural = "ಪುಟಗಳು",
        overview = "ಅವಲೋಕನ",
        locationUnknown = "ಫೈಲ್‌ನಲ್ಲಿ ಪುಟ ಗುರುತಿಸಲಾಗಿಲ್ಲ",
        rulesExampleDocTitle = "ಡಿಜಿಟಲ್ ವೈಯಕ್ತಿಕ ಡೇಟಾ ರಕ್ಷಣಾ ಕಾಯಿದೆ 2023",
    )
    SupportedLanguage.GUJARATI -> CitationDisplayLabels(
        sourcesHeader = "સ્રોત:",
        pageSingle = "પૃષ્ઠ",
        pagesPlural = "પૃષ્ઠો",
        overview = "અવલોકન",
        locationUnknown = "ફાઇલમાં પૃષ્ઠ ચિહ્નિત નથી",
        rulesExampleDocTitle = "ડિજિટલ વ્યક્તિગત ડેટા સંરક્ષણ અધિનિયમ 2023",
    )
    SupportedLanguage.PUNJABI -> CitationDisplayLabels(
        sourcesHeader = "ਸਰੋਤ:",
        pageSingle = "ਪੰਨਾ",
        pagesPlural = "ਪੰਨੇ",
        overview = "ਜਾਣ-ਪਛਾਣ",
        locationUnknown = "ਫਾਈਲ ਵਿੱਚ ਪੰਨਾ ਨਹੀਂ ਦਰਸਾਇਆ",
        rulesExampleDocTitle = "ਡਿਜਿਟਲ ਨਿੱਜੀ ਡਾਟਾ ਸੁਰੱਖਿਆ ਐਕਟ 2023",
    )
    SupportedLanguage.ODIA -> CitationDisplayLabels(
        sourcesHeader = "ସୂତ୍ର:",
        pageSingle = "ପୃଷ୍ଠା",
        pagesPlural = "ପୃଷ୍ଠା",
        overview = "ସାରାଂଶ",
        locationUnknown = "ଫାଇଲରେ ପୃଷ୍ଠା ଚିହ୍ନିତ ନାହିଁ",
        rulesExampleDocTitle = "ଡିଜିଟାଲ୍ ବ୍ୟକ୍ତିଗତ ଡାଟା ସୁରକ୍ଷା ଆଇନ 2023",
    )
}
