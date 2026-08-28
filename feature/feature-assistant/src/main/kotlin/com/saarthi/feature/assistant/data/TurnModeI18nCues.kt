package com.saarthi.feature.assistant.data

/**
 * Phase 5.4 — native doc-scope and opt-out cues for all UI languages.
 * Keeps [QueryRouting] turn-mode detection aligned with how users phrase
 * document intent outside English and Devanagari-only paths.
 */

internal fun queryContainsCuePhrase(query: String, phrases: List<String>): Boolean {
    if (query.isBlank()) return false
    val lower = query.lowercase()
    return phrases.any { phrase ->
        if (phrase.any { it.code >= 0x0080 }) {
            query.contains(phrase)
        } else {
            lower.contains(phrase)
        }
    }
}

/** User explicitly wants to ignore session documents for this turn. */
internal val I18N_DOCUMENT_OPT_OUT_PHRASES = listOf(
  // English
  "don't consider",
  "do not consider",
  "dont consider",
  "ignore the document",
  "ignore the file",
  "ignore this document",
  "ignore this file",
  "without the document",
  "without using the document",
  "not from the document",
  "not from the file",
  "don't use the document",
  "do not use the document",
  "don't use the file",
  "do not use the file",
  // Hindi (Devanagari)
  "दस्तावेज़ मत",
  "दस्तावेज मत",
  "फ़ाइल मत",
  "फाइल मत",
  "दस्तावेज़ नहीं",
  "दस्तावेज नहीं",
  "फ़ाइल नहीं",
  "फाइल नहीं",
  // Marathi
  "दस्तऐवज नको",
  "दस्तावेज नको",
  "फाइल नको",
  "कागदपत्र नको",
  "दस्तऐवज विसर",
  // Tamil
  "ஆவணம் வேண்டாம்",
  "கோப்பு வேண்டாம்",
  "ஆவணத்தை பயன்படுத்தாதே",
  "கோப்பை பயன்படுத்தாதே",
  // Telugu
  "పత్రం వద్దు",
  "ఫైల్ వద్దు",
  "పత్రాన్ని వద్దు",
  "ఫైల్ ను వద్దు",
  // Bengali
  "দস্তাবেজ না",
  "নথি না",
  "ফাইল না",
  "দস্তাবেজ বাদ",
  "নথি বাদ",
  // Kannada
  "ದಾಖಲೆ ಬಿಟ್ಟು",
  "ಫೈಲ್ ಬಿಟ್ಟು",
  "ದಾಖಲೆಯನ್ನು ಬಿಟ್ಟು",
  // Gujarati
  "દસ્તાવેજ નહીં",
  "ફાઇલ નહીં",
  "દસ્તાવેજ ન કરો",
  // Punjabi
  "ਦਸਤਾਵੇਜ਼ ਨਹੀਂ",
  "ਫਾਈਲ ਨਹੀਂ",
  "ਦਸਤਾਵੇਜ਼ ਨਾ ਵਰਤੋ",
  // Odia
  "ଦଲିଲ ନାହିଁ",
  "ଫାଇଲ ନାହିଁ",
  "ଦଲିଲ ଛାଡ଼ି",
  // Romanized Hinglish
  "document mat",
  "dastavez mat",
  "dastavaz mat",
  "file mat",
)

/** Phrases that anchor retrieval to attached / indexed document content. */
internal val I18N_DOCUMENT_SCOPE_PHRASES = listOf(
  // English
  "from the document",
  "from the file",
  "from this document",
  "from this file",
  "from the pdf",
  "in this document",
  "in the document",
  "in this file",
  "in the attached",
  "in this pdf",
  "what does it say",
  "what does the document say",
  "according to the file",
  "according to the document",
  "mentioned in the attached",
  "mentioned in the document",
  "mentioned in the file",
  "attached document",
  "this act",
  "the act says",
  "in the act",
  // Romanized / Hinglish
  "document se",
  "file se",
  "dastavaz",
  "dastavez",
  "dastavēj",
  "dastāvaj",
  // Hindi / Marathi (Devanagari)
  "इस दस्तावेज",
  "इस फाइल",
  "इस फ़ाइल",
  "दस्तावेज में",
  "फाइल में",
  "फ़ाइल में",
  "या दस्तावेजात",
  "या फाइलमध्ये",
  "दस्तावेजातील",
  "या कागदपत्रात",
  // Tamil
  "இந்த ஆவணத்தில்",
  "இந்த கோப்பில்",
  "ஆவணத்திலிருந்து",
  "கோப்பிலிருந்து",
  // Telugu
  "ఈ పత్రంలో",
  "ఈ ఫైల్‌లో",
  "పత్రం నుండి",
  "ఫైల్ నుండి",
  // Bengali
  "এই নথিতে",
  "এই ফাইলে",
  "নথি থেকে",
  "ফাইল থেকে",
  // Kannada
  "ಈ ದಾಖಲೆಯಲ್ಲಿ",
  "ಈ ಫೈಲ್‌ನಲ್ಲಿ",
  "ದಾಖಲೆಯಿಂದ",
  "ಫೈಲ್‌ನಿಂದ",
  // Gujarati
  "આ દસ્તાવેજમાં",
  "આ ફાઇલમાં",
  "દસ્તાવેજમાંથી",
  "ફાઇલમાંથી",
  // Punjabi
  "ਇਸ ਦਸਤਾਵੇਜ਼ ਵਿੱਚ",
  "ਇਸ ਫਾਈਲ ਵਿੱਚ",
  "ਦਸਤਾਵੇਜ਼ ਤੋਂ",
  "ਫਾਈਲ ਤੋਂ",
  // Odia
  "ଏହି ଦଲିଲରେ",
  "ଏହି ଫାଇଲରେ",
  "ଦଲିଲରୁ",
  "ଫାଇଲରୁ",
)

/** WH / ask tokens for topical indexed-session turns (non-English UI scripts). */
internal val I18N_TOPICAL_QUESTION_LEADS = setOf(
  // Hindi (already partially in QueryRouting — kept for completeness)
  "क्या", "कैसे", "क्यों", "कब", "कहाँ", "कहां", "कौन", "बताओ", "बताएं",
  // Marathi
  "काय", "कसे", "का", "कधी", "कुठे", "सांगा",
  // Tamil
  "என்ன", "எப்போது", "எங்கே", "ஏன்", "எவ்வாறு", "சொல்லு",
  // Telugu
  "ఏమి", "ఎప్పుడు", "ఎక్కడ", "ఎలా", "ఎందుకు", "చెప్పు",
  // Bengali
  "কী", "কখন", "কোথায়", "কেন", "কিভাবে", "বলো",
  // Kannada
  "ಏನು", "ಹೇಗೆ", "ಯಾವಾಗ", "ಎಲ್ಲಿ", "ಏಕೆ", "ಹೇಳು",
  // Gujarati
  "શું", "કેમ", "ક્યાં", "ક્યારે", "કહો",
  // Punjabi
  "ਕੀ", "ਕਿਵੇਂ", "ਕਦੋਂ", "ਕਿੱਥੇ", "ਕਿਉਂ", "ਦੱਸੋ",
  // Odia
  "କଣ", "କିପରି", "କେବେ", "କେଉଁଠି", "କାହିଁକି", "କହ",
)

internal fun hasI18nDocumentOptOutCue(query: String): Boolean =
  queryContainsCuePhrase(query, I18N_DOCUMENT_OPT_OUT_PHRASES)

internal fun hasI18nDocumentScopeCue(query: String): Boolean =
  queryContainsCuePhrase(query, I18N_DOCUMENT_SCOPE_PHRASES)
