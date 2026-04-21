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

    /** Single character shown inside the Saarthi avatar circle in the chat header. */
    val firstLetter: String get() = when (this) {
        ENGLISH  -> "S"
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
        ENGLISH  -> "Your assistant · Offline"
        HINDI    -> "आपका सहायक · Offline"
        TAMIL    -> "உங்கள் உதவியாளர் · Offline"
        TELUGU   -> "మీ సహాయకుడు · Offline"
        BENGALI  -> "আপনার সহায়ক · Offline"
        MARATHI  -> "तुमचा सहाय्यक · Offline"
        KANNADA  -> "ನಿಮ್ಮ ಸಹಾಯಕ · Offline"
        GUJARATI -> "તમારો સહાયક · Offline"
        PUNJABI  -> "ਤੁਹਾਡਾ ਸਹਾਇਕ · Offline"
        ODIA     -> "ଆପଣଙ୍କ ସହାୟକ · Offline"
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

    /** Quick-suggestion chips shown on the empty chat screen. */
    val suggestions: List<String> get() = when (this) {
        ENGLISH  -> listOf("Explain something simply", "Help me write", "Summarize a file", "Plan my budget")
        HINDI    -> listOf("सरल भाषा में समझाओ", "लिखने में मदद करो", "फ़ाइल सारांश दो", "बजट बनाने में मदद करो")
        TAMIL    -> listOf("எளிமையாக விளக்கு", "எழுத உதவு", "கோப்பை சுருக்கு", "பட்ஜெட் திட்டமிடு")
        TELUGU   -> listOf("సులభంగా వివరించు", "రాయడానికి సహాయపడు", "ఫైల్ సారాంశం ఇవ్వు", "బడ్జెట్ ప్లాన్ చేయి")
        BENGALI  -> listOf("সহজভাবে বোঝাও", "লিখতে সাহায্য করো", "ফাইল সংক্ষেপ করো", "বাজেট পরিকল্পনা করো")
        MARATHI  -> listOf("सोप्या भाषेत समजावा", "लिहायला मदत करा", "फाईल सारांश द्या", "बजेट बनवा")
        KANNADA  -> listOf("ಸರಳವಾಗಿ ವಿವರಿಸಿ", "ಬರೆಯಲು ಸಹಾಯ ಮಾಡಿ", "ಫೈಲ್ ಸಾರಾಂಶ ನೀಡಿ", "ಬಜೆಟ್ ಯೋಜನೆ ಮಾಡಿ")
        GUJARATI -> listOf("સરળ ભાષામાં સમજાવો", "લખવામાં મદદ કરો", "ફાઇલ સારાંશ આપો", "બજેટ બનાવો")
        PUNJABI  -> listOf("ਸਰਲ ਭਾਸ਼ਾ ਵਿੱਚ ਸਮਝਾਓ", "ਲਿਖਣ ਵਿੱਚ ਮਦਦ ਕਰੋ", "ਫਾਈਲ ਸੰਖੇਪ ਦਿਓ", "ਬਜਟ ਬਣਾਓ")
        ODIA     -> listOf("ସରଳ ଭାଷାରେ ବୁଝାନ୍ତୁ", "ଲେଖିବାରେ ସାହାଯ୍ୟ କରନ୍ତୁ", "ଫାଇଲ ସାରାଂଶ ଦିଅନ୍ତୁ", "ବଜେଟ ଯୋଜନା କରନ୍ତୁ")
    }

    companion object {
        fun fromCode(code: String): SupportedLanguage =
            entries.firstOrNull { it.code == code } ?: HINDI
    }
}
