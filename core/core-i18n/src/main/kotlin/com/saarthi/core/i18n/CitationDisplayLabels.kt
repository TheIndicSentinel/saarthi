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
    /** Short label for multi-file title collisions (A6): "File" → "File 1: Title · page". */
    val fileLabelWord: String,
    /** B3-2 — prefix before document title when role is summary / guide / sample / circular. */
    val summaryRolePrefix: String,
    val guideRolePrefix: String,
    val sampleRolePrefix: String,
    val circularRolePrefix: String,
    /** B3-3 — standard RAG prompt bullet: corpus-bound grounding only. */
    val excerptOnlyRule: String,
    /** B3-3 — compact one-liner for 1B tier. */
    val excerptOnlyRuleCompact: String,
) {
    /** Full multi-line example matching deterministic footer shape: header + one file line. */
    fun citationRulesFooterExample(): String =
        "${sourcesHeader}\n$rulesExampleDocTitle · $pageSingle 17"

    fun fileDisambigLabel(index1Based: Int): String = "$fileLabelWord $index1Based"
}

fun SupportedLanguage.citationDisplayLabels(): CitationDisplayLabels = when (this) {
    SupportedLanguage.ENGLISH -> CitationDisplayLabels(
        sourcesHeader = "Sources:",
        pageSingle = "page",
        pagesPlural = "pages",
        overview = "overview",
        locationUnknown = "location not marked in file",
        rulesExampleDocTitle = "Digital Personal Data Protection Act 2023",
        fileLabelWord = "File",
        summaryRolePrefix = "Summary:",
        guideRolePrefix = "Guide:",
        sampleRolePrefix = "Sample:",
        circularRolePrefix = "Circular:",
        excerptOnlyRule =
            "Use only information that appears in the excerpts below — facts, numbers, names, and terms. " +
            "Do not add unstated details or outside knowledge as if they were in the attached files.",
        excerptOnlyRuleCompact =
            "Only use what appears in the excerpts; do not add unstated facts or terms.",
    )
    SupportedLanguage.HINDI -> CitationDisplayLabels(
        sourcesHeader = "स्रोत:",
        pageSingle = "पृष्ठ",
        pagesPlural = "पृष्ठ",
        overview = "अवलोकन",
        locationUnknown = "फ़ाइल में पृष्ठ अंकित नहीं",
        rulesExampleDocTitle = "डिजिटल पर्सनल डेटा प्रोटेक्शन अधिनियम 2023",
        fileLabelWord = "फ़ाइल",
        summaryRolePrefix = "सारांश:",
        guideRolePrefix = "मार्गदर्शिका:",
        sampleRolePrefix = "नमूना:",
        circularRolePrefix = "परिपत्र:",
        excerptOnlyRule =
            "केवल नीचे दिए अंशों में लिखी जानकारी का उपयोग करें — तथ्य, संख्या, नाम और शब्द। " +
            "अंशों में न लिखी बातें या बाहरी ज्ञान जोड़कर उसे फ़ाइल का हिस्सा न बताएं।",
        excerptOnlyRuleCompact =
            "केवल अंशों में लिखी बातें बताएं; अंशों में नहीं लिखी बातें न जोड़ें।",
    )
    SupportedLanguage.TAMIL -> CitationDisplayLabels(
        sourcesHeader = "மூலம்:",
        pageSingle = "பக்கம்",
        pagesPlural = "பக்கங்கள்",
        overview = "மேலோட்டம்",
        locationUnknown = "கோப்பில் பக்கம் குறிக்கப்படவில்லை",
        rulesExampleDocTitle = "டிஜிட்டல் தனிப்பட்ட தரவு பாதுகாப்பு சட்டம் 2023",
        fileLabelWord = "கோப்பு",
        summaryRolePrefix = "சுருக்கம்:",
        guideRolePrefix = "வழிகாட்டி:",
        sampleRolePrefix = "மாதிரி:",
        circularRolePrefix = "சுற்றறிக்கை:",
        excerptOnlyRule =
            "கீழே உள்ள அங்கங்களில் இருக்கும் தகவலையே பயன்படுத்துங்கள் — உண்மைகள், எண்கள், பெயர்கள், சொற்கள். " +
            "அங்கங்களில் இல்லாத விவரங்கள் அல்லது வெளிப்புற அறிவை கோப்பின் பகுதியாகச் சொல்லாதீர்கள்.",
        excerptOnlyRuleCompact =
            "அங்கங்களில் இருப்பதையே கூறுங்கள்; அங்கங்களில் இல்லாத விவரங்கள் சேர்க்காதீர்கள்.",
    )
    SupportedLanguage.TELUGU -> CitationDisplayLabels(
        sourcesHeader = "మూలాలు:",
        pageSingle = "పేజీ",
        pagesPlural = "పేజీలు",
        overview = "అవలోకనం",
        locationUnknown = "ఫైల్‌లో పేజీ గుర్తించబడలేదు",
        rulesExampleDocTitle = "డిజిటల్ వ్యక్తిగత డేటా రక్షణ చట్టం 2023",
        fileLabelWord = "ఫైల్",
        summaryRolePrefix = "సారాంశం:",
        guideRolePrefix = "గైడ్:",
        sampleRolePrefix = "నమూనా:",
        circularRolePrefix = "పరిపత్ర:",
        excerptOnlyRule =
            "కింది ఉద్ధరణలలో ఉన్న సమాచారాన్ని మాత్రమే ఉపయోగించండి — వివరాలు, సంఖ్యలు, పేర్లు, పదాలు. " +
            "ఉద్ధరణలలో లేని వివరాలు లేదా బాహ్య జ్ఞానాన్ని ఫైల్ భాగంగా చెప్పకండి.",
        excerptOnlyRuleCompact =
            "ఉద్ధరణలలో ఉన్నదాన్ని మాత్రమే చెప్పండి; లేని వివరాలు చేర్చకండి.",
    )
    SupportedLanguage.BENGALI -> CitationDisplayLabels(
        sourcesHeader = "সূত্র:",
        pageSingle = "পৃষ্ঠা",
        pagesPlural = "পৃষ্ঠা",
        overview = "সারাংশ",
        locationUnknown = "ফাইলে পৃষ্ঠা চিহ্নিত নেই",
        rulesExampleDocTitle = "ডিজিটাল ব্যক্তিগত ডেটা সুরক্ষা আইন 2023",
        fileLabelWord = "ফাইল",
        summaryRolePrefix = "সারাংশ:",
        guideRolePrefix = "গাইড:",
        sampleRolePrefix = "নমুনা:",
        circularRolePrefix = "পরিপত্র:",
        excerptOnlyRule =
            "শুধু নিচের উদ্ধৃতিপথে লিখিত তথ্য ব্যবহার করুন — বিবরণ, সংখ্যা, নাম, শব্দ। " +
            "উদ্ধৃতিপথে নেই এমন তথ্য বা বাইরের জ্ঞান ফাইলের অংশ বলে উপস্থাপ করবেন না।",
        excerptOnlyRuleCompact =
            "উদ্ধৃতিপথে যা লিখা আছে শুধু তাই বলুন; লিখা নেই এমন তথ্য যোগ করবেন না।",
    )
    SupportedLanguage.MARATHI -> CitationDisplayLabels(
        sourcesHeader = "स्त्रोत:",
        pageSingle = "पृष्ठ",
        pagesPlural = "पृष्ठ",
        overview = "आढावा",
        locationUnknown = "फाइलमध्ये पृष्ठ चिन्हांकित नाही",
        rulesExampleDocTitle = "डिजिटल वैयक्तिक डेटा संरक्षण अधिनियम 2023",
        fileLabelWord = "फाइल",
        summaryRolePrefix = "सारांश:",
        guideRolePrefix = "मार्गदर्शिका:",
        sampleRolePrefix = "नमुना:",
        circularRolePrefix = "परिपत्र:",
        excerptOnlyRule =
            "फक्त खालील अंशांमधील माहिती वापरा — तथ्ये, संख्या, नावे, शब्द. " +
            "अंशांमध्ये नसलेले तपशील किंवा बाह्य माहिती फाइलचा भाग म्हणून सांगू नका.",
        excerptOnlyRuleCompact =
            "अंशांमध्ये जे आहे तेच सांगा; अंशांमध्ये नसलेले तपशील जोडू नका.",
    )
    SupportedLanguage.KANNADA -> CitationDisplayLabels(
        sourcesHeader = "ಮೂಲಗಳು:",
        pageSingle = "ಪುಟ",
        pagesPlural = "ಪುಟಗಳು",
        overview = "ಅವಲೋಕನ",
        locationUnknown = "ಫೈಲ್‌ನಲ್ಲಿ ಪುಟ ಗುರುತಿಸಲಾಗಿಲ್ಲ",
        rulesExampleDocTitle = "ಡಿಜಿಟಲ್ ವೈಯಕ್ತಿಕ ಡೇಟಾ ರಕ್ಷಣಾ ಕಾಯಿದೆ 2023",
        fileLabelWord = "ಫೈಲ್",
        summaryRolePrefix = "ಸಾರಾಂಶ:",
        guideRolePrefix = "ಮಾರ್ಗದರ್ಶಿ:",
        sampleRolePrefix = "ಮಾದರಿ:",
        circularRolePrefix = "ಪರಿಪತ್ರ:",
        excerptOnlyRule =
            "ಕೆಳಗಿನ ಉದ್ಧರಣಿಗಳಲ್ಲಿರುವ ಮಾಹಿತಿಯನ್ನು ಮಾತ್ರ ಬಳಸಿ — ವಿವರಗಳು, ಸಂಖ್ಯೆಗಳು, ನಾಮಗಳು, ಪದಗಳು. " +
            "ಉದ್ಧರಣಿಗಳಲ್ಲಿ ಇಲ್ಲದ ವಿವರಗಳು ಅಥವಾ ಬಾಹ್ಯ ಜ್ಞಾನವನ್ನು ಫೈಲ್ ಭಾಗವಾಗಿ ಹೇಳಬೇಡಿ.",
        excerptOnlyRuleCompact =
            "ಉದ್ಧರಣಿಗಳಲ್ಲಿರುವುದನ್ನು ಮಾತ್ರ ಹೇಳಿ; ಇಲ್ಲದ ವಿವರಗಳನ್ನು ಸೇರಿಸಬೇಡಿ.",
    )
    SupportedLanguage.GUJARATI -> CitationDisplayLabels(
        sourcesHeader = "સ્રોત:",
        pageSingle = "પૃષ્ઠ",
        pagesPlural = "પૃષ્ઠો",
        overview = "અવલોકન",
        locationUnknown = "ફાઇલમાં પૃષ્ઠ ચિહ્નિત નથી",
        rulesExampleDocTitle = "ડિજિટલ વ્યક્તિગત ડેટા સંરક્ષણ અધિનિયમ 2023",
        fileLabelWord = "ફાઇલ",
        summaryRolePrefix = "સારાંશ:",
        guideRolePrefix = "માર્ગદર્શિકા:",
        sampleRolePrefix = "નમૂના:",
        circularRolePrefix = "પરિપત્ર:",
        excerptOnlyRule =
            "ફક્ત નીચેના અંશોમાં લખેલી માહિતી વાપરો — વિગતો, આંકડા, નામો, શબ્દો. " +
            "અંશોમાં ન લખેલી વિગતો અથવા બાહ્ય જ્ઞાન ફાઇલનો ભાગ કહેવા નહીં.",
        excerptOnlyRuleCompact =
            "અંશોમાં જે લખ્યું છે ફક્ત તે જ કહો; લખ્યું નથી તે વિગતો ઉમેરો નહીં.",
    )
    SupportedLanguage.PUNJABI -> CitationDisplayLabels(
        sourcesHeader = "ਸਰੋਤ:",
        pageSingle = "ਪੰਨਾ",
        pagesPlural = "ਪੰਨੇ",
        overview = "ਜਾਣ-ਪਛਾਣ",
        locationUnknown = "ਫਾਈਲ ਵਿੱਚ ਪੰਨਾ ਨਹੀਂ ਦਰਸਾਇਆ",
        rulesExampleDocTitle = "ਡਿਜਿਟਲ ਨਿੱਜੀ ਡਾਟਾ ਸੁਰੱਖਿਆ ਐਕਟ 2023",
        fileLabelWord = "ਫਾਈਲ",
        summaryRolePrefix = "ਸਾਰਾਂਸ਼:",
        guideRolePrefix = "ਗਾਈਡ:",
        sampleRolePrefix = "ਨਮੂਨਾ:",
        circularRolePrefix = "ਪਰਿਪੱਤਰ:",
        excerptOnlyRule =
            "ਸਿਰਫ਼ ਹੇਠਾਂ ਦਿੱਤੇ ਅੰਸ਼ਾਂ ਵਿੱਚ ਲਿਖੀ ਜਾਣਕਾਰੀ ਵਰਤੋ — ਤੱਥ, ਨੰਬਰ, ਨਾਮ, ਸ਼ਬਦ। " +
            "ਅੰਸ਼ਾਂ ਵਿੱਚ ਨਹੀਂ ਲਿਖੀ ਜਾਣਕਾਰੀ ਜਾਂ ਬਾਹਰਲੀ ਜਾਣਕਾਰੀ ਫਾਈਲ ਦਾ ਹਿੱਸਾ ਨਾ ਦੱਸੋ।",
        excerptOnlyRuleCompact =
            "ਅੰਸ਼ਾਂ ਵਿੱਚ ਜੋ ਲਿਖਾ ਹੈ ਸਿਰਫ਼ ਉਹ ਦੱਸੋ; ਨਾ ਲਿਖੀ ਜਾਣਕਾਰੀ ਨਾ ਜੋੜੋ।",
    )
    SupportedLanguage.ODIA -> CitationDisplayLabels(
        sourcesHeader = "ସୂତ୍ର:",
        pageSingle = "ପୃଷ୍ଠା",
        pagesPlural = "ପୃଷ୍ଠା",
        overview = "ସାରାଂଶ",
        locationUnknown = "ଫାଇଲରେ ପୃଷ୍ଠା ଚିହ୍ନିତ ନାହିଁ",
        rulesExampleDocTitle = "ଡିଜିଟାଲ୍ ବ୍ୟକ୍ତିଗତ ଡାଟା ସୁରକ୍ଷା ଆଇନ 2023",
        fileLabelWord = "ଫାଇଲ୍",
        summaryRolePrefix = "ସାରାଂଶ:",
        guideRolePrefix = "ଗାଇଡ୍:",
        sampleRolePrefix = "ନମୁନା:",
        circularRolePrefix = "ପରିପତ୍ର:",
        excerptOnlyRule =
            "ତଳେ ଦିଆ ଉଦ୍ଧୃତିଗୁଡ଼ିକରେ ଲିଖିଥିବା ସୂଚନା ବ୍ୟବହାର କରନ୍ତୁ — ତଥ୍ୟ, ସଂଖ୍ୟା, ନାମ, ଶବ୍ଦ। " +
            "ଉଦ୍ଧୃତିଗୁଡ଼ିକରେ ନଥିବା ବିବରଣୀ କିମ୍ବା ବାହ୍ୟ ଜ୍ଞାନ ଫାଇଲର ଅଂଶ କହିବେ ନାହିଁ।",
        excerptOnlyRuleCompact =
            "ଉଦ୍ଧୃତିଗୁଡ଼ିକରେ ଲିଖିଥିବା କଥା ବ୍ୟବହାର କରନ୍ତୁ; ଲିଖିନଥିବା ବିବରଣୀ ଯୋଡ଼ନ୍ତୁ ନାହିଁ।",
    )
}

/** All localized Sources block headers — used for parsing and stripping footers (A5). */
fun allCitationSourcesHeaders(): List<String> =
    SupportedLanguage.entries.map { it.citationDisplayLabels().sourcesHeader }.distinct()
