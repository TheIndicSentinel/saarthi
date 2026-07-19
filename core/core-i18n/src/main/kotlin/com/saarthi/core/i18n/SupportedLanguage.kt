package com.saarthi.core.i18n

enum class SupportedLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
) {
    ENGLISH(code = "en", nativeName = "English", englishName = "English"),
    HINDI(code = "hi", nativeName = "हिन्दी", englishName = "Hindi"),
    TAMIL(code = "ta", nativeName = "தமிழ்", englishName = "Tamil"),
    TELUGU(code = "te", nativeName = "తెలుగు", englishName = "Telugu"),
    BENGALI(code = "bn", nativeName = "বাংলা", englishName = "Bengali"),
    MARATHI(code = "mr", nativeName = "मराठी", englishName = "Marathi"),
    KANNADA(code = "kn", nativeName = "ಕನ್ನಡ", englishName = "Kannada"),
    GUJARATI(code = "gu", nativeName = "ગુજરાતી", englishName = "Gujarati"),
    PUNJABI(code = "pa", nativeName = "ਪੰਜਾਬੀ", englishName = "Punjabi"),
    ODIA(code = "or", nativeName = "ଓଡ଼ିଆ", englishName = "Odia");

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

    /**
     * Time-of-day greeting in this language (morning / afternoon / evening /
     * night), chosen by [hour] (0..23). Used on the home header and fed into
     * the chat prompt's time context so both stay in sync.
     *
     * Bands match [buildTimeContext] in ChatRepositoryImpl exactly — morning
     * 5–11, afternoon 12–16, evening 17–20, night otherwise. The previous
     * 3-band version had no night slot, so any hour before noon (incl. 2 AM)
     * resolved to "Good morning" — the bug the user hit at 01:59.
     */
    fun timeGreeting(hour: Int): String {
        val slot = when (hour) {
            in 5..11  -> 0
            in 12..16 -> 1
            in 17..20 -> 2
            else      -> 3
        }
        return when (this) {
            ENGLISH  -> listOf("Good morning", "Good afternoon", "Good evening", "Good night")
            // NBSP ( ) joins the two words: Compose mis-measures Devanagari
            // widths and wrapped "शुभ संध्या" onto two lines on the home
            // greeting even though it fits — a no-break space makes the split
            // impossible while keeping the full font size (field report, hi+mr).
            HINDI    -> listOf("सुप्रभात", "शुभ दोपहर", "शुभ संध्या", "शुभ रात्रि")
            TAMIL    -> listOf("காலை வணக்கம்", "மதிய வணக்கம்", "மாலை வணக்கம்", "இனிய இரவு")
            TELUGU   -> listOf("శుభోదయం", "శుభ మధ్యాహ్నం", "శుభ సాయంత్రం", "శుభ రాత్రి")
            BENGALI  -> listOf("সুপ্রভাত", "শুভ অপরাহ্ন", "শুভ সন্ধ্যা", "শুভ রাত্রি")
            MARATHI  -> listOf("सुप्रभात", "शुभ दुपार", "शुभ संध्याकाळ", "शुभ रात्री")
            KANNADA  -> listOf("ಶುಭೋದಯ", "ಶುಭ ಮಧ್ಯಾಹ್ನ", "ಶುಭ ಸಂಜೆ", "ಶುಭ ರಾತ್ರಿ")
            GUJARATI -> listOf("સુપ્રભાત", "શુભ બપોર", "શુભ સાંજ", "શુભ રાત્રિ")
            PUNJABI  -> listOf("ਸ਼ੁਭ ਸਵੇਰ", "ਸ਼ੁਭ ਦੁਪਹਿਰ", "ਸ਼ੁਭ ਸ਼ਾਮ", "ਸ਼ੁਭ ਰਾਤ")
            ODIA     -> listOf("ଶୁଭ ସକାଳ", "ଶୁଭ ଅପରାହ୍ନ", "ଶୁଭ ସନ୍ଧ୍ୟା", "ଶୁଭ ରାତ୍ରି")
        }[slot]
    }

    /**
     * Short, NATIVE-ONLY "reply in this language" directive (no English).
     * Used by the COMPACT (1B) prompt where mixing English instruction text
     * tends to flip the tiny model back to English output.
     */
    val nativeReplyDirective: String get() = when (this) {
        ENGLISH  -> ""
        HINDI    -> "हमेशा हिन्दी में ही जवाब दें।"
        TAMIL    -> "எப்போதும் தமிழில் மட்டுமே பதிலளிக்கவும்."
        TELUGU   -> "ఎల్లప్పుడూ తెలుగులో మాత్రమే సమాధానం ఇవ్వండి."
        BENGALI  -> "সবসময় শুধু বাংলায় উত্তর দিন।"
        MARATHI  -> "नेहमी फक्त मराठीतच उत्तर द्या."
        KANNADA  -> "ಯಾವಾಗಲೂ ಕನ್ನಡದಲ್ಲೇ ಉತ್ತರಿಸಿ."
        GUJARATI -> "હંમેશા ફક્ત ગુજરાતીમાં જ જવાબ આપો."
        PUNJABI  -> "ਹਮੇਸ਼ਾ ਸਿਰਫ਼ ਪੰਜਾਬੀ ਵਿੱਚ ਹੀ ਜਵਾਬ ਦਿਓ।"
        ODIA     -> "ସବୁବେଳେ କେବଳ ଓଡ଼ିଆରେ ଉତ୍ତର ଦିଅନ୍ତୁ।"
    }

    /** Kisan pack chat top-bar subtitle. */
    val kisanChatSubtitle: String get() = when (this) {
        ENGLISH  -> "Answers from the offline farming pack"
        HINDI    -> "ऑफ़लाइन खेती पैक से जवाब"
        TAMIL    -> "ஆஃப்லைன் விவசாயத் தொகுப்பிலிருந்து பதில்கள்"
        TELUGU   -> "ఆఫ్‌లైన్ వ్యవసాయ ప్యాక్ నుండి సమాధానాలు"
        BENGALI  -> "অফলাইন কৃষি প্যাক থেকে উত্তর"
        MARATHI  -> "ऑफलाइन शेती पॅकमधून उत्तरे"
        KANNADA  -> "ಆಫ್‌ಲೈನ್ ಕೃಷಿ ಪ್ಯಾಕ್‌ನಿಂದ ಉತ್ತರಗಳು"
        GUJARATI -> "ઑફલાઇન ખેતી પૅકમાંથી જવાબો"
        PUNJABI  -> "ਆਫ਼ਲਾਈਨ ਖੇਤੀ ਪੈਕ ਤੋਂ ਜਵਾਬ"
        ODIA     -> "ଅଫଲାଇନ କୃଷି ପ୍ୟାକରୁ ଉତ୍ତର"
    }

    /** Kisan pack empty-state subtitle. */
    val kisanEmptySubtitle: String get() = when (this) {
        ENGLISH  -> "Answers come only from the offline farming pack — schemes, MSP, crop calendars, pest control."
        HINDI    -> "जवाब सिर्फ़ ऑफ़लाइन खेती पैक से आते हैं — योजनाएँ, MSP, फसल कैलेंडर, कीट नियंत्रण।"
        TAMIL    -> "பதில்கள் ஆஃப்லைன் விவசாயத் தொகுப்பிலிருந்து மட்டுமே — திட்டங்கள், MSP, பயிர் காலண்டர், பூச்சி கட்டுப்பாடு."
        TELUGU   -> "సమాధానాలు ఆఫ్‌లైన్ వ్యవసాయ ప్యాక్ నుండి మాత్రమే — పథకాలు, MSP, పంట క్యాలెండర్లు, పురుగు నియంత్రణ."
        BENGALI  -> "উত্তর শুধু অফলাইন কৃষি প্যাক থেকে — প্রকল্প, MSP, ফসল ক্যালেন্ডার, কীটপতঙ্গ নিয়ন্ত্রণ।"
        MARATHI  -> "उत्तरे फक्त ऑफलाइन शेती पॅकमधून — योजना, MSP, पीक दिनदर्शिका, कीड नियंत्रण."
        KANNADA  -> "ಉತ್ತರಗಳು ಆಫ್‌ಲೈನ್ ಕೃಷಿ ಪ್ಯಾಕ್‌ನಿಂದ ಮಾತ್ರ — ಯೋಜನೆಗಳು, MSP, ಬೆಳೆ ಕ್ಯಾಲೆಂಡರ್, ಕೀಟ ನಿಯಂತ್ರಣ."
        GUJARATI -> "જવાબો ફક્ત ઑફલાઇન ખેતી પૅકમાંથી — યોજનાઓ, MSP, પાક કૅલેન્ડર, જીવાત નિયંત્રણ."
        PUNJABI  -> "ਜਵਾਬ ਸਿਰਫ਼ ਆਫ਼ਲਾਈਨ ਖੇਤੀ ਪੈਕ ਤੋਂ — ਸਕੀਮਾਂ, MSP, ਫ਼ਸਲ ਕੈਲੰਡਰ, ਕੀਟ ਕੰਟਰੋਲ।"
        ODIA     -> "ଉତ୍ତର କେବଳ ଅଫଲାଇନ କୃଷି ପ୍ୟାକରୁ — ଯୋଜନା, MSP, ଫସଲ କ୍ୟାଲେଣ୍ଡର, କୀଟ ନିୟନ୍ତ୍ରଣ।"
    }

    /** Kisan landing "Open chat" call-to-action. */
    val kisanOpenChat: String get() = when (this) {
        ENGLISH  -> "Open Kisan chat →"
        HINDI    -> "किसान चैट खोलें →"
        TAMIL    -> "கிசான் அரட்டையைத் திற →"
        TELUGU   -> "కిసాన్ చాట్ తెరవండి →"
        BENGALI  -> "কিষাণ চ্যাট খুলুন →"
        MARATHI  -> "किसान चॅट उघडा →"
        KANNADA  -> "ಕಿಸಾನ್ ಚಾಟ್ ತೆರೆಯಿರಿ →"
        GUJARATI -> "કિસાન ચેટ ખોલો →"
        PUNJABI  -> "ਕਿਸਾਨ ਚੈਟ ਖੋਲ੍ਹੋ →"
        ODIA     -> "କିସାନ ଚାଟ ଖୋଲନ୍ତୁ →"
    }

    /** Kisan landing "QUICK ASK" section label. */
    val kisanQuickAsk: String get() = when (this) {
        ENGLISH  -> "QUICK ASK"
        HINDI    -> "तुरंत पूछें"
        TAMIL    -> "விரைவில் கேள்"
        TELUGU   -> "త్వరగా అడగండి"
        BENGALI  -> "দ্রুত জিজ্ঞাসা"
        MARATHI  -> "पटकन विचारा"
        KANNADA  -> "ತಕ್ಷಣ ಕೇಳಿ"
        GUJARATI -> "ઝડપથી પૂછો"
        PUNJABI  -> "ਤੁਰੰਤ ਪੁੱਛੋ"
        ODIA     -> "ଶୀଘ୍ର ପଚାରନ୍ତୁ"
    }

    /** Kisan landing "TOPICS IN THIS PACK" section label. */
    val kisanTopicsHeader: String get() = when (this) {
        ENGLISH  -> "TOPICS IN THIS PACK"
        HINDI    -> "इस पैक के विषय"
        TAMIL    -> "இந்தத் தொகுப்பின் தலைப்புகள்"
        TELUGU   -> "ఈ ప్యాక్‌లోని అంశాలు"
        BENGALI  -> "এই প্যাকের বিষয়"
        MARATHI  -> "या पॅकमधील विषय"
        KANNADA  -> "ಈ ಪ್ಯಾಕ್‌ನ ವಿಷಯಗಳು"
        GUJARATI -> "આ પૅકના વિષયો"
        PUNJABI  -> "ਇਸ ਪੈਕ ਦੇ ਵਿਸ਼ੇ"
        ODIA     -> "ଏହି ପ୍ୟାକର ବିଷୟ"
    }

    /** Kisan "100% offline" badge. */
    val kisanOfflineBadge: String get() = when (this) {
        ENGLISH  -> "100% offline"
        HINDI    -> "100% ऑफ़लाइन"
        TAMIL    -> "100% ஆஃப்லைன்"
        TELUGU   -> "100% ఆఫ్‌లైన్"
        BENGALI  -> "100% অফলাইন"
        MARATHI  -> "100% ऑफलाइन"
        KANNADA  -> "100% ಆಫ್‌ಲೈನ್"
        GUJARATI -> "100% ઑફલાઇન"
        PUNJABI  -> "100% ਆਫ਼ਲਾਈਨ"
        ODIA     -> "100% ଅଫଲାଇନ"
    }

    /** Kisan pack empty-state heading. */
    val kisanAskTitle: String get() = when (this) {
        ENGLISH  -> "Ask Kisan Saathi"
        HINDI    -> "किसान साथी से पूछें"
        TAMIL    -> "கிசான் சாத்தியிடம் கேளுங்கள்"
        TELUGU   -> "కిసాన్ సాథీని అడగండి"
        BENGALI  -> "কিষাণ সাথীকে জিজ্ঞাসা করুন"
        MARATHI  -> "किसान साथीला विचारा"
        KANNADA  -> "ಕಿಸಾನ್ ಸಾಥಿಯನ್ನು ಕೇಳಿ"
        GUJARATI -> "કિસાન સાથીને પૂછો"
        PUNJABI  -> "ਕਿਸਾਨ ਸਾਥੀ ਨੂੰ ਪੁੱਛੋ"
        ODIA     -> "କିସାନ ସାଥୀଙ୍କୁ ପଚାରନ୍ତୁ"
    }

    /** Kisan pack starter questions shown on the empty chat screen. */
    val kisanStarters: List<String> get() = when (this) {
        ENGLISH  -> listOf(
            "What is PM-KISAN and how do I apply?",
            "What is the MSP for wheat?",
            "How do I get a Kisan Credit Card?",
            "How do I control fall armyworm in maize?",
        )
        HINDI    -> listOf(
            "PM-KISAN क्या है और आवेदन कैसे करें?",
            "गेहूं का MSP कितना है?",
            "किसान क्रेडिट कार्ड कैसे बनवाएं?",
            "मक्का में फॉल आर्मीवर्म कैसे रोकें?",
        )
        TAMIL    -> listOf(
            "PM-KISAN என்றால் என்ன, எப்படி விண்ணப்பிப்பது?",
            "கோதுமையின் MSP எவ்வளவு?",
            "கிசான் கிரெடிட் கார்டு எப்படி பெறுவது?",
            "சோளத்தில் ஃபால் ஆர்மிவார்ம் பூச்சியை எப்படி கட்டுப்படுத்துவது?",
        )
        TELUGU   -> listOf(
            "PM-KISAN అంటే ఏమిటి, ఎలా దరఖాస్తు చేయాలి?",
            "గోధుమ MSP ఎంత?",
            "కిసాన్ క్రెడిట్ కార్డ్ ఎలా పొందాలి?",
            "మొక్కజొన్నలో ఫాల్ ఆర్మీవార్మ్‌ను ఎలా నియంత్రించాలి?",
        )
        BENGALI  -> listOf(
            "PM-KISAN কী এবং কীভাবে আবেদন করব?",
            "গমের MSP কত?",
            "কিষাণ ক্রেডিট কার্ড কীভাবে পাব?",
            "ভুট্টায় ফল আর্মিওয়ার্ম কীভাবে নিয়ন্ত্রণ করব?",
        )
        MARATHI  -> listOf(
            "PM-KISAN म्हणजे काय आणि अर्ज कसा करावा?",
            "गव्हाचा MSP किती आहे?",
            "किसान क्रेडिट कार्ड कसे मिळवावे?",
            "मक्यातील फॉल आर्मीवर्म कसे नियंत्रित करावे?",
        )
        KANNADA  -> listOf(
            "PM-KISAN ಎಂದರೇನು ಮತ್ತು ಹೇಗೆ ಅರ್ಜಿ ಸಲ್ಲಿಸುವುದು?",
            "ಗೋಧಿಯ MSP ಎಷ್ಟು?",
            "ಕಿಸಾನ್ ಕ್ರೆಡಿಟ್ ಕಾರ್ಡ್ ಹೇಗೆ ಪಡೆಯುವುದು?",
            "ಜೋಳದಲ್ಲಿ ಫಾಲ್ ಆರ್ಮಿವರ್ಮ್ ಅನ್ನು ಹೇಗೆ ನಿಯಂತ್ರಿಸುವುದು?",
        )
        GUJARATI -> listOf(
            "PM-KISAN શું છે અને કેવી રીતે અરજી કરવી?",
            "ઘઉંનો MSP કેટલો છે?",
            "કિસાન ક્રેડિટ કાર્ડ કેવી રીતે મેળવવું?",
            "મકાઈમાં ફોલ આર્મીવોર્મ કેવી રીતે કાબૂમાં રાખવું?",
        )
        PUNJABI  -> listOf(
            "PM-KISAN ਕੀ ਹੈ ਅਤੇ ਕਿਵੇਂ ਅਰਜ਼ੀ ਦੇਣੀ ਹੈ?",
            "ਕਣਕ ਦਾ MSP ਕਿੰਨਾ ਹੈ?",
            "ਕਿਸਾਨ ਕ੍ਰੈਡਿਟ ਕਾਰਡ ਕਿਵੇਂ ਲੈਣਾ ਹੈ?",
            "ਮੱਕੀ ਵਿੱਚ ਫਾਲ ਆਰਮੀਵਰਮ ਨੂੰ ਕਿਵੇਂ ਕੰਟਰੋਲ ਕਰਨਾ ਹੈ?",
        )
        ODIA     -> listOf(
            "PM-KISAN କ'ଣ ଏବଂ କିପରି ଆବେଦନ କରିବି?",
            "ଗହମର MSP କେତେ?",
            "କିସାନ କ୍ରେଡିଟ କାର୍ଡ କିପରି ପାଇବି?",
            "ମକାରେ ଫଲ ଆର୍ମିୱର୍ମ କିପରି ନିୟନ୍ତ୍ରଣ କରିବି?",
        )
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
        HINDI    -> "आपका सारथी · ऑफ़लाइन"
        TAMIL    -> "உங்கள் சாரதி · Offline"
        TELUGU   -> "మీ సారథి · Offline"
        BENGALI  -> "আপনার সারথি · Offline"
        MARATHI  -> "तुमचा सारथी · Offline"
        KANNADA  -> "ನಿಮ್ಮ ಸಾರಥಿ · Offline"
        GUJARATI -> "તમારો સારથી · Offline"
        PUNJABI  -> "ਤੁਹਾਡਾ ਸਾਰਥੀ · Offline"
        ODIA     -> "ଆପଣଙ୍କ ସାରଥୀ · Offline"
    }

    // ── Voice mode overlay ──────────────────────────────────────────────────
    /** Empty-state prompt before the user speaks. */
    val voicePrompt: String get() = when (this) {
        ENGLISH  -> "Speak now — your words will appear here"
        HINDI    -> "अब बोलिए — आपके शब्द यहाँ दिखेंगे"
        TAMIL    -> "இப்போது பேசுங்கள் — உங்கள் வார்த்தைகள் இங்கே தோன்றும்"
        TELUGU   -> "ఇప్పుడు మాట్లాడండి — మీ మాటలు ఇక్కడ కనిపిస్తాయి"
        BENGALI  -> "এখন বলুন — আপনার কথা এখানে দেখা যাবে"
        MARATHI  -> "आता बोला — तुमचे शब्द इथे दिसतील"
        KANNADA  -> "ಈಗ ಮಾತನಾಡಿ — ನಿಮ್ಮ ಮಾತುಗಳು ಇಲ್ಲಿ ಕಾಣಿಸುತ್ತವೆ"
        GUJARATI -> "હવે બોલો — તમારા શબ્દો અહીં દેખાશે"
        PUNJABI  -> "ਹੁਣ ਬੋਲੋ — ਤੁਹਾਡੇ ਸ਼ਬਦ ਇੱਥੇ ਦਿਖਣਗੇ"
        ODIA     -> "ବର୍ତ୍ତମାନ କୁହନ୍ତୁ — ଆପଣଙ୍କ ଶବ୍ଦ ଏଠାରେ ଦେଖାଯିବ"
    }

    /** Status line while actively listening. */
    val voiceStatusListening: String get() = when (this) {
        ENGLISH  -> "Listening…"
        HINDI    -> "सुन रहा हूँ…"
        TAMIL    -> "கேட்கிறேன்…"
        TELUGU   -> "వింటున్నాను…"
        BENGALI  -> "শুনছি…"
        MARATHI  -> "ऐकत आहे…"
        KANNADA  -> "ಕೇಳುತ್ತಿದ್ದೇನೆ…"
        GUJARATI -> "સાંભળું છું…"
        PUNJABI  -> "ਸੁਣ ਰਿਹਾ ਹਾਂ…"
        ODIA     -> "ଶୁଣୁଛି…"
    }

    /** Status line once words are captured. */
    val voiceStatusHeard: String get() = when (this) {
        ENGLISH  -> "I hear you saying…"
        HINDI    -> "आप कह रहे हैं…"
        TAMIL    -> "நீங்கள் சொல்வது…"
        TELUGU   -> "మీరు చెబుతున్నది…"
        BENGALI  -> "আপনি বলছেন…"
        MARATHI  -> "तुम्ही म्हणत आहात…"
        KANNADA  -> "ನೀವು ಹೇಳುತ್ತಿರುವುದು…"
        GUJARATI -> "તમે કહો છો…"
        PUNJABI  -> "ਤੁਸੀਂ ਕਹਿ ਰਹੇ ਹੋ…"
        ODIA     -> "ଆପଣ କହୁଛନ୍ତି…"
    }

    /** Small badge: actively listening. */
    val voiceBadgeListening: String get() = when (this) {
        ENGLISH  -> "LISTENING"
        HINDI    -> "सुन रहे हैं"
        TAMIL    -> "கேட்கிறது"
        TELUGU   -> "వింటోంది"
        BENGALI  -> "শুনছে"
        MARATHI  -> "ऐकत आहे"
        KANNADA  -> "ಕೇಳುತ್ತಿದೆ"
        GUJARATI -> "સાંભળે છે"
        PUNJABI  -> "ਸੁਣ ਰਿਹਾ"
        ODIA     -> "ଶୁଣୁଛି"
    }

    /** Small badge: capture finished. */
    val voiceBadgeCaptured: String get() = when (this) {
        ENGLISH  -> "CAPTURED"
        HINDI    -> "मिल गया"
        TAMIL    -> "பதிவானது"
        TELUGU   -> "నమోదైంది"
        BENGALI  -> "ধরা হয়েছে"
        MARATHI  -> "मिळाले"
        KANNADA  -> "ಸೆರೆಯಾಗಿದೆ"
        GUJARATI -> "મળ્યું"
        PUNJABI  -> "ਮਿਲ ਗਿਆ"
        ODIA     -> "ଧରାଗଲା"
    }

    /** Hint listing the languages the user may speak in. */
    val voiceLangHint: String get() = when (this) {
        ENGLISH  -> "English · or any Indian language"
        HINDI    -> "हिन्दी · English · या कोई भी मिश्रण"
        TAMIL    -> "தமிழ் · English · அல்லது கலவை"
        TELUGU   -> "తెలుగు · English · లేదా మిశ్రమం"
        BENGALI  -> "বাংলা · English · বা মিশ্রণ"
        MARATHI  -> "मराठी · English · किंवा मिश्रण"
        KANNADA  -> "ಕನ್ನಡ · English · ಅಥವಾ ಮಿಶ್ರಣ"
        GUJARATI -> "ગુજરાતી · English · અથવા મિશ્રણ"
        PUNJABI  -> "ਪੰਜਾਬੀ · English · ਜਾਂ ਮਿਸ਼ਰਣ"
        ODIA     -> "ଓଡ଼ିଆ · English · କିମ୍ବା ମିଶ୍ରଣ"
    }

    // ── Misc UI labels (Hindi done; other languages fall back to English until
    //     their language pass — see language-by-language rollout). ──────────────
    /** Message long-press menu: copy. */
    val copyLabel: String get() = when (this) { HINDI -> "कॉपी करें"; MARATHI -> "कॉपी करा"; else -> "Copy" }
    /** Message long-press menu / delete actions. */
    val deleteLabel: String get() = when (this) { HINDI -> "हटाएँ"; MARATHI -> "हटवा"; else -> "Delete" }
    /** Search box placeholder. */
    val searchHint: String get() = when (this) { HINDI -> "खोजें…"; MARATHI -> "शोधा…"; else -> "Search…" }
    /** Battery-optimization dialog: title (asks to skip battery optimization so long replies aren't cut off). */
    val notifPermTitle: String get() = when (this) {
        ENGLISH  -> "Keep replies on time?"
        HINDI    -> "जवाब समय पर पाएँ?"
        TAMIL    -> "பதில் சரியான நேரத்தில் வேண்டுமா?"
        TELUGU   -> "సమాధానాలు సమయానికి కావాలా?"
        BENGALI  -> "উত্তর সময়মতো পেতে চান?"
        MARATHI  -> "उत्तरे वेळेवर मिळवायची?"
        KANNADA  -> "ಉತ್ತರಗಳು ಸಮಯಕ್ಕೆ ಬೇಕೇ?"
        GUJARATI -> "જવાબો સમયસર જોઈએ છે?"
        PUNJABI  -> "ਜਵਾਬ ਸਮੇਂ ਸਿਰ ਚਾਹੀਦੇ ਹਨ?"
        ODIA     -> "ଉତ୍ତର ସମୟରେ ପାଇବେ କି?"
    }
    /** Battery-optimization dialog: body explaining why the exemption is needed. */
    val batteryOptExplanation: String get() = when (this) {
        ENGLISH  -> "Android may pause Saarthi to save battery. Letting the app skip battery optimization keeps long answers from being cut off mid-reply."
        HINDI    -> "बैटरी बचाने के लिए Android सारथी को रोक सकता है। बैटरी ऑप्टिमाइज़ेशन छोड़ने की अनुमति देने से लंबे जवाब बीच में कटने से बचते हैं।"
        TAMIL    -> "பேட்டரியைச் சேமிக்க Android சாரதியை இடைநிறுத்தலாம். பேட்டரி மேம்படுத்தலைத் தவிர்க்க அனுமதித்தால், நீண்ட பதில்கள் பாதியில் நிற்காது."
        TELUGU   -> "బ్యాటరీ ఆదా చేయడానికి Android సారథిని ఆపవచ్చు. బ్యాటరీ ఆప్టిమైజేషన్‌ను దాటవేయడానికి అనుమతిస్తే, పొడవైన సమాధానాలు మధ్యలో ఆగవు."
        BENGALI  -> "ব্যাটারি বাঁচাতে Android সারথিকে থামিয়ে দিতে পারে। ব্যাটারি অপ্টিমাইজেশন এড়াতে দিলে দীর্ঘ উত্তর মাঝপথে থেমে যাবে না।"
        MARATHI  -> "बॅटरी वाचवण्यासाठी Android सारथी थांबवू शकते. बॅटरी ऑप्टिमायझेशन वगळण्याची परवानगी दिल्यास लांब उत्तरे मध्येच थांबणार नाहीत."
        KANNADA  -> "ಬ್ಯಾಟರಿ ಉಳಿಸಲು Android ಸಾರಥಿಯನ್ನು ನಿಲ್ಲಿಸಬಹುದು. ಬ್ಯಾಟರಿ ಆಪ್ಟಿಮೈಸೇಶನ್ ಬಿಟ್ಟುಬಿಡಲು ಅನುಮತಿಸಿದರೆ ದೀರ್ಘ ಉತ್ತರಗಳು ಮಧ್ಯದಲ್ಲಿ ನಿಲ್ಲುವುದಿಲ್ಲ."
        GUJARATI -> "બેટરી બચાવવા Android સારથીને અટકાવી શકે છે. બેટરી ઑપ્ટિમાઇઝેશન છોડવાની મંજૂરી આપવાથી લાંબા જવાબો વચ્ચે અટકશે નહીં."
        PUNJABI  -> "ਬੈਟਰੀ ਬਚਾਉਣ ਲਈ Android ਸਾਰਥੀ ਨੂੰ ਰੋਕ ਸਕਦਾ ਹੈ। ਬੈਟਰੀ ਓਪਟੀਮਾਈਜ਼ੇਸ਼ਨ ਛੱਡਣ ਦੀ ਇਜਾਜ਼ਤ ਦੇਣ ਨਾਲ ਲੰਬੇ ਜਵਾਬ ਵਿਚਾਲੇ ਨਹੀਂ ਰੁਕਣਗੇ।"
        ODIA     -> "ବ୍ୟାଟେରୀ ବଞ୍ଚାଇବାକୁ Android ସାରଥୀକୁ ବନ୍ଦ କରିପାରେ। ବ୍ୟାଟେରୀ ଅପ୍ଟିମାଇଜେସନ୍ ଏଡ଼ାଇବାକୁ ଅନୁମତି ଦେଲେ ଲମ୍ବା ଉତ୍ତର ମଝିରେ ଅଟକିବ ନାହିଁ।"
    }
    /** Generic allow / not-now actions. */
    val allowLabel: String get() = when (this) {
        ENGLISH  -> "Allow"
        HINDI    -> "अनुमति दें"
        TAMIL    -> "அனுமதி"
        TELUGU   -> "అనుమతించు"
        BENGALI  -> "অনুমতি দিন"
        MARATHI  -> "परवानगी द्या"
        KANNADA  -> "ಅನುಮತಿಸಿ"
        GUJARATI -> "મંજૂરી આપો"
        PUNJABI  -> "ਇਜਾਜ਼ਤ ਦਿਓ"
        ODIA     -> "ଅନୁମତି ଦିଅନ୍ତୁ"
    }
    val notNowLabel: String get() = when (this) {
        ENGLISH  -> "Not now"
        HINDI    -> "अभी नहीं"
        TAMIL    -> "இப்போது வேண்டாம்"
        TELUGU   -> "ఇప్పుడు వద్దు"
        BENGALI  -> "এখন না"
        MARATHI  -> "आत्ता नको"
        KANNADA  -> "ಈಗ ಬೇಡ"
        GUJARATI -> "હમણાં નહીં"
        PUNJABI  -> "ਹੁਣ ਨਹੀਂ"
        ODIA     -> "ବର୍ତ୍ତମାନ ନୁହେଁ"
    }
    /** Saarthi Knowledge feature title (brand "Saarthi" kept). */
    val knowledgeTitle: String get() = when (this) { HINDI -> "सारथी ज्ञान"; MARATHI -> "सारथी ज्ञान"; else -> "Saarthi Knowledge" }
    val knowledgeEmpty: String get() = when (this) { HINDI -> "अभी कोई निजी जानकारी सहेजी नहीं गई।"; MARATHI -> "अजून कोणतीही वैयक्तिक माहिती साठवलेली नाही."; else -> "No personal knowledge stored yet." }
    /** Re-select model action (onboarding gate). */
    val reselectModel: String get() = when (this) { HINDI -> "मॉडल फिर चुनें"; MARATHI -> "मॉडेल पुन्हा निवडा"; else -> "Re-select Model" }
    /** Support screen: report an issue. */
    val reportIssue: String get() = when (this) { HINDI -> "समस्या बताएँ"; MARATHI -> "समस्या कळवा"; else -> "Report an issue" }
    /** Paywall labels (product name "Saarthi Pro" kept). */
    val proActive: String get() = when (this) { HINDI -> "Saarthi Pro सक्रिय है"; MARATHI -> "Saarthi Pro सक्रिय आहे"; else -> "Saarthi Pro is active" }
    val unlockBeta: String get() = when (this) { HINDI -> "अनलॉक करें (बीटा)"; MARATHI -> "अनलॉक करा (बीटा)"; else -> "Unlock (beta)" }
    val restorePurchase: String get() = when (this) { HINDI -> "खरीद बहाल करें"; MARATHI -> "खरेदी पुनर्संचयित करा"; else -> "Restore purchase" }
    /** Share-sheet message when the user shares the app (Play link is appended in code). */
    val shareAppMessage: String get() = when (this) {
        HINDI   -> "सारथी — भारत के लिए बना 100% ऑफ़लाइन AI असिस्टेंट। मुफ़्त इस्तेमाल करें:"
        MARATHI -> "सारथी — भारतासाठी बनवलेला 100% ऑफलाइन AI सहाय्यक. मोफत वापरा:"
        else    -> "Saarthi — a free, 100% offline AI assistant for India. Try it:"
    }

    // ── Attachment bottom sheet ─────────────────────────────────────────────
    val attachTitle: String get() = when (this) { HINDI -> "जोड़ें"; MARATHI -> "जोडा"; else -> "Attach" }
    val attachPrivacyNote: String get() = when (this) { HINDI -> "फ़ाइलें आपके फ़ोन में ही रहती हैं — कभी अपलोड नहीं होतीं"; MARATHI -> "फायली तुमच्या फोनमध्येच राहतात — कधीही अपलोड होत नाहीत"; else -> "Files stay on your device — never uploaded" }
    val attachCamera: String get() = when (this) { HINDI -> "कैमरा"; MARATHI -> "कॅमेरा"; else -> "Camera" }
    val attachCameraSub: String get() = when (this) { HINDI -> "फ़ोटो लें"; MARATHI -> "फोटो काढा"; else -> "Take a photo" }
    val attachPhoto: String get() = when (this) { HINDI -> "फ़ोटो"; MARATHI -> "फोटो"; else -> "Photo" }
    val attachPhotoSub: String get() = when (this) { HINDI -> "गैलरी से"; MARATHI -> "गॅलरीमधून"; else -> "From gallery" }
    val attachDocument: String get() = when (this) { HINDI -> "दस्तावेज़"; MARATHI -> "दस्तऐवज"; else -> "Document" }
    val attachVoice: String get() = when (this) { HINDI -> "आवाज़ मेमो"; MARATHI -> "व्हॉइस मेमो"; else -> "Voice memo" }
    val attachVoiceSub: String get() = when (this) { HINDI -> "ऑडियो रिकॉर्ड करें"; MARATHI -> "ऑडिओ रेकॉर्ड करा"; else -> "Record audio" }

    // ── Snackbar messages (chat errors, attachment/voice feedback) ───────────

    /** Snackbar: attach button tapped while the Compact (1B) model is active. */
    val attachmentsNeedLargerModel: String get() = when (this) {
        ENGLISH  -> "Attachments need a larger model — switch to Gemma 4 from Settings → Models."
        HINDI    -> "अटैचमेंट के लिए बड़ा मॉडल चाहिए — Settings → Models से Gemma 4 चुनें।"
        TAMIL    -> "இணைப்புகளுக்கு பெரிய மாடல் தேவை — Settings → Models இல் Gemma 4-க்கு மாறவும்."
        TELUGU   -> "అటాచ్‌మెంట్‌లకు పెద్ద మోడల్ కావాలి — Settings → Models నుండి Gemma 4కి మారండి."
        BENGALI  -> "অ্যাটাচমেন্টের জন্য বড় মডেল দরকার — Settings → Models থেকে Gemma 4-এ পাল্টান।"
        MARATHI  -> "अटॅचमेंटसाठी मोठे मॉडेल हवे — Settings → Models मधून Gemma 4 निवडा."
        KANNADA  -> "ಲಗತ್ತುಗಳಿಗೆ ದೊಡ್ಡ ಮಾಡೆಲ್ ಬೇಕು — Settings → Models ನಿಂದ Gemma 4 ಗೆ ಬದಲಿಸಿ."
        GUJARATI -> "એટેચમેન્ટ માટે મોટું મોડેલ જોઈએ — Settings → Models માંથી Gemma 4 પસંદ કરો."
        PUNJABI  -> "ਅਟੈਚਮੈਂਟ ਲਈ ਵੱਡਾ ਮਾਡਲ ਚਾਹੀਦਾ ਹੈ — Settings → Models ਤੋਂ Gemma 4 ਚੁਣੋ।"
        ODIA     -> "ସଂଲଗ୍ନ ପାଇଁ ବଡ଼ ମଡେଲ ଦରକାର — Settings → Models ରୁ Gemma 4 ବାଛନ୍ତୁ।"
    }
    /** Snackbar: free-tier per-chat document limit reached ([maxDocs] documents). */
    fun freeDocumentLimitReached(maxDocs: Int): String = when (this) {
        ENGLISH  -> "Free includes $maxDocs document per chat. Unlock Saarthi Pro in Settings for unlimited documents."
        HINDI    -> "फ्री में प्रति चैट $maxDocs दस्तावेज़ शामिल है। असीमित दस्तावेज़ों के लिए Settings में Saarthi Pro अनलॉक करें।"
        TAMIL    -> "இலவசத்தில் ஒரு அரட்டைக்கு $maxDocs ஆவணம் அடங்கும். வரம்பற்ற ஆவணங்களுக்கு Settings இல் Saarthi Pro ஐ அன்லாக் செய்யவும்."
        TELUGU   -> "ఫ్రీలో ఒక్కో చాట్‌కు $maxDocs డాక్యుమెంట్ ఉంటుంది. అపరిమిత డాక్యుమెంట్ల కోసం Settings లో Saarthi Pro అన్‌లాక్ చేయండి."
        BENGALI  -> "ফ্রি-তে প্রতি চ্যাটে $maxDocs নথি অন্তর্ভুক্ত। সীমাহীন নথির জন্য Settings-এ Saarthi Pro আনলক করুন।"
        MARATHI  -> "फ्री मध्ये प्रति चॅट $maxDocs दस्तऐवज समाविष्ट आहे. अमर्यादित दस्तऐवजांसाठी Settings मध्ये Saarthi Pro अनलॉक करा."
        KANNADA  -> "ಉಚಿತದಲ್ಲಿ ಪ್ರತಿ ಚಾಟ್‌ಗೆ $maxDocs ದಾಖಲೆ ಸೇರಿದೆ. ಅಪರಿಮಿತ ದಾಖಲೆಗಳಿಗೆ Settings ನಲ್ಲಿ Saarthi Pro ಅನ್‌ಲಾಕ್ ಮಾಡಿ."
        GUJARATI -> "ફ્રીમાં દરેક ચેટ દીઠ $maxDocs દસ્તાવેજ સામેલ છે. અમર્યાદિત દસ્તાવેજો માટે Settings માં Saarthi Pro અનલૉક કરો."
        PUNJABI  -> "ਫ੍ਰੀ ਵਿੱਚ ਹਰ ਚੈਟ ਲਈ $maxDocs ਦਸਤਾਵੇਜ਼ ਸ਼ਾਮਲ ਹੈ। ਅਸੀਮਤ ਦਸਤਾਵੇਜ਼ਾਂ ਲਈ Settings ਵਿੱਚ Saarthi Pro ਅਨਲੌਕ ਕਰੋ।"
        ODIA     -> "ଫ୍ରୀରେ ପ୍ରତି ଚାଟ୍‌କୁ $maxDocs ଡକ୍ୟୁମେଣ୍ଟ ଅନ୍ତର୍ଭୁକ୍ତ। ଅସୀମିତ ଡକ୍ୟୁମେଣ୍ଟ ପାଇଁ Settings ରେ Saarthi Pro ଅନଲକ୍ କରନ୍ତୁ।"
    }
    /** Snackbar: streaming a response failed (non-cancellation error). */
    val streamFailedRetry: String get() = when (this) {
        ENGLISH  -> "Couldn't finish that response. Please try again."
        HINDI    -> "जवाब पूरा नहीं हो सका। कृपया फिर से कोशिश करें।"
        TAMIL    -> "அந்த பதிலை முடிக்க முடியவில்லை. மீண்டும் முயற்சிக்கவும்."
        TELUGU   -> "ఆ సమాధానం పూర్తి కాలేదు. దయచేసి మళ్ళీ ప్రయత్నించండి."
        BENGALI  -> "উত্তরটি শেষ করা যায়নি। আবার চেষ্টা করুন।"
        MARATHI  -> "ते उत्तर पूर्ण होऊ शकले नाही. कृपया पुन्हा प्रयत्न करा."
        KANNADA  -> "ಆ ಉತ್ತರವನ್ನು ಪೂರ್ಣಗೊಳಿಸಲಾಗಲಿಲ್ಲ. ದಯವಿಟ್ಟು ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ."
        GUJARATI -> "તે જવાબ પૂરો થઈ શક્યો નહીં. કૃપા કરી ફરી પ્રયાસ કરો."
        PUNJABI  -> "ਉਹ ਜਵਾਬ ਪੂਰਾ ਨਹੀਂ ਹੋ ਸਕਿਆ। ਕਿਰਪਾ ਕਰਕੇ ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ।"
        ODIA     -> "ସେହି ଉତ୍ତର ସମାପ୍ତ ହୋଇପାରିଲା ନାହିଁ। ଦୟାକରି ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ।"
    }
    /** Voice error: no speech detected / timed out. */
    val voiceNoMatch: String get() = when (this) {
        ENGLISH  -> "Didn't catch that — tap the mic and try again."
        HINDI    -> "समझ नहीं आया — माइक दबाकर फिर से कोशिश करें।"
        TAMIL    -> "புரியவில்லை — மைக்கை தட்டி மீண்டும் முயற்சிக்கவும்."
        TELUGU   -> "అర్థం కాలేదు — మైక్ నొక్కి మళ్ళీ ప్రయత్నించండి."
        BENGALI  -> "বোঝা যায়নি — মাইক চেপে আবার চেষ্টা করুন।"
        MARATHI  -> "समजले नाही — माइक दाबून पुन्हा प्रयत्न करा."
        KANNADA  -> "ಅರ್ಥವಾಗಲಿಲ್ಲ — ಮೈಕ್ ಒತ್ತಿ ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ."
        GUJARATI -> "સમજાયું નહીં — માઇક દબાવીને ફરી પ્રયાસ કરો."
        PUNJABI  -> "ਸਮਝ ਨਹੀਂ ਆਇਆ — ਮਾਈਕ ਦਬਾ ਕੇ ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ।"
        ODIA     -> "ବୁଝି ହେଲା ନାହିଁ — ମାଇକ୍ ଦବାଇ ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ।"
    }
    /** Voice error: RECORD_AUDIO permission missing. */
    val voiceMicPermissionNeeded: String get() = when (this) {
        ENGLISH  -> "Microphone permission is needed for voice input."
        HINDI    -> "आवाज़ इनपुट के लिए माइक्रोफ़ोन की अनुमति चाहिए।"
        TAMIL    -> "குரல் உள்ளீட்டிற்கு மைக்ரோஃபோன் அனுமதி தேவை."
        TELUGU   -> "వాయిస్ ఇన్‌పుట్ కోసం మైక్రోఫోన్ అనుమతి అవసరం."
        BENGALI  -> "ভয়েস ইনপুটের জন্য মাইক্রোফোন অনুমতি প্রয়োজন।"
        MARATHI  -> "आवाज इनपुटसाठी मायक्रोफोन परवानगी आवश्यक आहे."
        KANNADA  -> "ಧ್ವನಿ ಇನ್‌ಪುಟ್‌ಗೆ ಮೈಕ್ರೋಫೋನ್ ಅನುಮತಿ ಅಗತ್ಯ."
        GUJARATI -> "વૉઇસ ઇનપુટ માટે માઇક્રોફોન પરવાનગી જરૂરી છે."
        PUNJABI  -> "ਵੌਇਸ ਇਨਪੁੱਟ ਲਈ ਮਾਈਕ੍ਰੋਫ਼ੋਨ ਇਜਾਜ਼ਤ ਲੋੜੀਂਦੀ ਹੈ।"
        ODIA     -> "ଭଏସ୍ ଇନପୁଟ୍ ପାଇଁ ମାଇକ୍ରୋଫୋନ୍ ଅନୁମତି ଆବଶ୍ୟକ।"
    }
    /** Voice error: on-device speech service network/server failure. */
    val voiceServiceUnavailable: String get() = when (this) {
        ENGLISH  -> "The device speech service isn't responding right now. Please type instead."
        HINDI    -> "डिवाइस की स्पीच सेवा अभी जवाब नहीं दे रही। कृपया टाइप करें।"
        TAMIL    -> "சாதனத்தின் பேச்சு சேவை இப்போது பதிலளிக்கவில்லை. தட்டச்சு செய்யவும்."
        TELUGU   -> "పరికరం స్పీచ్ సేవ ప్రస్తుతం స్పందించడం లేదు. దయచేసి టైప్ చేయండి."
        BENGALI  -> "ডিভাইসের স্পিচ সার্ভিস এখন সাড়া দিচ্ছে না। অনুগ্রহ করে টাইপ করুন।"
        MARATHI  -> "डिव्हाइसची स्पीच सेवा सध्या प्रतिसाद देत नाही. कृपया टाइप करा."
        KANNADA  -> "ಸಾಧನದ ಸ್ಪೀಚ್ ಸೇವೆ ಈಗ ಪ್ರತಿಕ್ರಿಯಿಸುತ್ತಿಲ್ಲ. ದಯವಿಟ್ಟು ಟೈಪ್ ಮಾಡಿ."
        GUJARATI -> "ડિવાઇસની સ્પીચ સેવા હમણાં જવાબ આપી રહી નથી. કૃપા કરી ટાઇપ કરો."
        PUNJABI  -> "ਡਿਵਾਈਸ ਦੀ ਸਪੀਚ ਸੇਵਾ ਹੁਣੇ ਜਵਾਬ ਨਹੀਂ ਦੇ ਰਹੀ। ਕਿਰਪਾ ਕਰਕੇ ਟਾਈਪ ਕਰੋ।"
        ODIA     -> "ଡିଭାଇସର ସ୍ପିଚ୍ ସେବା ବର୍ତ୍ତମାନ ଉତ୍ତର ଦେଉନାହିଁ। ଦୟାକରି ଟାଇପ୍ କରନ୍ତୁ।"
    }
    /** Voice error: recognizer busy (rapid re-trigger). */
    val voiceBusy: String get() = when (this) {
        ENGLISH  -> "Voice input is busy — try again in a moment."
        HINDI    -> "आवाज़ इनपुट व्यस्त है — थोड़ी देर में फिर कोशिश करें।"
        TAMIL    -> "குரல் உள்ளீடு பிஸியாக உள்ளது — சிறிது நேரம் கழித்து முயற்சிக்கவும்."
        TELUGU   -> "వాయిస్ ఇన్‌పుట్ బిజీగా ఉంది — కొద్దిసేపు తర్వాత మళ్ళీ ప్రయత్నించండి."
        BENGALI  -> "ভয়েস ইনপুট ব্যস্ত — কিছুক্ষণ পরে আবার চেষ্টা করুন।"
        MARATHI  -> "आवाज इनपुट व्यस्त आहे — थोड्या वेळाने पुन्हा प्रयत्न करा."
        KANNADA  -> "ಧ್ವನಿ ಇನ್‌ಪುಟ್ ಬ್ಯುಸಿಯಾಗಿದೆ — ಸ್ವಲ್ಪ ಸಮಯದ ನಂತರ ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ."
        GUJARATI -> "વૉઇસ ઇનપુટ વ્યસ્ત છે — થોડી વારમાં ફરી પ્રયાસ કરો."
        PUNJABI  -> "ਵੌਇਸ ਇਨਪੁੱਟ ਰੁੱਝਿਆ ਹੋਇਆ ਹੈ — ਥੋੜ੍ਹੀ ਦੇਰ ਬਾਅਦ ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ।"
        ODIA     -> "ଭଏସ୍ ଇନପୁଟ୍ ବ୍ୟସ୍ତ ଅଛି — କିଛି ସମୟ ପରେ ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ।"
    }
    /** Voice error: fallback for any other recognizer error code. */
    val voiceGenericError: String get() = when (this) {
        ENGLISH  -> "Couldn't process voice input. Please try again or type instead."
        HINDI    -> "आवाज़ इनपुट प्रोसेस नहीं हो सका। फिर कोशिश करें या टाइप करें।"
        TAMIL    -> "குரல் உள்ளீட்டை செயலாக்க முடியவில்லை. மீண்டும் முயற்சிக்கவும் அல்லது தட்டச்சு செய்யவும்."
        TELUGU   -> "వాయిస్ ఇన్‌పుట్ ప్రాసెస్ చేయలేకపోయాము. మళ్ళీ ప్రయత్నించండి లేదా టైప్ చేయండి."
        BENGALI  -> "ভয়েস ইনপুট প্রসেস করা যায়নি। আবার চেষ্টা করুন বা টাইপ করুন।"
        MARATHI  -> "आवाज इनपुट प्रक्रिया करता आली नाही. पुन्हा प्रयत्न करा किंवा टाइप करा."
        KANNADA  -> "ಧ್ವನಿ ಇನ್‌ಪುಟ್ ಪ್ರಕ್ರಿಯೆಗೊಳಿಸಲಾಗಲಿಲ್ಲ. ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ ಅಥವಾ ಟೈಪ್ ಮಾಡಿ."
        GUJARATI -> "વૉઇસ ઇનપુટ પ્રોસેસ કરી શકાયું નહીં. ફરી પ્રયાસ કરો અથવા ટાઇપ કરો."
        PUNJABI  -> "ਵੌਇਸ ਇਨਪੁੱਟ ਪ੍ਰੋਸੈਸ ਨਹੀਂ ਹੋ ਸਕਿਆ। ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ ਜਾਂ ਟਾਈਪ ਕਰੋ।"
        ODIA     -> "ଭଏସ୍ ଇନପୁଟ୍ ପ୍ରକ୍ରିୟାକରଣ ହୋଇପାରିଲା ନାହିଁ। ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ କିମ୍ବା ଟାଇପ୍ କରନ୍ତୁ।"
    }
    /** Voice error: SpeechRecognizer unavailable on this device at all. */
    val voiceNotAvailable: String get() = when (this) {
        ENGLISH  -> "Voice recognition not available on this device"
        HINDI    -> "इस डिवाइस पर आवाज़ पहचान उपलब्ध नहीं है"
        TAMIL    -> "இந்த சாதனத்தில் குரல் அடையாளம் இல்லை"
        TELUGU   -> "ఈ పరికరంలో వాయిస్ రికగ్నిషన్ అందుబాటులో లేదు"
        BENGALI  -> "এই ডিভাইসে ভয়েস শনাক্তকরণ উপলব্ধ নেই"
        MARATHI  -> "या डिव्हाइसवर आवाज ओळख उपलब्ध नाही"
        KANNADA  -> "ಈ ಸಾಧನದಲ್ಲಿ ಧ್ವನಿ ಗುರುತಿಸುವಿಕೆ ಲಭ್ಯವಿಲ್ಲ"
        GUJARATI -> "આ ડિવાઇસ પર વૉઇસ ઓળખ ઉપલબ્ધ નથી"
        PUNJABI  -> "ਇਸ ਡਿਵਾਈਸ 'ਤੇ ਵੌਇਸ ਪਛਾਣ ਉਪਲਬਧ ਨਹੀਂ ਹੈ"
        ODIA     -> "ଏହି ଡିଭାଇସ୍‌ରେ ଭଏସ୍ ଚିହ୍ନଟ ଉପଲବ୍ଧ ନାହିଁ"
    }
    /** Voice error: SpeechRecognizer.startListening() itself threw. */
    val voiceStartFailed: String get() = when (this) {
        ENGLISH  -> "Couldn't start voice input. Please try again."
        HINDI    -> "आवाज़ इनपुट शुरू नहीं हो सका। कृपया फिर से कोशिश करें।"
        TAMIL    -> "குரல் உள்ளீட்டைத் தொடங்க முடியவில்லை. மீண்டும் முயற்சிக்கவும்."
        TELUGU   -> "వాయిస్ ఇన్‌పుట్ ప్రారంభం కాలేదు. దయచేసి మళ్ళీ ప్రయత్నించండి."
        BENGALI  -> "ভয়েস ইনপুট শুরু করা যায়নি। আবার চেষ্টা করুন।"
        MARATHI  -> "आवाज इनपुट सुरू होऊ शकले नाही. कृपया पुन्हा प्रयत्न करा."
        KANNADA  -> "ಧ್ವನಿ ಇನ್‌ಪುಟ್ ಪ್ರಾರಂಭಿಸಲಾಗಲಿಲ್ಲ. ದಯವಿಟ್ಟು ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ."
        GUJARATI -> "વૉઇસ ઇનપુટ શરૂ થઈ શક્યું નહીં. કૃપા કરી ફરી પ્રયાસ કરો."
        PUNJABI  -> "ਵੌਇਸ ਇਨਪੁੱਟ ਸ਼ੁਰੂ ਨਹੀਂ ਹੋ ਸਕਿਆ। ਕਿਰਪਾ ਕਰਕੇ ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ।"
        ODIA     -> "ଭଏସ୍ ଇନପୁଟ୍ ଆରମ୍ଭ ହୋଇପାରିଲା ନାହିଁ। ଦୟାକରି ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ।"
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

    /** Short native-script "Ask" word — used as the accent label in the chat empty state. */
    val askPromptNative: String get() = when (this) {
        ENGLISH  -> "Ask me"
        HINDI    -> "पूछिए"
        TAMIL    -> "கேளுங்கள்"
        TELUGU   -> "అడగండి"
        BENGALI  -> "জিজ্ঞাসা করুন"
        MARATHI  -> "विचारा"
        KANNADA  -> "ಕೇಳಿ"
        GUJARATI -> "પૂછો"
        PUNJABI  -> "ਪੁੱਛੋ"
        ODIA     -> "ପଚାରନ୍ତୁ"
    }

    /** Empty-chat headline (e.g. "What's on your mind?"). */
    val emptyChatHeadline: String get() = when (this) {
        ENGLISH  -> "What's on your mind?"
        HINDI    -> "क्या जानना चाहते हैं?"
        TAMIL    -> "என்ன கேட்கணும்?"
        TELUGU   -> "ఏం తెలుసుకోవాలి?"
        BENGALI  -> "কী জানতে চান?"
        MARATHI  -> "काय जाणायचंय?"
        KANNADA  -> "ಏನು ತಿಳಿಯಬೇಕು?"
        GUJARATI -> "શું જાણવું છે?"
        PUNJABI  -> "ਕੀ ਜਾਣਨਾ ਹੈ?"
        ODIA     -> "କ'ଣ ଜାଣିବାକୁ ଚାହାଁନ୍ତି?"
    }

    /** Empty-chat helper subtitle. */
    val emptyChatSubtitle: String get() = when (this) {
        ENGLISH  -> "I work entirely on your phone.\nAsk me anything — your privacy stays intact."
        HINDI    -> "मैं पूरी तरह से आपके फ़ोन पर काम करता हूँ।\nकुछ भी पूछें — आपकी निजता सुरक्षित रहेगी।"
        else     -> "I work entirely on your phone.\nAsk me anything — your privacy stays intact."
    }

    /** Section label above the suggestion chips. */
    val tryTheseLabel: String get() = when (this) {
        ENGLISH  -> "TRY THESE"
        HINDI    -> "ये पूछ कर देखिए"
        TAMIL    -> "இவற்றை முயற்சிக்கவும்"
        TELUGU   -> "ఇవి ప్రయత్నించండి"
        BENGALI  -> "এগুলো জিজ্ঞাসা করুন"
        MARATHI  -> "हे विचारून पहा"
        KANNADA  -> "ಇವುಗಳನ್ನು ಪ್ರಯತ್ನಿಸಿ"
        GUJARATI -> "આ પૂછી જુઓ"
        PUNJABI  -> "ਇਹ ਪੁੱਛੋ"
        ODIA     -> "ଏଗୁଡ଼ିକ ପଚାରନ୍ତୁ"
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

    /** Chat menu "Persona" label (followed by the active persona name). */
    val personaLabel: String get() = when (this) {
        ENGLISH  -> "Persona"
        HINDI    -> "व्यक्तित्व"
        TAMIL    -> "ஆளுமை"
        TELUGU   -> "వ్యక్తిత్వం"
        BENGALI  -> "ব্যক্তিত্ব"
        MARATHI  -> "व्यक्तिमत्त्व"
        KANNADA  -> "ವ್ಯಕ್ತಿತ್ವ"
        GUJARATI -> "વ્યક્તિત્વ"
        PUNJABI  -> "ਸ਼ਖਸੀਅਤ"
        ODIA     -> "ବ୍ୟକ୍ତିତ୍ୱ"
    }

    /** Chat menu "Share chat" label. */
    val shareChat: String get() = when (this) {
        ENGLISH  -> "Share chat"
        HINDI    -> "चैट साझा करें"
        TAMIL    -> "அரட்டையைப் பகிர்"
        TELUGU   -> "చాట్‌ను షేర్ చేయి"
        BENGALI  -> "চ্যাট শেয়ার করুন"
        MARATHI  -> "चॅट शेअर करा"
        KANNADA  -> "ಚಾಟ್ ಹಂಚಿಕೊಳ್ಳಿ"
        GUJARATI -> "ચેટ શેર કરો"
        PUNJABI  -> "ਚੈਟ ਸਾਂਝੀ ਕਰੋ"
        ODIA     -> "ଚାଟ ସେୟାର କରନ୍ତୁ"
    }

    /** "You" speaker label in a shared chat transcript. */
    val shareYouLabel: String get() = when (this) {
        ENGLISH  -> "You"
        HINDI    -> "आप"
        TAMIL    -> "நீங்கள்"
        TELUGU   -> "మీరు"
        BENGALI  -> "আপনি"
        MARATHI  -> "तुम्ही"
        KANNADA  -> "ನೀವು"
        GUJARATI -> "તમે"
        PUNJABI  -> "ਤੁਸੀਂ"
        ODIA     -> "ଆପଣ"
    }

    /** Chat menu "Clear chat" label. */
    val clearChat: String get() = when (this) {
        ENGLISH  -> "Clear chat"
        HINDI    -> "चैट साफ़ करें"
        TAMIL    -> "அரட்டையை அழி"
        TELUGU   -> "చాట్ క్లియర్ చేయి"
        BENGALI  -> "চ্যাট সাফ করুন"
        MARATHI  -> "चॅट साफ करा"
        KANNADA  -> "ಚಾಟ್ ತೆರವುಗೊಳಿಸಿ"
        GUJARATI -> "ચેટ સાફ કરો"
        PUNJABI  -> "ਚੈਟ ਸਾਫ਼ ਕਰੋ"
        ODIA     -> "ଚାଟ ସଫା କରନ୍ତୁ"
    }

    /** Clear-chat confirmation dialog title. */
    val clearChatTitle: String get() = when (this) {
        ENGLISH  -> "Clear conversation?"
        HINDI    -> "बातचीत साफ़ करें?"
        TAMIL    -> "உரையாடலை அழிக்கவா?"
        TELUGU   -> "సంభాషణను క్లియర్ చేయాలా?"
        BENGALI  -> "কথোপকথন সাফ করবেন?"
        MARATHI  -> "संभाषण साफ करायचे?"
        KANNADA  -> "ಸಂಭಾಷಣೆ ತೆರವುಗೊಳಿಸಬೇಕೆ?"
        GUJARATI -> "વાતચીત સાફ કરવી?"
        PUNJABI  -> "ਗੱਲਬਾਤ ਸਾਫ਼ ਕਰੀਏ?"
        ODIA     -> "ବାର୍ତ୍ତାଳାପ ସଫା କରିବେ?"
    }

    /** Clear-chat confirmation dialog body. */
    val clearChatMessage: String get() = when (this) {
        ENGLISH  -> "All messages in this chat will be deleted."
        HINDI    -> "इस चैट के सभी संदेश हटा दिए जाएँगे।"
        TAMIL    -> "இந்த அரட்டையின் எல்லா செய்திகளும் நீக்கப்படும்."
        TELUGU   -> "ఈ చాట్‌లోని అన్ని సందేశాలు తొలగించబడతాయి."
        BENGALI  -> "এই চ্যাটের সমস্ত বার্তা মুছে ফেলা হবে।"
        MARATHI  -> "या चॅटमधील सर्व संदेश हटवले जातील."
        KANNADA  -> "ಈ ಚಾಟ್‌ನ ಎಲ್ಲಾ ಸಂದೇಶಗಳು ಅಳಿಸಲ್ಪಡುತ್ತವೆ."
        GUJARATI -> "આ ચેટના બધા સંદેશા કાઢી નાખવામાં આવશે."
        PUNJABI  -> "ਇਸ ਚੈਟ ਦੇ ਸਾਰੇ ਸੁਨੇਹੇ ਮਿਟਾ ਦਿੱਤੇ ਜਾਣਗੇ।"
        ODIA     -> "ଏହି ଚାଟର ସମସ୍ତ ବାର୍ତ୍ତା ବିଲୋପ ହେବ।"
    }

    /** Generic "Clear" confirm button. */
    val clearConfirm: String get() = when (this) {
        ENGLISH  -> "Clear"
        HINDI    -> "साफ़ करें"
        TAMIL    -> "அழி"
        TELUGU   -> "క్లియర్"
        BENGALI  -> "সাফ করুন"
        MARATHI  -> "साफ करा"
        KANNADA  -> "ತೆರವುಗೊಳಿಸಿ"
        GUJARATI -> "સાફ કરો"
        PUNJABI  -> "ਸਾਫ਼ ਕਰੋ"
        ODIA     -> "ସଫା କରନ୍ତୁ"
    }

    /** Generic "Cancel" button. */
    val cancelLabel: String get() = when (this) {
        ENGLISH  -> "Cancel"
        HINDI    -> "रद्द करें"
        TAMIL    -> "ரத்து"
        TELUGU   -> "రద్దు"
        BENGALI  -> "বাতিল"
        MARATHI  -> "रद्द करा"
        KANNADA  -> "ರದ್ದು"
        GUJARATI -> "રદ કરો"
        PUNJABI  -> "ਰੱਦ ਕਰੋ"
        ODIA     -> "ବାତିଲ"
    }

    /** Pack tile "LIVE" badge (an available pack). */
    val liveBadge: String get() = when (this) {
        ENGLISH  -> "LIVE"
        HINDI    -> "उपलब्ध"
        TAMIL    -> "தயார்"
        TELUGU   -> "అందుబాటులో"
        BENGALI  -> "চালু"
        MARATHI  -> "उपलब्ध"
        KANNADA  -> "ಲಭ್ಯ"
        GUJARATI -> "ઉપલબ્ધ"
        PUNJABI  -> "ਉਪਲਬਧ"
        ODIA     -> "ଉପଲବ୍ଧ"
    }

    /** Pack tile "SOON" badge (a coming-soon pack). */
    val soonBadge: String get() = when (this) {
        ENGLISH  -> "SOON"
        HINDI    -> "जल्द"
        TAMIL    -> "விரைவில்"
        TELUGU   -> "త్వరలో"
        BENGALI  -> "শীঘ্রই"
        MARATHI  -> "लवकरच"
        KANNADA  -> "ಶೀಘ್ರ"
        GUJARATI -> "જલ્દી"
        PUNJABI  -> "ਜਲਦੀ"
        ODIA     -> "ଶୀଘ୍ର"
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

    // ── Home screen copy (localized) ─────────────────────────────────────────

    /** Helper line under the greeting — the assistant's primary capability. */
    val homeHelperText: String get() = when (this) {
        ENGLISH  -> "Ask in text, voice, image, or file."
        HINDI    -> "टेक्स्ट, आवाज़, फ़ोटो या फ़ाइल से पूछें।"
        TAMIL    -> "உரை, குரல், படம் அல்லது கோப்பில் கேளுங்கள்."
        TELUGU   -> "టెక్స్ట్, వాయిస్, ఫోటో లేదా ఫైల్‌తో అడగండి."
        BENGALI  -> "টেক্সট, ভয়েস, ছবি বা ফাইলে জিজ্ঞাসা করুন।"
        MARATHI  -> "मजकूर, आवाज, फोटो किंवा फाइलने विचारा."
        KANNADA  -> "ಪಠ್ಯ, ಧ್ವನಿ, ಚಿತ್ರ ಅಥವಾ ಫೈಲ್‌ನಲ್ಲಿ ಕೇಳಿ."
        GUJARATI -> "ટેક્સ્ટ, અવાજ, ફોટો કે ફાઇલથી પૂછો."
        PUNJABI  -> "ਟੈਕਸਟ, ਆਵਾਜ਼, ਫ਼ੋਟੋ ਜਾਂ ਫ਼ਾਈਲ ਨਾਲ ਪੁੱਛੋ।"
        ODIA     -> "ଟେକ୍ସଟ, ସ୍ୱର, ଫଟୋ କିମ୍ବା ଫାଇଲରେ ପଚାରନ୍ତୁ।"
    }

    /** Primary "Ask Saarthi" card title — brand name preserved per language. */
    val askCardTitle: String get() = when (this) {
        ENGLISH  -> "Ask Saarthi"
        HINDI    -> "सारथी से पूछें"
        TAMIL    -> "சாரதியிடம் கேள்"
        TELUGU   -> "సారథిని అడుగు"
        BENGALI  -> "সারথিকে জিজ্ঞাসা করুন"
        MARATHI  -> "सारथीला विचारा"
        KANNADA  -> "ಸಾರಥಿಯನ್ನು ಕೇಳಿ"
        GUJARATI -> "સારથીને પૂછો"
        PUNJABI  -> "ਸਾਰਥੀ ਨੂੰ ਪੁੱਛੋ"
        ODIA     -> "ସାରଥୀଙ୍କୁ ପଚାରନ୍ତୁ"
    }

    /** Card subtitle listing the input modes (dot-separated). */
    val homeInputModes: String get() = when (this) {
        ENGLISH  -> "Text · Voice · File · Image"
        HINDI    -> "टेक्स्ट · आवाज़ · फ़ाइल · फ़ोटो"
        TAMIL    -> "உரை · குரல் · கோப்பு · படம்"
        TELUGU   -> "టెక్స్ట్ · వాయిస్ · ఫైల్ · ఫోటో"
        BENGALI  -> "টেক্সট · ভয়েস · ফাইল · ছবি"
        MARATHI  -> "मजकूर · आवाज · फाइल · फोटो"
        KANNADA  -> "ಪಠ್ಯ · ಧ್ವನಿ · ಫೈಲ್ · ಚಿತ್ರ"
        GUJARATI -> "ટેક્સ્ટ · અવાજ · ફાઇલ · ફોટો"
        PUNJABI  -> "ਟੈਕਸਟ · ਆਵਾਜ਼ · ਫ਼ਾਈਲ · ਫ਼ੋਟੋ"
        ODIA     -> "ଟେକ୍ସଟ · ସ୍ୱର · ଫାଇଲ · ଫଟୋ"
    }

    /** Three home quick-action chips (highest-frequency tasks). Also sent as
     *  the prompt when tapped, so they are full natural phrases. */
    val homeQuickActions: List<String> get() = when (this) {
        ENGLISH  -> listOf("Summarize a PDF", "Check PM-Kisan eligibility", "Translate")
        HINDI    -> listOf("PDF का सारांश दें", "PM-Kisan पात्रता जाँचें", "अनुवाद करें")
        TAMIL    -> listOf("PDF சுருக்கம் தா", "PM-Kisan தகுதி சரிபார்", "மொழிபெயர்")
        TELUGU   -> listOf("PDF సారాంశం ఇవ్వు", "PM-Kisan అర్హత తనిఖీ", "అనువదించు")
        BENGALI  -> listOf("PDF সারসংক্ষেপ দাও", "PM-Kisan যোগ্যতা দেখো", "অনুবাদ করো")
        MARATHI  -> listOf("PDF चा सारांश द्या", "PM-Kisan पात्रता तपासा", "भाषांतर करा")
        KANNADA  -> listOf("PDF ಸಾರಾಂಶ ನೀಡಿ", "PM-Kisan ಅರ್ಹತೆ ಪರಿಶೀಲಿಸಿ", "ಅನುವಾದಿಸಿ")
        GUJARATI -> listOf("PDF નો સારાંશ આપો", "PM-Kisan પાત્રતા તપાસો", "અનુવાદ કરો")
        PUNJABI  -> listOf("PDF ਦਾ ਸਾਰ ਦਿਓ", "PM-Kisan ਯੋਗਤਾ ਜਾਂਚੋ", "ਅਨੁਵਾਦ ਕਰੋ")
        ODIA     -> listOf("PDF ସାରାଂଶ ଦିଅନ୍ତୁ", "PM-Kisan ଯୋଗ୍ୟତା ଯାଞ୍ଚ", "ଅନୁବାଦ କରନ୍ତୁ")
    }

    /**
     * Home quick-action chips for the Compact (1B) model — swapped in for
     * [homeQuickActions] when it's the active model. Compact can't handle a
     * user-attached PDF (no attachment support at that tier) or the Kisan
     * knowledge pack (its own catalog description: "not for knowledge packs
     * like Kisan" — too little context budget for grounded RAG answers), so
     * both are replaced with general-chat prompts Compact handles well.
     * "Translate" is unchanged — plain translation needs no RAG/attachments.
     */
    val homeQuickActionsCompact: List<String> get() = when (this) {
        ENGLISH  -> listOf("Write a letter for me", "Explain something simply", "Translate")
        HINDI    -> listOf("मेरे लिए पत्र लिखें", "आसान भाषा में समझाएँ", "अनुवाद करें")
        TAMIL    -> listOf("எனக்கு கடிதம் எழுது", "எளிமையாக விளக்கு", "மொழிபெயர்")
        TELUGU   -> listOf("నాకు లేఖ రాయండి", "సులభంగా వివరించండి", "అనువదించు")
        BENGALI  -> listOf("আমার জন্য চিঠি লেখো", "সহজভাবে বুঝিয়ে দাও", "অনুবাদ করো")
        MARATHI  -> listOf("माझ्यासाठी पत्र लिहा", "सोप्या भाषेत समजावा", "भाषांतर करा")
        KANNADA  -> listOf("ನನಗೆ ಪತ್ರ ಬರೆಯಿರಿ", "ಸರಳವಾಗಿ ವಿವರಿಸಿ", "ಅನುವಾದಿಸಿ")
        GUJARATI -> listOf("મારા માટે પત્ર લખો", "સરળ ભાષામાં સમજાવો", "અનુવાદ કરો")
        PUNJABI  -> listOf("ਮੇਰੇ ਲਈ ਚਿੱਠੀ ਲਿਖੋ", "ਸੌਖੇ ਸ਼ਬਦਾਂ ਵਿੱਚ ਸਮਝਾਓ", "ਅਨੁਵਾਦ ਕਰੋ")
        ODIA     -> listOf("ମୋ ପାଇଁ ଚିଠି ଲେଖ", "ସହଜରେ ବୁଝାନ୍ତୁ", "ଅନୁବାଦ କରନ୍ତୁ")
    }

    /** "SPECIALIST MODES" section label. */
    val specialistModesLabel: String get() = when (this) {
        ENGLISH  -> "SPECIALIST MODES"
        HINDI    -> "विशेषज्ञ मोड"
        TAMIL    -> "சிறப்பு பயன்முறைகள்"
        TELUGU   -> "ప్రత్యేక మోడ్‌లు"
        BENGALI  -> "বিশেষজ্ঞ মোড"
        MARATHI  -> "तज्ज्ञ मोड"
        KANNADA  -> "ತಜ್ಞ ಮೋಡ್‌ಗಳು"
        GUJARATI -> "નિષ્ણાત મોડ"
        PUNJABI  -> "ਮਾਹਰ ਮੋਡ"
        ODIA     -> "ବିଶେଷଜ୍ଞ ମୋଡ୍"
    }

    /** Kisan tile keywords (Agriculture • Market • Schemes). */
    val kisanKeywords: String get() = when (this) {
        ENGLISH  -> "Farming · Mandi · Schemes"
        HINDI    -> "खेती · मंडी · योजनाएँ"
        TAMIL    -> "விவசாயம் · சந்தை · திட்டங்கள்"
        TELUGU   -> "వ్యవసాయం · మార్కెట్ · పథకాలు"
        BENGALI  -> "কৃষি · বাজার · প্রকল্প"
        MARATHI  -> "शेती · बाजार · योजना"
        KANNADA  -> "ಕೃಷಿ · ಮಾರುಕಟ್ಟೆ · ಯೋಜನೆಗಳು"
        GUJARATI -> "ખેતી · બજાર · યોજનાઓ"
        PUNJABI  -> "ਖੇਤੀ · ਮੰਡੀ · ਸਕੀਮਾਂ"
        ODIA     -> "କୃଷି · ବଜାର · ଯୋଜନା"
    }

    /** Vidya tile keywords (Learning • NCERT • GK). */
    val vidyaKeywords: String get() = when (this) {
        ENGLISH  -> "Learning · NCERT · GK"
        HINDI    -> "पढ़ाई · NCERT · सामान्य ज्ञान"
        TAMIL    -> "கல்வி · NCERT · பொது அறிவு"
        TELUGU   -> "చదువు · NCERT · జనరల్ నాలెడ్జ్"
        BENGALI  -> "পড়াশোনা · NCERT · সাধারণ জ্ঞান"
        MARATHI  -> "अभ्यास · NCERT · सामान्य ज्ञान"
        KANNADA  -> "ಕಲಿಕೆ · NCERT · ಸಾಮಾನ್ಯ ಜ್ಞಾನ"
        GUJARATI -> "અભ્યાસ · NCERT · સામાન્ય જ્ઞાન"
        PUNJABI  -> "ਪੜ੍ਹਾਈ · NCERT · ਆਮ ਗਿਆਨ"
        ODIA     -> "ପଢ଼ା · NCERT · ସାଧାରଣ ଜ୍ଞାନ"
    }

    /** Karigar tile keywords (Manuals • Repairs). */
    val karigarKeywords: String get() = when (this) {
        ENGLISH  -> "Manuals · Repairs"
        HINDI    -> "मैनुअल · मरम्मत"
        TAMIL    -> "கையேடுகள் · பழுதுபார்ப்பு"
        TELUGU   -> "మాన్యువల్స్ · మరమ్మతులు"
        BENGALI  -> "ম্যানুয়াল · মেরামত"
        MARATHI  -> "मॅन्युअल · दुरुस्ती"
        KANNADA  -> "ಕೈಪಿಡಿಗಳು · ದುರಸ್ತಿ"
        GUJARATI -> "મેન્યુઅલ · સમારકામ"
        PUNJABI  -> "ਮੈਨੂਅਲ · ਮੁਰੰਮਤ"
        ODIA     -> "ମାନୁଆଲ · ମରାମତି"
    }

    /** Swasth tile keywords (Health • First Aid). */
    val swasthKeywords: String get() = when (this) {
        ENGLISH  -> "Wellness · First-aid"
        HINDI    -> "सेहत · प्राथमिक उपचार"
        TAMIL    -> "நலம் · முதலுதவி"
        TELUGU   -> "ఆరోగ్యం · ప్రథమ చికిత్స"
        BENGALI  -> "সুস্থতা · প্রাথমিক চিকিৎসা"
        MARATHI  -> "आरोग्य · प्रथमोपचार"
        KANNADA  -> "ಆರೋಗ್ಯ · ಪ್ರಥಮ ಚಿಕಿತ್ಸೆ"
        GUJARATI -> "આરોગ્ય · પ્રાથમિક સારવાર"
        PUNJABI  -> "ਸਿਹਤ · ਮੁੱਢਲੀ ਸਹਾਇਤਾ"
        ODIA     -> "ସୁସ୍ଥତା · ପ୍ରଥମ ଚିକିତ୍ସା"
    }

    /** "THOUGHT OF THE DAY" card label. */
    val thoughtOfDayLabel: String get() = when (this) {
        ENGLISH  -> "THOUGHT OF THE DAY"
        HINDI    -> "आज का विचार"
        TAMIL    -> "இன்றைய சிந்தனை"
        TELUGU   -> "నేటి ఆలోచన"
        BENGALI  -> "আজকের ভাবনা"
        MARATHI  -> "आजचा विचार"
        KANNADA  -> "ಇಂದಿನ ಚಿಂತನೆ"
        GUJARATI -> "આજનો વિચાર"
        PUNJABI  -> "ਅੱਜ ਦਾ ਵਿਚਾਰ"
        ODIA     -> "ଆଜିର ଚିନ୍ତା"
    }

    /**
     * The canonical "who are you" answer, localized. Injected as grounding when
     * an identity question is detected (any language), so the model states the
     * agreed Saarthi identity instead of leaking a base-model "I'm a language
     * model" answer. Keep it warm, 2 short sentences, never name any model/tech.
     */
    val identityAnswer: String get() = when (this) {
        ENGLISH  -> "I'm Saarthi, your friendly AI assistant made for India. I run fully offline on your phone, so our conversations stay private — ask me anything by text, voice, photo, or file."
        HINDI    -> "मैं सारथी हूँ, भारत के लिए बना आपका मित्रवत AI सहायक। मैं पूरी तरह आपके फ़ोन पर ऑफ़लाइन चलता हूँ, इसलिए हमारी बातचीत निजी रहती है — टेक्स्ट, आवाज़, फ़ोटो या फ़ाइल से कुछ भी पूछें।"
        TAMIL    -> "நான் சாரதி, இந்தியாவுக்காக உருவாக்கப்பட்ட உங்கள் நட்பான AI உதவியாளர். நான் முழுவதும் உங்கள் தொலைபேசியில் ஆஃப்லைனில் இயங்குகிறேன், அதனால் நம் உரையாடல்கள் தனிப்பட்டதாக இருக்கும் — உரை, குரல், படம் அல்லது கோப்பு மூலம் எதையும் கேளுங்கள்."
        TELUGU   -> "నేను సారథిని, భారతదేశం కోసం రూపొందించిన మీ స్నేహపూర్వక AI సహాయకుడిని. నేను పూర్తిగా మీ ఫోన్‌లో ఆఫ్‌లైన్‌లో పనిచేస్తాను, కాబట్టి మన సంభాషణలు గోప్యంగా ఉంటాయి — టెక్స్ట్, వాయిస్, ఫోటో లేదా ఫైల్‌తో ఏదైనా అడగండి."
        BENGALI  -> "আমি সারথি, ভারতের জন্য তৈরি আপনার বন্ধুত্বপূর্ণ AI সহকারী। আমি সম্পূর্ণ আপনার ফোনে অফলাইনে চলি, তাই আমাদের কথোপকথন ব্যক্তিগত থাকে — টেক্সট, ভয়েস, ছবি বা ফাইলে যেকোনো কিছু জিজ্ঞাসা করুন।"
        MARATHI  -> "मी सारथी आहे, भारतासाठी बनवलेला तुमचा मित्रत्वपूर्ण AI सहाय्यक. मी पूर्णपणे तुमच्या फोनवर ऑफलाइन चालतो, त्यामुळे आपले संभाषण खाजगी राहते — मजकूर, आवाज, फोटो किंवा फाइलने काहीही विचारा."
        KANNADA  -> "ನಾನು ಸಾರಥಿ, ಭಾರತಕ್ಕಾಗಿ ರೂಪಿಸಿದ ನಿಮ್ಮ ಸ್ನೇಹಪರ AI ಸಹಾಯಕ. ನಾನು ಸಂಪೂರ್ಣವಾಗಿ ನಿಮ್ಮ ಫೋನ್‌ನಲ್ಲಿ ಆಫ್‌ಲೈನ್‌ನಲ್ಲಿ ಕೆಲಸ ಮಾಡುತ್ತೇನೆ, ಆದ್ದರಿಂದ ನಮ್ಮ ಸಂಭಾಷಣೆಗಳು ಖಾಸಗಿಯಾಗಿರುತ್ತವೆ — ಪಠ್ಯ, ಧ್ವನಿ, ಚಿತ್ರ ಅಥವಾ ಫೈಲ್‌ನಲ್ಲಿ ಏನಾದರೂ ಕೇಳಿ."
        GUJARATI -> "હું સારથી છું, ભારત માટે બનાવેલો તમારો મિત્રતાભર્યો AI સહાયક. હું સંપૂર્ણપણે તમારા ફોન પર ઑફલાઇન ચાલું છું, તેથી આપણી વાતચીત ખાનગી રહે છે — ટેક્સ્ટ, અવાજ, ફોટો કે ફાઇલથી કંઈ પણ પૂછો."
        PUNJABI  -> "ਮੈਂ ਸਾਰਥੀ ਹਾਂ, ਭਾਰਤ ਲਈ ਬਣਾਇਆ ਤੁਹਾਡਾ ਦੋਸਤਾਨਾ AI ਸਹਾਇਕ। ਮੈਂ ਪੂਰੀ ਤਰ੍ਹਾਂ ਤੁਹਾਡੇ ਫ਼ੋਨ 'ਤੇ ਆਫ਼ਲਾਈਨ ਚੱਲਦਾ ਹਾਂ, ਇਸ ਲਈ ਸਾਡੀ ਗੱਲਬਾਤ ਨਿੱਜੀ ਰਹਿੰਦੀ ਹੈ — ਟੈਕਸਟ, ਆਵਾਜ਼, ਫ਼ੋਟੋ ਜਾਂ ਫ਼ਾਈਲ ਨਾਲ ਕੁਝ ਵੀ ਪੁੱਛੋ।"
        ODIA     -> "ମୁଁ ସାରଥୀ, ଭାରତ ପାଇଁ ତିଆରି ଆପଣଙ୍କ ବନ୍ଧୁତ୍ୱପୂର୍ଣ୍ଣ AI ସହାୟକ। ମୁଁ ସମ୍ପୂର୍ଣ୍ଣ ଆପଣଙ୍କ ଫୋନରେ ଅଫଲାଇନ ଚାଲେ, ତେଣୁ ଆମ କଥାବାର୍ତ୍ତା ଗୋପନୀୟ ରହେ — ଟେକ୍ସଟ, ସ୍ୱର, ଫଟୋ କିମ୍ବା ଫାଇଲରେ ଯାହା ବି ପଚାରନ୍ତୁ।"
    }

    // ── Chat error / empty-state copy (localized) ────────────────────────────

    /** Generic stream failure shown in the chat bubble. */
    val errorGeneric: String get() = when (this) {
        ENGLISH  -> "Something went wrong. Please try again."
        HINDI    -> "कुछ गड़बड़ हो गई। कृपया फिर से कोशिश करें।"
        TAMIL    -> "ஏதோ தவறு நடந்தது. மீண்டும் முயற்சிக்கவும்."
        TELUGU   -> "ఏదో పొరపాటు జరిగింది. దయచేసి మళ్లీ ప్రయత్నించండి."
        BENGALI  -> "কিছু একটা ভুল হয়েছে। আবার চেষ্টা করুন।"
        MARATHI  -> "काहीतरी चूक झाली. कृपया पुन्हा प्रयत्न करा."
        KANNADA  -> "ಏನೋ ತಪ್ಪಾಗಿದೆ. ದಯವಿಟ್ಟು ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ."
        GUJARATI -> "કંઈક ખોટું થયું. કૃપા કરી ફરી પ્રયાસ કરો."
        PUNJABI  -> "ਕੁਝ ਗਲਤ ਹੋ ਗਿਆ। ਕਿਰਪਾ ਕਰਕੇ ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ।"
        ODIA     -> "କିଛି ଭୁଲ ହୋଇଗଲା। ଦୟାକରି ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ।"
    }

    /** Failure while generating a reply (bubble fallback). */
    val errorGenerating: String get() = when (this) {
        ENGLISH  -> "Something went wrong generating a reply. Please try again."
        HINDI    -> "जवाब बनाते समय कुछ गड़बड़ हो गई। कृपया फिर से कोशिश करें।"
        TAMIL    -> "பதில் உருவாக்கும்போது தவறு நடந்தது. மீண்டும் முயற்சிக்கவும்."
        TELUGU   -> "సమాధానం రూపొందించేటప్పుడు పొరపాటు జరిగింది. మళ్లీ ప్రయత్నించండి."
        BENGALI  -> "উত্তর তৈরি করার সময় ভুল হয়েছে। আবার চেষ্টা করুন।"
        MARATHI  -> "उत्तर तयार करताना चूक झाली. कृपया पुन्हा प्रयत्न करा."
        KANNADA  -> "ಉತ್ತರ ರಚಿಸುವಾಗ ತಪ್ಪಾಗಿದೆ. ದಯವಿಟ್ಟು ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ."
        GUJARATI -> "જવાબ બનાવતી વખતે કંઈક ખોટું થયું. ફરી પ્રયાસ કરો."
        PUNJABI  -> "ਜਵਾਬ ਬਣਾਉਂਦੇ ਸਮੇਂ ਕੁਝ ਗਲਤ ਹੋ ਗਿਆ। ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ।"
        ODIA     -> "ଉତ୍ତର ତିଆରି କରିବା ସମୟରେ କିଛି ଭୁଲ ହେଲା। ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ।"
    }

    /** Shown when the user stops generation and no partial text exists. */
    val stoppedReply: String get() = when (this) {
        ENGLISH  -> "Stopped."
        HINDI    -> "रोक दिया।"
        TAMIL    -> "நிறுத்தப்பட்டது."
        TELUGU   -> "ఆపివేయబడింది."
        BENGALI  -> "থামানো হয়েছে।"
        MARATHI  -> "थांबवले."
        KANNADA  -> "ನಿಲ್ಲಿಸಲಾಗಿದೆ."
        GUJARATI -> "બંધ કર્યું."
        PUNJABI  -> "ਰੋਕ ਦਿੱਤਾ।"
        ODIA     -> "ବନ୍ଦ କରାଗଲା।"
    }

    /** Model produced nothing (usually memory pressure) — actionable next step. */
    val emptyReply: String get() = when (this) {
        ENGLISH  -> "I couldn't generate a reply just now. Please try again — if it keeps happening, switch to a lighter model in Settings."
        HINDI    -> "मैं अभी जवाब नहीं बना सका। कृपया फिर से कोशिश करें — अगर बार-बार हो, तो सेटिंग्स में हल्का मॉडल चुनें।"
        TAMIL    -> "என்னால் இப்போது பதில் உருவாக்க முடியவில்லை. மீண்டும் முயற்சிக்கவும் — தொடர்ந்தால், Settings-ல் இலகுவான மாடலுக்கு மாறவும்."
        TELUGU   -> "నేను ఇప్పుడే సమాధానం ఇవ్వలేకపోయాను. మళ్లీ ప్రయత్నించండి — పదే పదే జరిగితే, Settings లో తేలికైన మోడల్‌కు మారండి."
        BENGALI  -> "আমি এখনই উত্তর তৈরি করতে পারিনি। আবার চেষ্টা করুন — বারবার হলে, Settings-এ হালকা মডেলে যান।"
        MARATHI  -> "मला आत्ता उत्तर तयार करता आले नाही. कृपया पुन्हा प्रयत्न करा — वारंवार होत असेल, तर Settings मध्ये हलका मॉडेल निवडा."
        KANNADA  -> "ನನಗೆ ಈಗ ಉತ್ತರ ರಚಿಸಲು ಆಗಲಿಲ್ಲ. ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ — ಪದೇ ಪದೇ ಆದರೆ, Settings ನಲ್ಲಿ ಹಗುರ ಮಾದರಿಗೆ ಬದಲಿಸಿ."
        GUJARATI -> "હું હમણાં જવાબ બનાવી શક્યો નહીં. ફરી પ્રયાસ કરો — વારંવાર થાય તો, Settings માં હળવા મૉડલ પર જાઓ."
        PUNJABI  -> "ਮੈਂ ਹੁਣੇ ਜਵਾਬ ਨਹੀਂ ਬਣਾ ਸਕਿਆ। ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ — ਜੇ ਵਾਰ-ਵਾਰ ਹੋਵੇ, ਤਾਂ Settings ਵਿੱਚ ਹਲਕਾ ਮਾਡਲ ਚੁਣੋ।"
        ODIA     -> "ମୁଁ ବର୍ତ୍ତମାନ ଉତ୍ତର ତିଆରି କରିପାରିଲି ନାହିଁ। ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ — ବାରମ୍ବାର ହେଲେ, Settings ରେ ହାଲୁକା ମଡେଲକୁ ଯାଆନ୍ତୁ।"
    }

    /** Kisan chat: model not loaded (low memory). */
    val packModelNotLoaded: String get() = when (this) {
        ENGLISH  -> "Saarthi's model isn't loaded right now — your phone may be low on memory. Close a few background apps and try again. Everything still works offline."
        HINDI    -> "सारथी का मॉडल अभी लोड नहीं है — आपके फ़ोन की मेमोरी कम हो सकती है। कुछ बैकग्राउंड ऐप बंद करके फिर कोशिश करें। सब कुछ ऑफ़लाइन ही काम करता है।"
        TAMIL    -> "சாரதியின் மாடல் இப்போது ஏற்றப்படவில்லை — உங்கள் தொலைபேசியில் நினைவகம் குறைவாக இருக்கலாம். சில பின்னணி ஆப்களை மூடிவிட்டு மீண்டும் முயற்சிக்கவும். எல்லாம் ஆஃப்லைனில் வேலை செய்யும்."
        TELUGU   -> "సారథి మోడల్ ఇప్పుడు లోడ్ కాలేదు — మీ ఫోన్‌లో మెమొరీ తక్కువగా ఉండవచ్చు. కొన్ని బ్యాక్‌గ్రౌండ్ యాప్‌లను మూసివేసి మళ్లీ ప్రయత్నించండి. అంతా ఆఫ్‌లైన్‌లోనే పనిచేస్తుంది."
        BENGALI  -> "সারথির মডেল এখন লোড নেই — আপনার ফোনে মেমরি কম থাকতে পারে। কয়েকটি ব্যাকগ্রাউন্ড অ্যাপ বন্ধ করে আবার চেষ্টা করুন। সবকিছু অফলাইনেই কাজ করে।"
        MARATHI  -> "सारथीचे मॉडेल आत्ता लोड झालेले नाही — तुमच्या फोनची मेमरी कमी असू शकते. काही बॅकग्राउंड अॅप्स बंद करून पुन्हा प्रयत्न करा. सर्व काही ऑफलाइनच काम करते."
        KANNADA  -> "ಸಾರಥಿಯ ಮಾದರಿ ಈಗ ಲೋಡ್ ಆಗಿಲ್ಲ — ನಿಮ್ಮ ಫೋನ್‌ನಲ್ಲಿ ಮೆಮೊರಿ ಕಡಿಮೆ ಇರಬಹುದು. ಕೆಲವು ಬ್ಯಾಕ್‌ಗ್ರೌಂಡ್ ಆ್ಯಪ್‌ಗಳನ್ನು ಮುಚ್ಚಿ ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ. ಎಲ್ಲವೂ ಆಫ್‌ಲೈನ್‌ನಲ್ಲೇ ಕೆಲಸ ಮಾಡುತ್ತದೆ."
        GUJARATI -> "સારથીનું મૉડલ અત્યારે લોડ નથી — તમારા ફોનમાં મેમરી ઓછી હોઈ શકે. થોડી બૅકગ્રાઉન્ડ એપ બંધ કરી ફરી પ્રયાસ કરો. બધું ઑફલાઇન જ કામ કરે છે."
        PUNJABI  -> "ਸਾਰਥੀ ਦਾ ਮਾਡਲ ਹੁਣੇ ਲੋਡ ਨਹੀਂ ਹੈ — ਤੁਹਾਡੇ ਫ਼ੋਨ ਦੀ ਮੈਮੋਰੀ ਘੱਟ ਹੋ ਸਕਦੀ ਹੈ। ਕੁਝ ਬੈਕਗ੍ਰਾਊਂਡ ਐਪ ਬੰਦ ਕਰਕੇ ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ। ਸਭ ਕੁਝ ਆਫ਼ਲਾਈਨ ਹੀ ਕੰਮ ਕਰਦਾ ਹੈ।"
        ODIA     -> "ସାରଥୀଙ୍କ ମଡେଲ ବର୍ତ୍ତମାନ ଲୋଡ ହୋଇନାହିଁ — ଆପଣଙ୍କ ଫୋନରେ ମେମୋରୀ କମ ଥାଇପାରେ। କିଛି ବ୍ୟାକଗ୍ରାଉଣ୍ଡ ଆପ ବନ୍ଦ କରି ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ। ସବୁକିଛି ଅଫଲାଇନରେ ହିଁ କାମ କରେ।"
    }

    /** Kisan chat: model too small for reliable pack answers. */
    val packModelTooSmall: String get() = when (this) {
        ENGLISH  -> "The compact model on this phone is too small for reliable pack answers. You can still read the pack topics offline. For chat, switch to Gemma 4 or Gemma 3n in Settings → Models."
        HINDI    -> "इस फ़ोन का कॉम्पैक्ट मॉडल भरोसेमंद पैक जवाबों के लिए बहुत छोटा है। आप पैक विषय फिर भी ऑफ़लाइन पढ़ सकते हैं। चैट के लिए सेटिंग्स → मॉडल में Gemma 4 या Gemma 3n चुनें।"
        TAMIL    -> "இந்தத் தொலைபேசியின் காம்பாக்ட் மாடல் நம்பகமான தொகுப்பு பதில்களுக்கு மிகச் சிறியது. தொகுப்பு தலைப்புகளை ஆஃப்லைனில் படிக்கலாம். அரட்டைக்கு Settings → Models-ல் Gemma 4 அல்லது Gemma 3n-க்கு மாறவும்."
        TELUGU   -> "ఈ ఫోన్‌లోని కాంపాక్ట్ మోడల్ నమ్మదగిన ప్యాక్ సమాధానాలకు చాలా చిన్నది. ప్యాక్ అంశాలను ఆఫ్‌లైన్‌లో చదవవచ్చు. చాట్ కోసం Settings → Models లో Gemma 4 లేదా Gemma 3n కు మారండి."
        BENGALI  -> "এই ফোনের কমপ্যাক্ট মডেল নির্ভরযোগ্য প্যাক উত্তরের জন্য খুব ছোট। আপনি প্যাকের বিষয় অফলাইনে পড়তে পারেন। চ্যাটের জন্য Settings → Models-এ Gemma 4 বা Gemma 3n বেছে নিন।"
        MARATHI  -> "या फोनवरील कॉम्पॅक्ट मॉडेल विश्वसनीय पॅक उत्तरांसाठी खूप लहान आहे. तुम्ही पॅक विषय ऑफलाइन वाचू शकता. चॅटसाठी Settings → Models मध्ये Gemma 4 किंवा Gemma 3n निवडा."
        KANNADA  -> "ಈ ಫೋನ್‌ನ ಕಾಂಪ್ಯಾಕ್ಟ್ ಮಾದರಿ ವಿಶ್ವಾಸಾರ್ಹ ಪ್ಯಾಕ್ ಉತ್ತರಗಳಿಗೆ ತುಂಬಾ ಚಿಕ್ಕದು. ಪ್ಯಾಕ್ ವಿಷಯಗಳನ್ನು ಆಫ್‌ಲೈನ್‌ನಲ್ಲಿ ಓದಬಹುದು. ಚಾಟ್‌ಗೆ Settings → Models ನಲ್ಲಿ Gemma 4 ಅಥವಾ Gemma 3n ಗೆ ಬದಲಿಸಿ."
        GUJARATI -> "આ ફોનનું કૉમ્પેક્ટ મૉડલ વિશ્વસનીય પૅક જવાબો માટે ખૂબ નાનું છે. તમે પૅક વિષયો ઑફલાઇન વાંચી શકો છો. ચેટ માટે Settings → Models માં Gemma 4 કે Gemma 3n પસંદ કરો."
        PUNJABI  -> "ਇਸ ਫ਼ੋਨ ਦਾ ਕੌਂਪੈਕਟ ਮਾਡਲ ਭਰੋਸੇਯੋਗ ਪੈਕ ਜਵਾਬਾਂ ਲਈ ਬਹੁਤ ਛੋਟਾ ਹੈ। ਤੁਸੀਂ ਪੈਕ ਵਿਸ਼ੇ ਆਫ਼ਲਾਈਨ ਪੜ੍ਹ ਸਕਦੇ ਹੋ। ਚੈਟ ਲਈ Settings → Models ਵਿੱਚ Gemma 4 ਜਾਂ Gemma 3n ਚੁਣੋ।"
        ODIA     -> "ଏହି ଫୋନର କମ୍ପାକ୍ଟ ମଡେଲ ନିର୍ଭରଯୋଗ୍ୟ ପ୍ୟାକ ଉତ୍ତର ପାଇଁ ବହୁତ ଛୋଟ। ଆପଣ ପ୍ୟାକ ବିଷୟ ଅଫଲାଇନରେ ପଢ଼ିପାରିବେ। ଚାଟ ପାଇଁ Settings → Models ରେ Gemma 4 କିମ୍ବା Gemma 3n ବାଛନ୍ତୁ।"
    }

    /** Kisan chat: generation failed. */
    val packGenerationError: String get() = when (this) {
        ENGLISH  -> "Sorry, I couldn't generate an answer just now. Please try again, or ask a shorter question."
        HINDI    -> "क्षमा करें, मैं अभी जवाब नहीं बना सका। कृपया फिर से कोशिश करें, या छोटा सवाल पूछें।"
        TAMIL    -> "மன்னிக்கவும், என்னால் இப்போது பதில் உருவாக்க முடியவில்லை. மீண்டும் முயற்சிக்கவும், அல்லது சுருக்கமான கேள்வி கேளுங்கள்."
        TELUGU   -> "క్షమించండి, నేను ఇప్పుడే సమాధానం ఇవ్వలేకపోయాను. మళ్లీ ప్రయత్నించండి, లేదా చిన్న ప్రశ్న అడగండి."
        BENGALI  -> "দুঃখিত, আমি এখনই উত্তর তৈরি করতে পারিনি। আবার চেষ্টা করুন, বা ছোট প্রশ্ন করুন।"
        MARATHI  -> "क्षमस्व, मला आत्ता उत्तर तयार करता आले नाही. कृपया पुन्हा प्रयत्न करा, किंवा छोटा प्रश्न विचारा."
        KANNADA  -> "ಕ್ಷಮಿಸಿ, ನನಗೆ ಈಗ ಉತ್ತರ ರಚಿಸಲು ಆಗಲಿಲ್ಲ. ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ, ಅಥವಾ ಚಿಕ್ಕ ಪ್ರಶ್ನೆ ಕೇಳಿ."
        GUJARATI -> "માફ કરશો, હું હમણાં જવાબ બનાવી શક્યો નહીં. ફરી પ્રયાસ કરો, અથવા ટૂંકો પ્રશ્ન પૂછો."
        PUNJABI  -> "ਮਾਫ਼ ਕਰਨਾ, ਮੈਂ ਹੁਣੇ ਜਵਾਬ ਨਹੀਂ ਬਣਾ ਸਕਿਆ। ਦੁਬਾਰਾ ਕੋਸ਼ਿਸ਼ ਕਰੋ, ਜਾਂ ਛੋਟਾ ਸਵਾਲ ਪੁੱਛੋ।"
        ODIA     -> "କ୍ଷମା କରନ୍ତୁ, ମୁଁ ବର୍ତ୍ତମାନ ଉତ୍ତର ତିଆରି କରିପାରିଲି ନାହିଁ। ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ, କିମ୍ବା ଛୋଟ ପ୍ରଶ୍ନ ପଚାରନ୍ତୁ।"
    }

    // ── Notification copy (localized) ────────────────────────────────────────

    /** Title of a one-off user reminder notification (an emoji is prefixed in code). */
    val reminderNotificationTitle: String get() = when (this) {
        ENGLISH  -> "Saarthi Reminder"
        HINDI    -> "सारथी रिमाइंडर"
        TAMIL    -> "சாரதி நினைவூட்டல்"
        TELUGU   -> "సారథి రిమైండర్"
        BENGALI  -> "সারথি রিমাইন্ডার"
        MARATHI  -> "सारथी स्मरणपत्र"
        KANNADA  -> "ಸಾರಥಿ ಜ್ಞಾಪನೆ"
        GUJARATI -> "સારથી રિમાઇન્ડર"
        PUNJABI  -> "ਸਾਰਥੀ ਰਿਮਾਈਂਡਰ"
        ODIA     -> "ସାରଥୀ ସ୍ମାରକ"
    }

    /** Title of the daily wisdom notification (a lamp emoji is prefixed in code). */
    val wisdomNotificationTitle: String get() = when (this) {
        ENGLISH  -> "Thought of the day"
        HINDI    -> "आज का विचार"
        TAMIL    -> "இன்றைய சிந்தனை"
        TELUGU   -> "నేటి ఆలోచన"
        BENGALI  -> "আজকের ভাবনা"
        MARATHI  -> "आजचा विचार"
        KANNADA  -> "ಇಂದಿನ ಚಿಂತನೆ"
        GUJARATI -> "આજનો વિચાર"
        PUNJABI  -> "ਅੱਜ ਦਾ ਵਿਚਾਰ"
        ODIA     -> "ଆଜିର ଚିନ୍ତା"
    }

    /** Inference foreground-service notification: shown while the model is loading from disk. */
    val loadingModelTitle: String get() = when (this) {
        ENGLISH  -> "Saarthi is loading a model…"
        HINDI    -> "सारथी मॉडल लोड कर रहा है…"
        TAMIL    -> "சாரதி மாடலை ஏற்றுகிறது…"
        TELUGU   -> "సారథి మోడల్‌ను లోడ్ చేస్తోంది…"
        BENGALI  -> "সারথি মডেল লোড করছে…"
        MARATHI  -> "सारथी मॉडेल लोड करत आहे…"
        KANNADA  -> "ಸಾರಥಿ ಮಾಡೆಲ್ ಲೋಡ್ ಮಾಡುತ್ತಿದೆ…"
        GUJARATI -> "સારથી મોડેલ લોડ કરી રહ્યું છે…"
        PUNJABI  -> "ਸਾਰਥੀ ਮਾਡਲ ਲੋਡ ਕਰ ਰਿਹਾ ਹੈ…"
        ODIA     -> "ସାରଥୀ ମଡେଲ ଲୋଡ କରୁଛି…"
    }
    val loadingModelBody: String get() = when (this) {
        ENGLISH  -> "Preparing the AI model. This takes a few seconds."
        HINDI    -> "AI मॉडल तैयार किया जा रहा है। इसमें कुछ सेकंड लगते हैं।"
        TAMIL    -> "AI மாடல் தயார் செய்யப்படுகிறது. இதற்கு சில வினாடிகள் ஆகும்."
        TELUGU   -> "AI మోడల్ సిద్ధమవుతోంది. దీనికి కొన్ని సెకన్లు పడుతుంది."
        BENGALI  -> "AI মডেল প্রস্তুত করা হচ্ছে। এতে কয়েক সেকেন্ড সময় লাগে।"
        MARATHI  -> "AI मॉडेल तयार केले जात आहे. यासाठी काही सेकंद लागतात."
        KANNADA  -> "AI ಮಾಡೆಲ್ ಸಿದ್ಧಪಡಿಸಲಾಗುತ್ತಿದೆ. ಇದಕ್ಕೆ ಕೆಲವು ಸೆಕೆಂಡುಗಳು ಬೇಕಾಗುತ್ತವೆ."
        GUJARATI -> "AI મોડેલ તૈયાર કરવામાં આવી રહ્યું છે. આમાં થોડી સેકંડ લાગે છે."
        PUNJABI  -> "AI ਮਾਡਲ ਤਿਆਰ ਕੀਤਾ ਜਾ ਰਿਹਾ ਹੈ। ਇਸ ਵਿੱਚ ਕੁਝ ਸਕਿੰਟ ਲੱਗਦੇ ਹਨ।"
        ODIA     -> "AI ମଡେଲ ପ୍ରସ୍ତୁତ ହେଉଛି। ଏଥିରେ କିଛି ସେକେଣ୍ଡ ଲାଗେ।"
    }
    /** Inference foreground-service notification: shown while a response is streaming. */
    val generatingResponseTitle: String get() = when (this) {
        ENGLISH  -> "Saarthi is generating a response…"
        HINDI    -> "सारथी जवाब तैयार कर रहा है…"
        TAMIL    -> "சாரதி பதிலை உருவாக்குகிறது…"
        TELUGU   -> "సారథి సమాధానం తయారు చేస్తోంది…"
        BENGALI  -> "সারথি উত্তর তৈরি করছে…"
        MARATHI  -> "सारथी उत्तर तयार करत आहे…"
        KANNADA  -> "ಸಾರಥಿ ಉತ್ತರ ಸಿದ್ಧಪಡಿಸುತ್ತಿದೆ…"
        GUJARATI -> "સારથી જવાબ તૈયાર કરી રહ્યું છે…"
        PUNJABI  -> "ਸਾਰਥੀ ਜਵਾਬ ਤਿਆਰ ਕਰ ਰਿਹਾ ਹੈ…"
        ODIA     -> "ସାରଥୀ ଉତ୍ତର ପ୍ରସ୍ତୁତ କରୁଛି…"
    }
    val generatingResponseBody: String get() = when (this) {
        ENGLISH  -> "Processing your message offline."
        HINDI    -> "आपके संदेश को ऑफ़लाइन प्रोसेस किया जा रहा है।"
        TAMIL    -> "உங்கள் செய்தி ஆஃப்லைனில் செயலாக்கப்படுகிறது."
        TELUGU   -> "మీ సందేశం ఆఫ్‌లైన్‌లో ప్రాసెస్ అవుతోంది."
        BENGALI  -> "আপনার বার্তা অফলাইনে প্রসেস করা হচ্ছে।"
        MARATHI  -> "तुमचा संदेश ऑफलाइन प्रक्रिया केला जात आहे."
        KANNADA  -> "ನಿಮ್ಮ ಸಂದೇಶವನ್ನು ಆಫ್‌ಲೈನ್‌ನಲ್ಲಿ ಪ್ರಕ್ರಿಯೆಗೊಳಿಸಲಾಗುತ್ತಿದೆ."
        GUJARATI -> "તમારો સંદેશ ઑફલાઇન પ્રોસેસ કરવામાં આવી રહ્યો છે."
        PUNJABI  -> "ਤੁਹਾਡਾ ਸੁਨੇਹਾ ਆਫਲਾਈਨ ਪ੍ਰੋਸੈਸ ਕੀਤਾ ਜਾ ਰਿਹਾ ਹੈ।"
        ODIA     -> "ଆପଣଙ୍କ ସନ୍ଦେଶ ଅଫଲାଇନ୍‌ରେ ପ୍ରକ୍ରିୟାକରଣ ହେଉଛି।"
    }

    /** Model-download notification: title prefix before the model name ("Downloading Gemma 4"). */
    val downloadingTitlePrefix: String get() = when (this) {
        ENGLISH  -> "Downloading"
        HINDI    -> "डाउनलोड हो रहा है"
        TAMIL    -> "பதிவிறக்குகிறது"
        TELUGU   -> "డౌన్‌లోడ్ అవుతోంది"
        BENGALI  -> "ডাউনলোড হচ্ছে"
        MARATHI  -> "डाउनलोड होत आहे"
        KANNADA  -> "ಡೌನ್‌ಲೋಡ್ ಆಗುತ್ತಿದೆ"
        GUJARATI -> "ડાઉનલોડ થઈ રહ્યું છે"
        PUNJABI  -> "ਡਾਊਨਲੋਡ ਹੋ ਰਿਹਾ ਹੈ"
        ODIA     -> "ଡାଉନଲୋଡ ହେଉଛି"
    }

    /** Model-download notification: shown before progress numbers arrive. */
    val startingDownload: String get() = when (this) {
        ENGLISH  -> "Starting download…"
        HINDI    -> "डाउनलोड शुरू हो रहा है…"
        TAMIL    -> "பதிவிறக்கம் தொடங்குகிறது…"
        TELUGU   -> "డౌన్‌లోడ్ ప్రారంభమవుతోంది…"
        BENGALI  -> "ডাউনলোড শুরু হচ্ছে…"
        MARATHI  -> "डाउनलोड सुरू होत आहे…"
        KANNADA  -> "ಡೌನ್‌ಲೋಡ್ ಪ್ರಾರಂಭವಾಗುತ್ತಿದೆ…"
        GUJARATI -> "ડાઉનલોડ શરૂ થઈ રહ્યું છે…"
        PUNJABI  -> "ਡਾਊਨਲੋਡ ਸ਼ੁਰੂ ਹੋ ਰਿਹਾ ਹੈ…"
        ODIA     -> "ଡାଉନଲୋଡ ଆରମ୍ଭ ହେଉଛି…"
    }

    /** Title of the "Kisan pack refreshed" notification (a crop emoji is prefixed in code). */
    val packUpdatedTitle: String get() = when (this) {
        ENGLISH  -> "Kisan pack updated"
        HINDI    -> "किसान पैक अपडेट हुआ"
        TAMIL    -> "கிசான் தொகுப்பு புதுப்பிக்கப்பட்டது"
        TELUGU   -> "కిసాన్ ప్యాక్ నవీకరించబడింది"
        BENGALI  -> "কিষাণ প্যাক আপডেট হয়েছে"
        MARATHI  -> "किसान पॅक अपडेट झाले"
        KANNADA  -> "ಕಿಸಾನ್ ಪ್ಯಾಕ್ ನವೀಕರಿಸಲಾಗಿದೆ"
        GUJARATI -> "કિસાન પૅક અપડેટ થયું"
        PUNJABI  -> "ਕਿਸਾਨ ਪੈਕ ਅੱਪਡੇਟ ਹੋਇਆ"
        ODIA     -> "କିସାନ ପ୍ୟାକ ଅପଡେଟ ହେଲା"
    }

    /** Body of the "Kisan pack refreshed" notification. */
    val packUpdatedBody: String get() = when (this) {
        ENGLISH  -> "Saarthi refreshed the Kisan knowledge pack with the latest government data."
        HINDI    -> "सारथी ने किसान ज्ञान पैक को नवीनतम सरकारी डेटा के साथ अपडेट किया है।"
        TAMIL    -> "சாரதி கிசான் அறிவுத் தொகுப்பை சமீபத்திய அரசு தரவுடன் புதுப்பித்துள்ளது."
        TELUGU   -> "సారథి కిసాన్ నాలెడ్జ్ ప్యాక్‌ను తాజా ప్రభుత్వ డేటాతో నవీకరించింది."
        BENGALI  -> "সারথি কিষাণ জ্ঞান প্যাকটি সর্বশেষ সরকারি তথ্য দিয়ে আপডেট করেছে।"
        MARATHI  -> "सारथीने किसान ज्ञान पॅक नवीनतम सरकारी डेटासह अपडेट केले आहे."
        KANNADA  -> "ಸಾರಥಿ ಕಿಸಾನ್ ಜ್ಞಾನ ಪ್ಯಾಕ್ ಅನ್ನು ಇತ್ತೀಚಿನ ಸರ್ಕಾರಿ ಡೇಟಾದೊಂದಿಗೆ ನವೀಕರಿಸಿದೆ."
        GUJARATI -> "સારથીએ કિસાન જ્ઞાન પૅકને નવીનતમ સરકારી ડેટા સાથે અપડેટ કર્યું છે."
        PUNJABI  -> "ਸਾਰਥੀ ਨੇ ਕਿਸਾਨ ਗਿਆਨ ਪੈਕ ਨੂੰ ਨਵੀਨਤਮ ਸਰਕਾਰੀ ਡੇਟਾ ਨਾਲ ਅੱਪਡੇਟ ਕੀਤਾ ਹੈ।"
        ODIA     -> "ସାରଥୀ କିସାନ ଜ୍ଞାନ ପ୍ୟାକକୁ ସର୍ବଶେଷ ସରକାରୀ ତଥ୍ୟ ସହ ଅପଡେଟ କରିଛି।"
    }

    /**
     * Instruction sandwiched at the TOP and BOTTOM of the system prompt so
     * the model responds in this language.
     *
     * Each non-English directive starts with a **native-script seed phrase**
     * meaning "reply in <this language>". Models with weaker
     * instruction-following (Gemma 3n in particular) anchor on the script of
     * the directive itself — an English-only "Reply in Telugu" line gets
     * ignored because the surrounding English prompt dilutes it. A
     * native-script seed plus an emphatic English emphasis is the industry-
     * standard pattern for multilingual production prompts.
     */
    val systemPromptInstruction: String get() = when (this) {
        ENGLISH  -> "Reply ONLY in English. You MUST reply entirely in English. Do not reply in Hindi, Marathi, or any other language or script under any circumstance."
        HINDI    -> "हिन्दी में जवाब दें। You MUST reply entirely in Hindi (हिन्दी), in Devanagari script. Do not write the reply in English under any circumstance."
        TAMIL    -> "தமிழில் பதிலளிக்கவும். You MUST reply entirely in Tamil (தமிழ்), in Tamil script. Do not write the reply in English under any circumstance."
        TELUGU   -> "తెలుగులో సమాధానం ఇవ్వండి. You MUST reply entirely in Telugu (తెలుగు), in Telugu script. Do not write the reply in English under any circumstance."
        BENGALI  -> "বাংলায় উত্তর দিন। You MUST reply entirely in Bengali (বাংলা), in Bengali script. Do not write the reply in English under any circumstance."
        MARATHI  -> "मराठीत उत्तर द्या. You MUST reply entirely in Marathi (मराठी), in Devanagari script. Do not write the reply in English under any circumstance."
        KANNADA  -> "ಕನ್ನಡದಲ್ಲಿ ಉತ್ತರಿಸಿ. You MUST reply entirely in Kannada (ಕನ್ನಡ), in Kannada script. Do not write the reply in English under any circumstance."
        GUJARATI -> "ગુજરાતીમાં જવાબ આપો. You MUST reply entirely in Gujarati (ગુજરાતી), in Gujarati script. Do not write the reply in English under any circumstance."
        PUNJABI  -> "ਪੰਜਾਬੀ ਵਿੱਚ ਜਵਾਬ ਦਿਓ। You MUST reply entirely in Punjabi (ਪੰਜਾਬੀ), in Gurmukhi script. Do not write the reply in English under any circumstance."
        ODIA     -> "ଓଡ଼ିଆରେ ଉତ୍ତର ଦିଅନ୍ତୁ। You MUST reply entirely in Odia (ଓଡ଼ିଆ), in Odia script. Do not write the reply in English under any circumstance."
    }

    companion object {
        fun fromCode(code: String): SupportedLanguage =
            entries.firstOrNull { it.code == code } ?: HINDI
    }
}
