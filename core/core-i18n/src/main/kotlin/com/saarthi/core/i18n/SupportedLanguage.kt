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

    /** Language-specific display name of the assistant. */
    val appName: String get() = when (this) {
        ENGLISH  -> "Companion"
        HINDI    -> "सारथी"
        TAMIL    -> "சாரதி"
        TELUGU   -> "సారథి"
        BENGALI  -> "সারথি"
        MARATHI  -> "सारथी"
        KANNADA  -> "ಸಾರಥಿ"
        GUJARATI -> "સારથી"
        PUNJABI  -> "ਸਾਰਥੀ"
        ODIA     -> "ସାରଥୀ"
    }

    /** Short label shown inside the avatar circle (1-2 chars). */
    val avatarLabel: String get() = when (this) {
        ENGLISH  -> "Co"
        HINDI    -> "सा"
        TAMIL    -> "சா"
        TELUGU   -> "సా"
        BENGALI  -> "সা"
        MARATHI  -> "सा"
        KANNADA  -> "ಸಾ"
        GUJARATI -> "સા"
        PUNJABI  -> "ਸਾ"
        ODIA     -> "ସା"
    }

    /** Single character shown inside the Saarthi avatar circle in the chat header. */
    val firstLetter: String get() = when (this) {
        ENGLISH  -> "C"
        HINDI    -> "स"
        TAMIL    -> "ச"
        TELUGU   -> "స"
        BENGALI  -> "স"
        MARATHI  -> "स"
        KANNADA  -> "ಸ"
        GUJARATI -> "સ"
        PUNJABI  -> "ਸ"
        ODIA     -> "ସ"
    }

    /** Home-screen greeting with lamp emoji. */
    val greeting: String get() = when (this) {
        ENGLISH  -> "Hello 🪔"
        HINDI    -> "नमस्ते 🪔"
        TAMIL    -> "வணக்கம் 🪔"
        TELUGU   -> "నమస్కారం 🪔"
        BENGALI  -> "নমস্কার 🪔"
        MARATHI  -> "नमस्कार 🪔"
        KANNADA  -> "ನಮಸ್ಕಾರ 🪔"
        GUJARATI -> "નમસ્તે 🪔"
        PUNJABI  -> "ਸਤ ਸ੍ਰੀ ਅਕਾਲ 🪔"
        ODIA     -> "ନମସ୍କାର 🪔"
    }

    /** Home-screen "what would you like to explore?" subtitle. */
    val exploreSubtitle: String get() = when (this) {
        ENGLISH  -> "What would you like to explore?"
        HINDI    -> "आज क्या जानना चाहते हैं?"
        TAMIL    -> "இன்று என்ன தேட விரும்புகிறீர்கள்?"
        TELUGU   -> "ఈరోజు ఏమి తెలుసుకోవాలనుకుంటున్నారు?"
        BENGALI  -> "আজ আপনি কী জানতে চান?"
        MARATHI  -> "आज तुम्हाला काय जाणायचे आहे?"
        KANNADA  -> "ಇಂದು ನೀವು ಏನು ತಿಳಿದುಕೊಳ್ಳಲು ಬಯಸುತ್ತೀರಿ?"
        GUJARATI -> "આજે તમે શું જાણવા ઇચ્છો છો?"
        PUNJABI  -> "ਅੱਜ ਤੁਸੀਂ ਕੀ ਜਾਣਨਾ ਚਾਹੁੰਦੇ ਹੋ?"
        ODIA     -> "ଆଜି ଆପଣ କ'ଣ ଜାଣିବାକୁ ଚାହାଁନ୍ତି?"
    }

    /** Chat top-bar subtitle when idle. */
    val chatOfflineSubtitle: String get() = when (this) {
        ENGLISH  -> "Your Companion · Offline"
        HINDI    -> "आपका सारथी · Offline"
        TAMIL    -> "உங்கள் சாரதி · Offline"
        TELUGU   -> "మీ సారథి · Offline"
        BENGALI  -> "আপনার সারথি · Offline"
        MARATHI  -> "तुमचा सारथी · Offline"
        KANNADA  -> "ನಿಮ್ಮ ಸಾರಥಿ · Offline"
        GUJARATI -> "તમારો સારથી · Offline"
        PUNJABI  -> "ਤੁਹਾਡਾ ਸਾਰਥੀ · Offline"
        ODIA     -> "ଆପଣଙ୍କ ସାରଥୀ · Offline"
    }

    /** Chat top-bar subtitle while generating. */
    val thinkingText: String get() = when (this) {
        ENGLISH  -> "Thinking…"
        HINDI    -> "सोच रहा हूँ…"
        TAMIL    -> "சிந்திக்கிறேன்…"
        TELUGU   -> "ఆలోచిస్తున్నాను…"
        BENGALI  -> "ভাবছি…"
        MARATHI  -> "विचार करतोय…"
        KANNADA  -> "ಯೋಚಿಸುತ್ತಿದ್ದೇನೆ…"
        GUJARATI -> "વિચારી રહ્યો છું…"
        PUNJABI  -> "ਸੋਚ ਰਿਹਾ ਹਾਂ…"
        ODIA     -> "ଭାବୁଛି…"
    }

    /** Input field placeholder. */
    val inputHint: String get() = when (this) {
        ENGLISH  -> "Ask me anything…"
        HINDI    -> "कुछ भी पूछें…"
        TAMIL    -> "எதையும் கேளுங்கள்…"
        TELUGU   -> "ఏదైనా అడగండి…"
        BENGALI  -> "যেকোনো কিছু জিজ্ঞাসা করুন…"
        MARATHI  -> "काहीही विचारा…"
        KANNADA  -> "ಏನಾದರೂ ಕೇಳಿ…"
        GUJARATI -> "કંઈ પણ પૂછો…"
        PUNJABI  -> "ਕੁਝ ਵੀ ਪੁੱਛੋ…"
        ODIA     -> "ଯେକୌଣସି କିଛି ପଚାରନ୍ତୁ…"
    }

    /** "New Chat" button label. */
    val newChat: String get() = when (this) {
        ENGLISH  -> "New Chat"
        HINDI    -> "नई बातचीत"
        TAMIL    -> "புதிய உரையாடல்"
        TELUGU   -> "కొత్త చాట్"
        BENGALI  -> "নতুন চ্যাট"
        MARATHI  -> "नवीन चॅट"
        KANNADA  -> "ಹೊಸ ಚಾಟ್"
        GUJARATI -> "નવી વાતચીત"
        PUNJABI  -> "ਨਵੀਂ ਚੈਟ"
        ODIA     -> "ନୂଆ ଚ୍ୟାଟ"
    }

    /** Conversations drawer header. */
    val conversationsLabel: String get() = when (this) {
        ENGLISH  -> "Conversations"
        HINDI    -> "बातचीत"
        TAMIL    -> "உரையாடல்கள்"
        TELUGU   -> "సంభాషణలు"
        BENGALI  -> "কথোপকথন"
        MARATHI  -> "संभाषणे"
        KANNADA  -> "ಸಂಭಾಷಣೆಗಳು"
        GUJARATI -> "વાતચીત"
        PUNJABI  -> "ਗੱਲਬਾਤ"
        ODIA     -> "ବାର୍ତ୍ତାଳାପ"
    }

    /** "Change Language" menu item label. */
    val changeLanguage: String get() = when (this) {
        ENGLISH  -> "Change Language"
        HINDI    -> "भाषा बदलें"
        TAMIL    -> "மொழி மாற்று"
        TELUGU   -> "భాష మార్చు"
        BENGALI  -> "ভাষা পরিবর্তন"
        MARATHI  -> "भाषा बदला"
        KANNADA  -> "ಭಾಷೆ ಬದಲಿಸಿ"
        GUJARATI -> "ભાષા બદલો"
        PUNJABI  -> "ਭਾਸ਼ਾ ਬਦਲੋ"
        ODIA     -> "ଭାଷା ପରିବର୍ତ୍ତନ"
    }

    /** "Change AI Model" label. */
    val changeModel: String get() = when (this) {
        ENGLISH  -> "Change AI Model"
        HINDI    -> "AI मॉडल बदलें"
        TAMIL    -> "AI மாடல் மாற்று"
        TELUGU   -> "AI మోడల్ మార్చు"
        BENGALI  -> "AI মডেল পরিবর্তন"
        MARATHI  -> "AI मॉडेल बदला"
        KANNADA  -> "AI ಮಾದರಿ ಬದಲಿಸಿ"
        GUJARATI -> "AI મૉડલ બદલો"
        PUNJABI  -> "AI ਮਾਡਲ ਬਦਲੋ"
        ODIA     -> "AI ମଡେଲ ବଦଳନ୍ତୁ"
    }

    /** Quick-suggestion chips shown on the empty chat screen — India-relevant. */
    val suggestions: List<String> get() = when (this) {
        ENGLISH  -> listOf(
            "Explain a government scheme",
            "Help me write a letter",
            "Farming tips for this season",
            "How to save money with UPI",
        )
        HINDI    -> listOf(
            "PM योजना के बारे में बताओ",
            "खेती की सलाह दो",
            "पत्र लिखने में मदद करो",
            "UPI से पैसे बचाने के तरीके",
        )
        TAMIL    -> listOf(
            "அரசு திட்டம் பற்றி சொல்",
            "விவசாய ஆலோசனை தா",
            "கடிதம் எழுத உதவு",
            "UPI பணம் சேமிப்பு",
        )
        TELUGU   -> listOf(
            "ప్రభుత్వ పథకం గురించి చెప్పు",
            "వ్యవసాయ సలహా ఇవ్వు",
            "ఉత్తరం రాయడంలో సహాయపడు",
            "UPI తో డబ్బు ఆదా",
        )
        BENGALI  -> listOf(
            "সরকারি প্রকল্প সম্পর্কে বলো",
            "কৃষি পরামর্শ দাও",
            "চিঠি লিখতে সাহায্য করো",
            "UPI দিয়ে সঞ্চয়",
        )
        MARATHI  -> listOf(
            "सरकारी योजना सांगा",
            "शेतीची माहिती द्या",
            "पत्र लिहायला मदत करा",
            "UPI ने पैसे वाचवा",
        )
        KANNADA  -> listOf(
            "ಸರ್ಕಾರಿ ಯೋಜನೆ ಹೇಳಿ",
            "ಕೃಷಿ ಸಲಹೆ ನೀಡಿ",
            "ಪತ್ರ ಬರೆಯಲು ಸಹಾಯ ಮಾಡಿ",
            "UPI ಉಳಿತಾಯ ಸಲಹೆ",
        )
        GUJARATI -> listOf(
            "સરકારી યોજના સમજાવો",
            "ખેતી અંગે સલાહ આપો",
            "પત્ર લખવામાં મદદ કરો",
            "UPI થી બચત",
        )
        PUNJABI  -> listOf(
            "ਸਰਕਾਰੀ ਯੋਜਨਾ ਦੱਸੋ",
            "ਖੇਤੀ ਸਲਾਹ ਦਿਓ",
            "ਚਿੱਠੀ ਲਿਖਣ ਵਿੱਚ ਮਦਦ",
            "UPI ਨਾਲ ਬੱਚਤ",
        )
        ODIA     -> listOf(
            "ସରକାରୀ ଯୋଜନା କୁହନ୍ତୁ",
            "କୃଷି ପରାମର୍ଶ ଦିଅନ୍ତୁ",
            "ଚିଠି ଲେଖିବାରେ ସାହାଯ୍ୟ",
            "UPI ସଞ୍ଚୟ ଉପାୟ",
        )
    }

    /** Instruction appended to system prompt so the model responds in this language. */
    val systemPromptInstruction: String get() =
        "IMPORTANT: You must respond ONLY in $nativeName ($englishName). " +
        "Never switch to Hindi or any other language unless the user explicitly asks you to."

    companion object {
        fun fromCode(code: String): SupportedLanguage =
            entries.firstOrNull { it.code == code } ?: HINDI
    }
}
