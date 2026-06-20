package com.saarthi.core.i18n

import java.time.LocalDate

/**
 * One day's wisdom — a short, region-neutral thought shown in the user's
 * selected language. Used by the Home-screen "Thought of the day" card and
 * the daily wisdom notification.
 *
 * The set is deliberately small and high-quality (10 universal thoughts)
 * rather than a long churn — the user is expected to encounter the same set
 * across ~10 days, and recognising one from earlier is the point.
 *
 * [translations] holds the native wording for each language; [localized]
 * falls back to [english] for any language not present. [sanskrit] is kept
 * for the daily-wisdom notification and is NOT shown on the home card
 * (which is fully localized — no Sanskrit-only content for any user).
 */
data class DailyWisdom(
    val sanskrit: String,
    val english: String,
    val translations: Map<SupportedLanguage, String> = emptyMap(),
) {
    /** The thought in [lang]; falls back to English when no translation exists. */
    fun localized(lang: SupportedLanguage): String =
        if (lang == SupportedLanguage.ENGLISH) english else translations[lang] ?: english
}

object DailyWisdomCatalog {

    /**
     * Curated daily wisdom — 10 entries, each localized into every supported
     * language. Cycled deterministically by day-of-year so every device on
     * the same day shows the same thought.
     */
    val entries: List<DailyWisdom> = listOf(
        DailyWisdom(
            "विद्या ददाति विनयम्", "Knowledge gives humility",
            mapOf(
                SupportedLanguage.HINDI to "ज्ञान विनम्रता देता है",
                SupportedLanguage.TAMIL to "அறிவு பணிவைத் தரும்",
                SupportedLanguage.TELUGU to "జ్ఞానం వినయాన్ని ఇస్తుంది",
                SupportedLanguage.BENGALI to "জ্ঞান নম্রতা দেয়",
                SupportedLanguage.MARATHI to "ज्ञान नम्रता देते",
                SupportedLanguage.KANNADA to "ಜ್ಞಾನ ವಿನಯವನ್ನು ನೀಡುತ್ತದೆ",
                SupportedLanguage.GUJARATI to "જ્ઞાન નમ્રતા આપે છે",
                SupportedLanguage.PUNJABI to "ਗਿਆਨ ਨਿਮਰਤਾ ਦਿੰਦਾ ਹੈ",
                SupportedLanguage.ODIA to "ଜ୍ଞାନ ନମ୍ରତା ଦିଏ",
            ),
        ),
        DailyWisdom(
            "सत्यमेव जयते", "Truth alone triumphs",
            mapOf(
                SupportedLanguage.HINDI to "सत्य की ही जीत होती है",
                SupportedLanguage.TAMIL to "உண்மையே வெல்லும்",
                SupportedLanguage.TELUGU to "సత్యమే గెలుస్తుంది",
                SupportedLanguage.BENGALI to "সত্যেরই জয় হয়",
                SupportedLanguage.MARATHI to "सत्याचाच विजय होतो",
                SupportedLanguage.KANNADA to "ಸತ್ಯಕ್ಕೆ ಮಾತ್ರ ಜಯ",
                SupportedLanguage.GUJARATI to "સત્યનો જ વિજય થાય છે",
                SupportedLanguage.PUNJABI to "ਸੱਚ ਦੀ ਹੀ ਜਿੱਤ ਹੁੰਦੀ ਹੈ",
                SupportedLanguage.ODIA to "ସତ୍ୟର ହିଁ ଜୟ ହୁଏ",
            ),
        ),
        DailyWisdom(
            "वसुधैव कुटुम्बकम्", "The world is one family",
            mapOf(
                SupportedLanguage.HINDI to "सारा संसार एक परिवार है",
                SupportedLanguage.TAMIL to "உலகமே ஒரு குடும்பம்",
                SupportedLanguage.TELUGU to "ప్రపంచమే ఒక కుటుంబం",
                SupportedLanguage.BENGALI to "সারা বিশ্ব এক পরিবার",
                SupportedLanguage.MARATHI to "सारे जग एक कुटुंब आहे",
                SupportedLanguage.KANNADA to "ಇಡೀ ಜಗತ್ತು ಒಂದು ಕುಟುಂಬ",
                SupportedLanguage.GUJARATI to "આખું વિશ્વ એક પરિવાર છે",
                SupportedLanguage.PUNJABI to "ਸਾਰਾ ਸੰਸਾਰ ਇੱਕ ਪਰਿਵਾਰ ਹੈ",
                SupportedLanguage.ODIA to "ସାରା ବିଶ୍ୱ ଏକ ପରିବାର",
            ),
        ),
        DailyWisdom(
            "योगः कर्मसु कौशलम्", "Skill in action is excellence",
            mapOf(
                SupportedLanguage.HINDI to "कुशलता से किया काम ही श्रेष्ठ है",
                SupportedLanguage.TAMIL to "செயலில் திறமையே சிறப்பு",
                SupportedLanguage.TELUGU to "పనిలో నైపుణ్యమే గొప్పతనం",
                SupportedLanguage.BENGALI to "কাজে দক্ষতাই শ্রেষ্ঠত্ব",
                SupportedLanguage.MARATHI to "कामातील कौशल्य हेच श्रेष्ठत्व",
                SupportedLanguage.KANNADA to "ಕೆಲಸದಲ್ಲಿ ಕೌಶಲ್ಯವೇ ಶ್ರೇಷ್ಠತೆ",
                SupportedLanguage.GUJARATI to "કામમાં કુશળતા જ શ્રેષ્ઠતા",
                SupportedLanguage.PUNJABI to "ਕੰਮ ਵਿੱਚ ਹੁਨਰ ਹੀ ਉੱਤਮਤਾ ਹੈ",
                SupportedLanguage.ODIA to "କାମରେ ଦକ୍ଷତା ହିଁ ଶ୍ରେଷ୍ଠତା",
            ),
        ),
        DailyWisdom(
            "सर्वे भवन्तु सुखिनः", "May everyone be happy",
            mapOf(
                SupportedLanguage.HINDI to "सब सुखी रहें",
                SupportedLanguage.TAMIL to "அனைவரும் மகிழ்ச்சியாக இருக்கட்டும்",
                SupportedLanguage.TELUGU to "అందరూ సంతోషంగా ఉండాలి",
                SupportedLanguage.BENGALI to "সবাই সুখী হোক",
                SupportedLanguage.MARATHI to "सर्व सुखी होवोत",
                SupportedLanguage.KANNADA to "ಎಲ್ಲರೂ ಸುಖವಾಗಿರಲಿ",
                SupportedLanguage.GUJARATI to "સૌ સુખી રહે",
                SupportedLanguage.PUNJABI to "ਸਾਰੇ ਖ਼ੁਸ਼ ਰਹਿਣ",
                SupportedLanguage.ODIA to "ସମସ୍ତେ ସୁଖୀ ହୁଅନ୍ତୁ",
            ),
        ),
        DailyWisdom(
            "उद्यमेन हि सिध्यन्ति कार्याणि", "Effort, not wishes, gets things done",
            mapOf(
                SupportedLanguage.HINDI to "काम मेहनत से होता है, इच्छा से नहीं",
                SupportedLanguage.TAMIL to "ஆசையால் அல்ல, முயற்சியால் காரியம் ஆகும்",
                SupportedLanguage.TELUGU to "కోరికతో కాదు, కృషితోనే పని జరుగుతుంది",
                SupportedLanguage.BENGALI to "ইচ্ছায় নয়, পরিশ্রমেই কাজ হয়",
                SupportedLanguage.MARATHI to "इच्छेने नव्हे, मेहनतीने काम होते",
                SupportedLanguage.KANNADA to "ಆಸೆಯಿಂದಲ್ಲ, ಶ್ರಮದಿಂದಲೇ ಕೆಲಸ ಆಗುತ್ತದೆ",
                SupportedLanguage.GUJARATI to "ઇચ્છાથી નહીં, મહેનતથી કામ થાય છે",
                SupportedLanguage.PUNJABI to "ਇੱਛਾ ਨਾਲ ਨਹੀਂ, ਮਿਹਨਤ ਨਾਲ ਕੰਮ ਹੁੰਦਾ ਹੈ",
                SupportedLanguage.ODIA to "ଇଚ୍ଛାରେ ନୁହେଁ, ପରିଶ୍ରମରେ କାମ ହୁଏ",
            ),
        ),
        DailyWisdom(
            "परोपकाराय फलन्ति वृक्षाः", "Live to help others",
            mapOf(
                SupportedLanguage.HINDI to "दूसरों की मदद के लिए जिएँ",
                SupportedLanguage.TAMIL to "பிறருக்கு உதவ வாழ்க",
                SupportedLanguage.TELUGU to "ఇతరులకు సహాయం చేయడానికి జీవించండి",
                SupportedLanguage.BENGALI to "অন্যকে সাহায্য করতে বাঁচুন",
                SupportedLanguage.MARATHI to "इतरांना मदत करण्यासाठी जगा",
                SupportedLanguage.KANNADA to "ಇತರರಿಗೆ ಸಹಾಯ ಮಾಡಲು ಬದುಕಿ",
                SupportedLanguage.GUJARATI to "બીજાને મદદ કરવા જીવો",
                SupportedLanguage.PUNJABI to "ਦੂਜਿਆਂ ਦੀ ਮਦਦ ਲਈ ਜੀਓ",
                SupportedLanguage.ODIA to "ଅନ୍ୟକୁ ସାହାଯ୍ୟ କରିବାକୁ ବଞ୍ଚନ୍ତୁ",
            ),
        ),
        DailyWisdom(
            "धैर्यं सर्वत्र साधनम्", "Patience is the key everywhere",
            mapOf(
                SupportedLanguage.HINDI to "हर जगह धैर्य ही कुंजी है",
                SupportedLanguage.TAMIL to "எங்கும் பொறுமையே திறவுகோல்",
                SupportedLanguage.TELUGU to "ప్రతిచోటా ఓర్పే తాళం",
                SupportedLanguage.BENGALI to "সর্বত্র ধৈর্যই চাবিকাঠি",
                SupportedLanguage.MARATHI to "सर्वत्र संयमच गुरुकिल्ली आहे",
                SupportedLanguage.KANNADA to "ಎಲ್ಲೆಡೆ ತಾಳ್ಮೆಯೇ ಕೀಲಿ",
                SupportedLanguage.GUJARATI to "દરેક જગ્યાએ ધીરજ જ ચાવી છે",
                SupportedLanguage.PUNJABI to "ਹਰ ਥਾਂ ਸਬਰ ਹੀ ਕੁੰਜੀ ਹੈ",
                SupportedLanguage.ODIA to "ସବୁଠାରେ ଧୈର୍ଯ୍ୟ ହିଁ ଚାବି",
            ),
        ),
        DailyWisdom(
            "शुभस्य शीघ्रम्", "Do the good thing quickly",
            mapOf(
                SupportedLanguage.HINDI to "अच्छा काम तुरंत करें",
                SupportedLanguage.TAMIL to "நல்ல காரியத்தை உடனே செய்",
                SupportedLanguage.TELUGU to "మంచి పని వెంటనే చేయండి",
                SupportedLanguage.BENGALI to "ভালো কাজ দ্রুত করুন",
                SupportedLanguage.MARATHI to "चांगले काम लगेच करा",
                SupportedLanguage.KANNADA to "ಒಳ್ಳೆಯ ಕೆಲಸವನ್ನು ತಕ್ಷಣ ಮಾಡಿ",
                SupportedLanguage.GUJARATI to "સારું કામ તરત કરો",
                SupportedLanguage.PUNJABI to "ਚੰਗਾ ਕੰਮ ਤੁਰੰਤ ਕਰੋ",
                SupportedLanguage.ODIA to "ଭଲ କାମ ତୁରନ୍ତ କରନ୍ତୁ",
            ),
        ),
        DailyWisdom(
            "क्रोधाद्भवति सम्मोहः", "Anger clouds the mind",
            mapOf(
                SupportedLanguage.HINDI to "क्रोध मन को धुंधला कर देता है",
                SupportedLanguage.TAMIL to "கோபம் மனதை மறைக்கும்",
                SupportedLanguage.TELUGU to "కోపం మనసును మసకబారుస్తుంది",
                SupportedLanguage.BENGALI to "রাগ মনকে আচ্ছন্ন করে",
                SupportedLanguage.MARATHI to "राग मन गढूळ करतो",
                SupportedLanguage.KANNADA to "ಕೋಪ ಮನಸ್ಸನ್ನು ಮಂಕಾಗಿಸುತ್ತದೆ",
                SupportedLanguage.GUJARATI to "ગુસ્સો મનને ધૂંધળું કરે છે",
                SupportedLanguage.PUNJABI to "ਗੁੱਸਾ ਮਨ ਨੂੰ ਧੁੰਦਲਾ ਕਰ ਦਿੰਦਾ ਹੈ",
                SupportedLanguage.ODIA to "କ୍ରୋଧ ମନକୁ ଅସ୍ପଷ୍ଟ କରେ",
            ),
        ),
    )

    /**
     * Picks the wisdom for [date] deterministically — every device,
     * every install, sees the same thought on the same calendar day.
     * Uses day-of-year (1..366) so the cycle resets cleanly each year
     * and there is no off-by-one across month boundaries.
     */
    fun forDate(date: LocalDate = LocalDate.now()): DailyWisdom {
        val index = (date.dayOfYear - 1).mod(entries.size)
        return entries[index]
    }
}
