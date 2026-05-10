package com.saarthi.feature.assistant.data

/**
 * Parses special action markers embedded by the model at the end of its responses.
 *
 * Supported markers (always appended after the visible response text):
 *
 *   Memory:
 *     [SAARTHI_MEMORY key="user_name" value="Rahul"]
 *
 *   Reminder — absolute time (HH:MM 24h):
 *     [SAARTHI_REMINDER text="Cook dinner" time="18:00"]
 *
 *   Reminder — relative (minutes from now):
 *     [SAARTHI_REMINDER text="Take medicine" delay_minutes="30"]
 */
object ResponseMarkerParser {

    data class ParseResult(
        val cleanText: String,
        val memories: List<MemoryMarker>,
        val reminders: List<ReminderMarker>,
    )

    data class MemoryMarker(val key: String, val value: String)

    /** Either [time] (HH:MM absolute) or [delayMinutes] (relative) will be set — never both. */
    data class ReminderMarker(
        val text: String,
        val time: String? = null,
        val delayMinutes: Int? = null,
    )

    private val MEMORY_REGEX = Regex(
        """\[SAARTHI_MEMORY\s+key="([^"]+)"\s+value="([^"]+)"\]"""
    )
    private val REMINDER_ABS_REGEX = Regex(
        """\[SAARTHI_REMINDER\s+text="([^"]+)"\s+time="([^"]+)"\]"""
    )
    private val REMINDER_REL_REGEX = Regex(
        """\[SAARTHI_REMINDER\s+text="([^"]+)"\s+delay_minutes="(\d+)"\]"""
    )

    fun parse(raw: String): ParseResult {
        val memories = MEMORY_REGEX.findAll(raw)
            .map { MemoryMarker(it.groupValues[1], it.groupValues[2]) }
            .toList()

        val reminders = buildList {
            REMINDER_ABS_REGEX.findAll(raw).forEach {
                add(ReminderMarker(text = it.groupValues[1], time = it.groupValues[2]))
            }
            REMINDER_REL_REGEX.findAll(raw).forEach {
                add(ReminderMarker(text = it.groupValues[1], delayMinutes = it.groupValues[2].toIntOrNull()))
            }
        }

        val cleaned = normalizeFormatting(rewriteIdentity(stripAll(raw)))
        return ParseResult(
            cleanText = cleaned,
            memories = memories,
            reminders = reminders,
        )
    }

    /** Strip complete markers from text shown in the streaming bubble. */
    fun stripForDisplay(raw: String): String =
        normalizeFormatting(rewriteIdentity(stripAll(raw)))

    /**
     * Rewrites identity leaks the model emits despite the system prompt.
     * Gemma 4's training is strong enough that no amount of prompt tuning fully
     * stops it from saying "I am Gemma", "developed by Google DeepMind", "I'm a
     * large language model", etc. — so we do a final substitution pass on the
     * visible text. Matters because:
     *   • The Saarthi brand experience falls apart the second the user reads
     *     "I am Gemma".
     *   • Production assistants (Pi, Replika, ChatGPT custom GPTs) all run a
     *     similar guardrail layer for the same reason.
     *
     * Rules are conservative — only literal phrases that have no legitimate
     * non-identity use in this app. We do NOT strip the words "Google", "Gemma",
     * etc. on their own (the user might genuinely ask about Google), only when
     * they appear in a self-introduction context. We DO neutralise full
     * "developed by …" / "made by …" phrases since those only ever appear in
     * leaks here.
     */
    fun rewriteIdentity(text: String): String {
        if (text.isEmpty()) return text
        var out = text

        // Self-identification phrases (case-insensitive). Replace with the
        // Saarthi-correct form so the bubble keeps reading naturally instead of
        // showing weird gaps.
        val selfIntroPatterns = listOf(
            // "I am Gemma 4 / Gemma3n / Gemma" — normalised to Saarthi
            Regex("""(?i)\bI\s+am\s+Gemma(?:[\s\-]?\d+(?:[A-Za-z]+)?)?\b""") to "I am Saarthi",
            Regex("""(?i)\bI'm\s+Gemma(?:[\s\-]?\d+(?:[A-Za-z]+)?)?\b""")    to "I'm Saarthi",
            Regex("""(?i)\bMy\s+name\s+is\s+Gemma(?:[\s\-]?\d+\w*)?\b""")     to "My name is Saarthi",
            // "I am a large language model …" — change to "I am Saarthi"
            Regex("""(?i)\bI\s+am\s+a\s+large\s+language\s+model\b""")        to "I am Saarthi",
            Regex("""(?i)\bI'm\s+a\s+large\s+language\s+model\b""")           to "I'm Saarthi",
            Regex("""(?i)\bI\s+am\s+an\s+AI\s+language\s+model\b""")          to "I am Saarthi, your AI assistant",
            Regex("""(?i)\bI'm\s+an\s+AI\s+language\s+model\b""")             to "I'm Saarthi, your AI assistant",
            Regex("""(?i)\bI\s+am\s+an?\s+(?:large\s+)?LLM\b""")              to "I am Saarthi",
            Regex("""(?i)\bI'm\s+an?\s+(?:large\s+)?LLM\b""")                 to "I'm Saarthi",
        )
        for ((pattern, replacement) in selfIntroPatterns) {
            out = pattern.replace(out, replacement)
        }

        // Provenance phrases — strip entirely. These only ever appear in leaks.
        val provenancePatterns = listOf(
            Regex("""(?i),?\s*(?:developed|made|created|trained|built)\s+by\s+Google(?:\s+DeepMind)?\.?"""),
            Regex("""(?i),?\s*(?:developed|made|created|trained|built)\s+by\s+DeepMind\.?"""),
            Regex("""(?i),?\s*(?:a|the)\s+(?:large\s+)?language\s+model\s+(?:made|created|developed|trained|built)\s+by\s+\w+(?:\s+\w+)?\.?"""),
        )
        for (pattern in provenancePatterns) {
            out = pattern.replace(out, "")
        }

        // Tidy up any double spaces / dangling punctuation we just created.
        out = out.replace(Regex("""\s{2,}"""), " ")
                 .replace(Regex("""\s+([,.!?])"""), "$1")
        return out
    }

    private fun normalizeFormatting(text: String): String = text
        .lines()
        .joinToString("\n") { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("* ") -> line.replaceFirst("* ", "- ")
                trimmed.startsWith("• ") -> line.replaceFirst("• ", "- ")
                else -> line
            }
        }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun stripAll(text: String): String = text
        .replace(MEMORY_REGEX, "")
        .replace(REMINDER_ABS_REGEX, "")
        .replace(REMINDER_REL_REGEX, "")
        .replace("<end_of_turn>", "")
        .replace("<eos>", "")
        .replace("<start_of_turn>", "")
        .trim()
}
