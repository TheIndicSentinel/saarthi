package com.saarthi.feature.assistant.data

/**
 * True when the user is asking who/what Saarthi is ("who are you",
 * "introduce yourself", "about Saarthi", or the equivalent in an Indian
 * language / romanized form). Deliberately conservative — short messages
 * only, anchored phrases — so a normal question is never mistaken for an
 * identity ask. Drives the deterministic localized identity reply.
 */
internal fun isIdentityQuestion(message: String): Boolean {
    val m = message.trim().lowercase()
    if (m.length !in 2..80) return false
    val latin = listOf(
        "who are you", "who r u", "what are you", "who is saarthi", "what is saarthi",
        "about saarthi", "introduce yourself", "tell me about yourself", "tell me about you",
        "about you", "your name", "what is your name", "what's your name", "who made you",
        "tum kaun", "tum kon", "aap kaun", "tumhare bare", "tumhare baare", "tumhari jaankari",
        "apne bare", "apne baare", "khud ke bare", "khud ke baare", "tum kya ho", "tum kya hai",
        "tumhara naam", "tera naam", "aapka naam", "apna parichay", "kaun ho", "tumhi kon",
        "tujhya baddal", "tumchya baddal", "tumcha baddal", "tujhe naav", "tumche naav",
        // Telugu
        "nuvvu evaru", "nuvvu evaroo", "meeru evaru", "nee peru", "mee peru", "nee gurinchi",
        // Tamil
        "nee yaar", "neenga yaaru", "neenga yaar", "un peyar", "unga peyar", "unnai pathi",
        // Bengali
        "tumi ke", "apni ke", "tomar naam", "apnar naam", "tomar somporke",
        // Kannada
        "neenu yaaru", "neevu yaaru", "ninna hesaru", "nimma hesaru", "nimma bagge",
        // Gujarati
        "tame kon", "tamaru naam", "taru naam", "tara vishe", "tamara vishe",
        // Punjabi
        "tusi kaun", "tuhada naam", "tera naa", "tuhade bare",
        // Odia
        "tume kie", "apan kie", "tuma naam", "nija bisaya",
    )
    if (latin.any { m.contains(it) }) return true
    val native = listOf(
        // Hindi
        "तुम कौन", "आप कौन", "तुम क्या हो", "अपने बारे", "तुम्हारे बारे", "सारथी कौन", "सारथी क्या",
        "तुम्हारा नाम", "आपका नाम", "तेरा नाम", "नाम क्या",
        // Marathi
        "तू कोण", "तुम्ही कोण", "तुमच्याबद्दल", "तुझ्याबद्दल", "स्वतःबद्दल",
        "तुझे नाव", "तुमचे नाव", "तुझं नाव", "तुमचं नाव", "नाव काय",
        // Telugu
        "నువ్వు ఎవరు", "మీరు ఎవరు", "నీ గురించి", "మీ గురించి", "నీ పేరు", "మీ పేరు",
        // Tamil
        "நீ யார்", "நீங்கள் யார்", "உங்களை பற்றி", "உன்னை பற்றி", "உன் பெயர்", "உங்கள் பெயர்", "உங்க பெயர்",
        // Bengali
        "তুমি কে", "আপনি কে", "তোমার সম্পর্কে", "নিজের সম্পর্কে", "তোমার নাম", "আপনার নাম",
        // Kannada
        "ನೀನು ಯಾರು", "ನೀವು ಯಾರು", "ನಿಮ್ಮ ಬಗ್ಗೆ", "ನಿನ್ನ ಹೆಸರು", "ನಿಮ್ಮ ಹೆಸರು",
        // Gujarati
        "તમે કોણ", "તું કોણ", "તમારા વિશે", "તારું નામ", "તમારું નામ",
        // Punjabi
        "ਤੁਸੀਂ ਕੌਣ", "ਤੂੰ ਕੌਣ", "ਆਪਣੇ ਬਾਰੇ", "ਤੇਰਾ ਨਾਂ", "ਤੁਹਾਡਾ ਨਾਂ", "ਤੇਰਾ ਨਾਮ", "ਤੁਹਾਡਾ ਨਾਮ",
        // Odia
        "ଆପଣ କିଏ", "ତୁମେ କିଏ", "ନିଜ ବିଷୟରେ", "ତୁମ ନାମ", "ଆପଣଙ୍କ ନାମ",
    )
    return native.any { message.contains(it) }
}
