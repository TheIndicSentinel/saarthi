package com.saarthi.feature.assistant.data

/**
 * True when the user explicitly asks Saarthi to remember / save / note a fact.
 * Marker-based and implicit memory persistence run only in this case — casual
 * self-disclosure ("my name is Arjun") stays in chat context only.
 */
internal fun userRequestedMemorySave(message: String): Boolean {
    val m = message.trim()
    if (m.length !in 4..600) return false
    val lower = m.lowercase()
    val latinPhrases = listOf(
        "remember that", "remember my", "remember this", "please remember",
        "save that", "save my", "save this", "please save",
        "note that", "note my", "note this", "keep in mind", "don't forget",
        "do not forget", "for future", "for later",
        "yaad rakh", "yaad rakho", "yaad rakhna", "yaad rakhiye",
        "save karo", "save kar", "note karo",
        "mera yaad", "mujhe yaad",
    )
    if (latinPhrases.any { lower.contains(it) }) return true
    val nativePhrases = listOf(
        "याद रख", "याद रखो", "याद रखना", "याद रखिए",
        "सेव कर", "सेव करो", "नोट कर",
        "याद ठेव", "लक्षात ठेव",
        "గుర్తు పettu", "గుర్తు ఉంచ", "జ్ఞాపకం",
        "நினைவில் வை", "பதிவு செய",
        "মনে রাখ", "সংরক্ষণ",
        "ನೆನಪಿಟ್ಟು", "ಉಳಿಸ",
        "યાદ રાખ", "સાચવ",
        "ਯਾਦ ਰੱਖ", "ਸੰਭਾਲ",
        "ମନେ ରଖ",
    )
    return nativePhrases.any { m.contains(it) }
}
