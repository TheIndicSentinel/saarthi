package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage

/**
 * Lightweight pre-LLM gate for poison / self-harm / harm-to-others mentions.
 * Returns a fixed, localized reply so small models cannot under-react or give
 * harmful advice. Pure string matching — no extra I/O or model load.
 */
internal object UrgentSafetyGate {

    fun replyFor(message: String, language: SupportedLanguage): String? {
        val m = message.trim()
        if (m.length !in 4..500) return null
        val lower = m.lowercase()
        if (!matchesUrgentPattern(lower, m)) return null
        return language.urgentSafetyReply
    }

    private fun matchesUrgentPattern(lower: String, raw: String): Boolean {
        if (SELF_HARM.any { phrase -> phraseMatches(lower, raw, phrase) }) return true
        if (HARM_OTHERS.any { phrase -> phraseMatches(lower, raw, phrase) }) return true
        val hasPoison = POISON_TERMS.any { lower.contains(it) || raw.contains(it, ignoreCase = true) }
        if (!hasPoison) return false
        return POISON_INTENT.any { intent -> phraseMatches(lower, raw, intent) }
    }

    private fun phraseMatches(lower: String, raw: String, phrase: String): Boolean {
        if (phrase.any { it.code > 127 }) {
            return raw.contains(phrase) || lower.contains(phrase)
        }
        return Regex("\\b${Regex.escape(phrase)}\\b").containsMatchIn(lower)
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
        "kill myself", "suicide", "end my life",
        "पी", "पिया", "खा", "खाय", "निगल",
        "खुदकुशी", "आत्महत्या",
    )

    private val SELF_HARM = listOf(
        "suicide", "kill myself", "end my life", "self-harm", "self harm",
        "cut myself", "want to die", "don't want to live", "do not want to live",
        "hurt myself", "harm myself",
        "खुदकुशी", "आत्महत्या", "मरना चाह",
    )

    private val HARM_OTHERS = listOf(
        "poison someone", "poison my", "poison him", "poison her",
        "kill him with", "kill her with",
    )
}
