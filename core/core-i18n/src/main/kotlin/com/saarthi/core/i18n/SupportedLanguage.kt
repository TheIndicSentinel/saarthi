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
            HINDI    -> listOf("सुप्रभात", "शुभ दोपहर", "शुभ संध्या", "शुभ रात्रि")
            TAMIL    -> listOf("காலை வணக்கம்", "மதிய வணக்கம்", "மாலை வணக்கம்", "இனிய இரவு")
            TELUGU   -> listOf("శుభోదయం", "శుభ మధ్యాహ్నం", "శుభ సాయంత్రం", "శుభ రాత్రి")
            BENGALI  -> listOf("সুপ্রভাত", "শুভ অপরাহ্ন", "শুভ সন্ধ্যা", "শুভ রাত্রি")
            MARATHI  -> listOf("सुप्रभात", "शुभ दुपार", "शुभ संध्याकाळ", "शुभ रात्री")
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
        ENGLISH  -> "Reply in English. If the user wrote earlier in another language, do not switch unless they switch now."
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
