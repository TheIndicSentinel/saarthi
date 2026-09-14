package com.saarthi.feature.assistant.data

/**
 * Lightweight pre-LLM gate for poison / self-harm / harm-to-others mentions.
 * Returns a fixed, vetted reply so small models cannot under-react or give
 * harmful advice. Pure string matching — no extra I/O or model load.
 */
internal object UrgentSafetyGate {

    fun replyFor(message: String): String? {
        val m = message.trim()
        if (m.length !in 4..500) return null
        val lower = m.lowercase()
        if (!matchesUrgentPattern(lower, m)) return null
        return SAFETY_REPLY
    }

    private fun matchesUrgentPattern(lower: String, raw: String): Boolean {
        if (SELF_HARM.any { lower.contains(it) }) return true
        if (HARM_OTHERS.any { lower.contains(it) }) return true
        val hasPoison = POISON_TERMS.any { lower.contains(it) || raw.contains(it, ignoreCase = true) }
        if (!hasPoison) return false
        return POISON_INTENT.any { intent ->
            Regex("\\b${Regex.escape(intent)}\\b").containsMatchIn(lower)
        }
    }

    private val POISON_TERMS = listOf(
        "poison", "poisonous", "toxic", "pesticide", "insecticide", "rodenticide",
        "cyanide", "bleach", "rat poison",
        "जहर", "विष", "ज़हर", "विषाक्त",
        "விஷ", "విష", "ವಿಷ", "വിഷ", "বিষ",
    )

    private val POISON_INTENT = listOf(
        "drink", "drank", "eat", "ate", "swallow", "swallowed", "take", "took",
        "consume", "consumed", "give", "gave", "feed", "fed", "inject", "injected",
        "kill myself", "kill myself", "suicide", "end my life", "want to die",
        "पी", "पिया", "खा", "खाय", "निगल", "ले", "दे", "खुदकुशी",
        "தண்ணீர் அருந்த", "குடி", "முடித்த", // partial roman/native hooks
    )

    private val SELF_HARM = listOf(
        "suicide", "kill myself", "end my life", "self-harm", "self harm",
        "cut myself", "want to die", "don't want to live", "do not want to live",
        "खुदकुशी", "आत्महत्या", "मरना चाह", "जीना नही",
    )

    private val HARM_OTHERS = listOf(
        "poison someone", "poison my", "poison him", "poison her",
        "kill him with", "kill her with",
    )

    private val SAFETY_REPLY = """
**Do not consume or give any poison or toxic substance to anyone.**

If the substance is nearby, move away from it and keep others away.

**Have you already taken it, or are you about to?** If yes, or if someone may be in danger, **call local emergency services or a poison-control helpline immediately** (India: national emergency **112**; poison information centres vary by state — use the nearest hospital emergency).

Stay with a trusted person if you can; do not stay alone with a toxic substance nearby.

I cannot give medical treatment steps here. **Do not induce vomiting or use home remedies** unless emergency staff tell you to — get professional help right away.
""".trimIndent()
}
