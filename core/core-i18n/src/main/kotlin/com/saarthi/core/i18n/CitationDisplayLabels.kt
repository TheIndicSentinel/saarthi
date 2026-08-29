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
    /** Wave 6 P27 — shown when amounts/section refs in the answer lack excerpt support. */
    val groundednessCaveat: String,
    /** R4 — session document manifest line prefix (filenames follow). */
    val documentsInChatHeader: String,
    /** R4 — prefix before this-turn attachment short names. */
    val newFilesThisTurnPrefix: String,
    /** R4 — instruction after this-turn attachment names. */
    val newFilesThisTurnSuffix: String,
    /** R4 — intro before unreadable attachment list in the RAG block. */
    val unreadableFilesIntro: String,
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
        groundednessCaveat =
            "Note: Some amounts or section references in this reply were not found in the excerpts. " +
            "Please verify against the original document.",
        documentsInChatHeader = "Documents in this chat:",
        newFilesThisTurnPrefix = "New files this turn: ",
        newFilesThisTurnSuffix =
            ". Answer from these files; do not reuse answers about earlier documents.\n\n",
        unreadableFilesIntro =
            "Files attached this turn that could NOT be read (do not cite them; do not pretend to know their contents):",
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
        groundednessCaveat =
            "नोट: इस उत्तर में कुछ राशि या धारा संदर्भ अंशों में नहीं मिले। मूल दस्तावेज़ से सत्यापित करें।",
        documentsInChatHeader = "इस चैट में दस्तावेज़:",
        newFilesThisTurnPrefix = "इस बार जोड़ी गई फ़ाइलें: ",
        newFilesThisTurnSuffix =
            ". इन फ़ाइलों से उत्तर दें; पहले के दस्तावेज़ों के उत्तर न दोहराएँ.\n\n",
        unreadableFilesIntro =
            "इस बार जोड़ी गई फ़ाइलें जो पढ़ी नहीं जा सकीं (उन्हें उद्धृत न करें; उनकी सामग्री जानने का दिखावा न करें):",
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
        groundednessCaveat =
            "குறிப்பு: இந்த பதிலில் சில தொகை அல்லது பிரிவு குறிப்புகள் அங்கங்களில் காணப்படவில்லை. " +
            "அசல் ஆவணத்தில் சரிபார்க்கவும்.",
        documentsInChatHeader = "இந்த உரையாடலில் ஆவணங்கள்:",
        newFilesThisTurnPrefix = "இந்த திருப்பில் புதிய கோப்புகள்: ",
        newFilesThisTurnSuffix =
            ". இந்த கோப்புகளிலிருந்து பதிலளிக்கவும்; முந்தைய ஆவணங்களின் பதில்களை மீண்டும் பயன்படுத்த வேண்டாம்.\n\n",
        unreadableFilesIntro =
            "இந்த திருப்பில் படிக்க முடியாத கோப்புகள் (அவற்றை மேற்கோள் காட்ட வேண்டாம்; உள்ளடக்கம் தெரிந்ததாக நடிக்க வேண்டாம்):",
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
        groundednessCaveat =
            "గమనిక: ఈ సమాధానంలో కొన్ని మొత్తాలు లేదా విభాగం సూచనలు ఉద్ధరణలలో కనిపించలేదు. " +
            "మూల పత్రంలో ధృవీకరించండి.",
        documentsInChatHeader = "ఈ చాట్‌లో పత్రాలు:",
        newFilesThisTurnPrefix = "ఈ మలుపులో కొత్త ఫైల్‌లు: ",
        newFilesThisTurnSuffix =
            ". ఈ ఫైల్‌ల నుండి సమాధానం ఇవ్వండి; మునుపటి పత్రాల సమాధానాలను మళ్లీ ఉపయోగించకండి.\n\n",
        unreadableFilesIntro =
            "ఈ మలుపులో చదవలేని ఫైల్‌లు (వాటిని ఉదహరించకండి; విషయం తెలిసినట్లు నటించకండి):",
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
        groundednessCaveat =
            "নোট: এই উত্তরের কিছু পরিমাণ বা ধারা উল্লেখ উদ্ধৃতিপথে পাওয়া যায়নি। " +
            "মূল নথিতে যাচাই করুন।",
        documentsInChatHeader = "এই চ্যাটে নথি:",
        newFilesThisTurnPrefix = "এই বারে যোগ করা ফাইল: ",
        newFilesThisTurnSuffix =
            ". এই ফাইলগুলি থেকে উত্তর দিন; পূর্বের নথির উত্তর পুনরায় ব্যবহার করবেন না.\n\n",
        unreadableFilesIntro =
            "এই বারে যোগ করা ফাইল যা পড়া যায়নি (উদ্ধৃত করবেন না; বিষয়বস্তু জানার ভান করবেন না):",
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
        groundednessCaveat =
            "टीप: या उत्तरातील काही रक्कम किंवा विभाग संदर्भ अंशांमध्ये सापडले नाहीत. " +
            "मूळ दस्तऐवजात तपासा.",
        documentsInChatHeader = "या चॅटमधील दस्तऐवज:",
        newFilesThisTurnPrefix = "या वेळी जोडलेली फाइल: ",
        newFilesThisTurnSuffix =
            ". या फाइलांमधून उत्तर द्या; मागील दस्तऐवजांचे उत्तर पुन्हा वापरू नका.\n\n",
        unreadableFilesIntro =
            "या वेळी जोडलेली फाइल जी वाचता आली नाही (त्यांना उद्धृत करू नका; मजकूर माहित असल्याचे दाखवू नका):",
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
        groundednessCaveat =
            "ಗಮನಿಸಿ: ಈ ಉತ್ತರದಲ್ಲಿನ ಕೆಲವು ಮೊತ್ತಗಳು ಅಥವಾ ವಿಭಾಗ ಉಲ್ಲೇಖಗಳು ಉದ್ಧರಣಿಗಳಲ್ಲಿ ಕಂಡುಬಂದಿಲ್ಲ. " +
            "ಮೂಲ ದಾಖಲೆಯಲ್ಲಿ ಪರಿಶೀಲಿಸಿ.",
        documentsInChatHeader = "ಈ ಚಾಟ್‌ನಲ್ಲಿನ ದಾಖಲೆಗಳು:",
        newFilesThisTurnPrefix = "ಈ ತಿರುವರಿಯಲ್ಲಿ ಹೊಸ ಫೈಲ್‌ಗಳು: ",
        newFilesThisTurnSuffix =
            ". ಈ ಫೈಲ್‌ಗಳಿಂದ ಉತ್ತರಿಸಿ; ಹಿಂದಿನ ದಾಖಲೆಗಳ ಉತ್ತರಗಳನ್ನು ಮರುಬಳಕೆ ಮಾಡಬೇಡಿ.\n\n",
        unreadableFilesIntro =
            "ಈ ತಿರುವರಿಯಲ್ಲಿ ಓದಲಾಗದ ಫೈಲ್‌ಗಳು (ಅವುಗಳನ್ನು ಉಲ್ಲೇಖಿಸಬೇಡಿ; ವಿಷಯ ತಿಳಿದಿದೆ ಎಂದು ನಟಿಸಬೇಡಿ):",
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
        groundednessCaveat =
            "નોંધ: આ જવાબમાં કેટલાક રકમ અથવા વિભાગ સંદર્ભ અંશોમાં મળ્યા નથી. " +
            "મૂળ દસ્તાવેજમાં ચકાસો.",
        documentsInChatHeader = "આ ચેટમાં દસ્તાવેજો:",
        newFilesThisTurnPrefix = "આ વળતરમાં નવી ફાઇલો: ",
        newFilesThisTurnSuffix =
            ". આ ફાઇલોમાંથી જવાબ આપો; પહેલાના દસ્તાવેજોના જવાબ ફરી વાપરશો નહીં.\n\n",
        unreadableFilesIntro =
            "આ વળતરમાં વાંચી શકાય નહીં તેવી ફાઇલો (ઉદ્ધૃત કરશો નહીં; સામગ્રી જાણતા હોવાનો દેખાવ ન કરો):",
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
        groundednessCaveat =
            "ਨੋਟ: ਇਸ ਜਵਾਬ ਵਿੱਚ ਕੁਝ ਰਕਮਾਂ ਜਾਂ ਧਾਰਾ ਹਵਾਲੇ ਅੰਸ਼ਾਂ ਵਿੱਚ ਨਹੀਂ ਮਿਲੇ। " +
            "ਮੂਲ ਦਸਤਾਵੇਜ਼ ਵਿੱਚ ਪੁਸ਼ਟੀ ਕਰੋ।",
        documentsInChatHeader = "ਇਸ ਚੈਟ ਵਿੱਚ ਦਸਤਾਵੇਜ਼:",
        newFilesThisTurnPrefix = "ਇਸ ਵਾਰ ਜੋੜੀਆਂ ਫਾਈਲਾਂ: ",
        newFilesThisTurnSuffix =
            ". ਇਨ੍ਹਾਂ ਫਾਈਲਾਂ ਤੋਂ ਜਵਾਬ ਦਿਓ; ਪਿਛਲੇ ਦਸਤਾਵੇਜ਼ਾਂ ਦੇ ਜਵਾਬ ਦੁਬਾਰਾ ਨਾ ਵਰਤੋ.\n\n",
        unreadableFilesIntro =
            "ਇਸ ਵਾਰ ਜੋੜੀਆਂ ਫਾਈਲਾਂ ਜੋ ਪੜ੍ਹੀ ਨਹੀਂ ਜਾ ਸਕੀ (ਉਹਨਾਂ ਨੂੰ ਹਵਾਲਾ ਨਾ ਦਿਓ; ਸਮੱਗਰੀ ਜਾਣਨ ਦਾ ਦਿਖਾਵਾ ਨਾ ਕਰੋ):",
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
        groundednessCaveat =
            "ଟିପ୍ପଣୀ: ଏହି ଉତ୍ତରର କେତେକ ରାଶି କିମ୍ବା ଧାରା ସନ୍ଦର୍ଭ ଉଦ୍ଧୃତିଗୁଡ଼ିକରେ ମିଳିଲା ନାହିଁ। " +
            "ମୂଳ ଦଲିଲରେ ଯାଞ୍ଚ କରନ୍ତୁ।",
        documentsInChatHeader = "ଏହି ଚାଟରେ ଦଲିଲ:",
        newFilesThisTurnPrefix = "ଏହି ବାର ଯୋଡ଼ା ଫାଇଲ:",
        newFilesThisTurnSuffix =
            ". ଏହି ଫାଇଲରୁ ଉତ୍ତର ଦିଅନ୍ତୁ; ପୂର୍ବ ଦଲିଲର ଉତ୍ତର ପୁନର୍ବ୍ୟବହାର କରନ୍ତୁ ନାହିଁ.\n\n",
        unreadableFilesIntro =
            "ଏହି ବାର ଯୋଡ଼ା ଫାଇଲ ଯାହା ପଢ଼ି ହେଲା ନାହିଁ (ଉଦ୍ଧୃତ କରନ୍ତୁ ନାହିଁ; ବିଷୟବସ୍ତୁ ଜାଣିଛନ୍ତି ଭଳି ଦେଖାନ୍ତୁ ନାହିଁ):",
    )
}

/** All localized Sources block headers — used for parsing and stripping footers (A5). */
fun allCitationSourcesHeaders(): List<String> =
    SupportedLanguage.entries.map { it.citationDisplayLabels().sourcesHeader }.distinct()
