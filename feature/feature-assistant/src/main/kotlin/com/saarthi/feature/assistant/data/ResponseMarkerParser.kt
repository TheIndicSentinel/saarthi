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

    // ── Parse regexes (tolerant) ───────────────────────────────────────────
    //
    // Earlier these required NO whitespace around `=` and a non-empty value
    // (`text="([^"]+)"`). In English / Hindi the model emits the syntax
    // verbatim and that worked, but in Telugu, Bengali, Tamil etc. it tends
    // to write the spaces around `=` differently (`text = "x"`) and
    // sometimes copies the literal placeholder text from the prompt
    // (`text = ""`, `delay_minutes = "N"`). The strict regex matched
    // neither case → the markers were not stripped → they leaked into
    // the user-visible bubble.
    //
    // Now the regexes:
    //   • allow whitespace around `=`,
    //   • allow empty quoted values ("([^"]*)" — zero-or-more),
    //   • allow either ASCII straight quotes (") or fancy / curly quotes
    //     (“ ”) which Indic-language keyboards routinely produce.
    //
    // The schedule-side filter in ChatRepositoryImpl + the
    // [isPlaceholderValue] helper below reject empty / template values
    // before they hit AlarmManager, so a tolerant parser cannot cause
    // spurious notifications.
    private const val Q = "[\"“”]"

    private val MEMORY_REGEX = Regex(
        """\[SAARTHI_MEMORY\s+key\s*=\s*$Q([^"“”]*)$Q\s+value\s*=\s*$Q([^"“”]*)$Q\s*\]"""
    )
    private val REMINDER_ABS_REGEX = Regex(
        """\[SAARTHI_REMINDER\s+text\s*=\s*$Q([^"“”]*)$Q\s+time\s*=\s*$Q([^"“”]*)$Q\s*\]"""
    )
    private val REMINDER_REL_REGEX = Regex(
        """\[SAARTHI_REMINDER\s+text\s*=\s*$Q([^"“”]*)$Q\s+delay_minutes\s*=\s*$Q(\d+)$Q\s*\]"""
    )

    // Defensive net for everything the strict regexes might miss. ANY block
    // that looks like a SAARTHI marker — well-formed or not — must be wiped
    // from the rendered chat bubble. Without this, malformed markers from
    // weaker-instruction-following languages (Telugu has been the reproducer)
    // show up as raw "[SAARTHI_REMINDER text = ""...]" text in the chat.
    //
    // Greedy-bounded: closes on the first `]` so it can't swallow normal
    // text that uses brackets later in the response.
    private val ANY_SAARTHI_MARKER_REGEX = Regex(
        """\[\s*SAARTHI_(?:REMINDER|MEMORY)\b[^\]]*\]""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Returns true when the value is empty or a literal template placeholder
     * the model copied from the prompt instead of filling in.
     * These must never reach AlarmManager / MemoryRepository.
     */
    private fun isPlaceholderValue(v: String): Boolean {
        val t = v.trim()
        if (t.isEmpty()) return true
        if (t == "...") return true
        if (t == "N") return true
        if (t == "HH:MM") return true
        if (t == "short_key" || t == "value") return true
        if (t == "what to remind") return true
        return false
    }

    fun parse(raw: String): ParseResult {
        val memories = MEMORY_REGEX.findAll(raw)
            .map { MemoryMarker(it.groupValues[1].trim(), it.groupValues[2].trim()) }
            .filter { !isPlaceholderValue(it.key) && !isPlaceholderValue(it.value) }
            .toList()

        val reminders = buildList {
            REMINDER_ABS_REGEX.findAll(raw).forEach {
                val text = it.groupValues[1].trim()
                val time = it.groupValues[2].trim()
                if (!isPlaceholderValue(text) && !isPlaceholderValue(time)) {
                    add(ReminderMarker(text = text, time = time))
                }
            }
            REMINDER_REL_REGEX.findAll(raw).forEach {
                val text = it.groupValues[1].trim()
                val mins = it.groupValues[2].toIntOrNull()
                if (mins != null && mins > 0 && !isPlaceholderValue(text)) {
                    add(ReminderMarker(text = text, delayMinutes = mins))
                }
            }
        }

        val cleaned = normalizeFormatting(rewriteIdentity(stripAll(raw)))
        return ParseResult(
            cleanText = cleaned,
            memories = memories,
            reminders = reminders,
        )
    }

    /**
     * Strip complete markers from text shown in the streaming bubble.
     *
     * If [streaming] is true, the tail of the text is held back until any
     * in-progress identity leak ("I am Ge…", "I am a large lang…") completes
     * — otherwise the user sees the leak flicker for one token before
     * `rewriteIdentity` swaps it for "I am Saarthi". The withheld characters
     * land in the very next [stripForDisplay] call (or on completion), so no
     * content is lost.
     */
    fun stripForDisplay(raw: String, streaming: Boolean = true): String {
        val cleaned = normalizeFormatting(rewriteIdentity(stripAll(raw)))
        if (!streaming) return cleaned
        return holdBackPartialLeaks(cleaned)
    }

    /**
     * Tokens of "I am Gemma", "I'm a large language model" etc. arrive a few
     * chars at a time; the regex only matches the full phrase. Until then the
     * partial prefix is technically clean text and would render in the bubble
     * for one frame, producing a visible flicker. Detect such prefixes at the
     * tail and trim them off — they'll appear on the *next* token tick once
     * the rewrite has something to match.
     */
    private fun holdBackPartialLeaks(text: String): String {
        if (text.isEmpty()) return text
        // Anchor at the very end of the visible text. Each prefix here is the
        // *start* of a phrase that rewriteIdentity will catch in full — but
        // only after a few more chars arrive.
        val tailPrefixes = listOf(
            "I am Gemma", "I'm Gemma", "My name is Gemma",
            "I am a large language model", "I'm a large language model",
            "I am an AI language model", "I'm an AI language model",
            "I am an LLM", "I'm an LLM",
            "I am a Google", "I'm a Google",
            "developed by Google", "made by Google", "created by Google", "trained by Google", "built by Google",
            "developed by DeepMind", "made by DeepMind",
        )
        // Find the longest tail of `text` that matches a *strict prefix* of any
        // pattern (length < pattern.length). Trim that tail.
        var holdFrom = text.length
        for (pattern in tailPrefixes) {
            val maxOverlap = minOf(text.length, pattern.length - 1)
            // Walk down so we find the longest partial overlap with this pattern.
            for (k in maxOverlap downTo 1) {
                if (text.regionMatches(text.length - k, pattern, 0, k, ignoreCase = true)) {
                    holdFrom = minOf(holdFrom, text.length - k)
                    break  // longest overlap for this pattern found
                }
            }
        }
        return if (holdFrom < text.length) text.substring(0, holdFrom) else text
    }

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
        // Note: we deliberately do NOT consume a trailing "." — the period is
        // the sentence terminator and we want to keep it so the cleaned text
        // reads as a normal sentence ("I am Saarthi. How can I help?").
        val provenancePatterns = listOf(
            Regex("""(?i),?\s*(?:developed|made|created|trained|built)\s+by\s+Google(?:\s+DeepMind)?"""),
            Regex("""(?i),?\s*(?:developed|made|created|trained|built)\s+by\s+DeepMind"""),
            Regex("""(?i),?\s*(?:a|the)\s+(?:large\s+)?language\s+model\s+(?:made|created|developed|trained|built)\s+by\s+\w+(?:\s+\w+)?"""),
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

    // Gemma special / control tokens that occasionally leak into output —
    // especially in non-English sessions (Telugu, Bengali, Tamil) where the
    // model's instruction-following confidence is lower. None of these should
    // ever be visible to the user.
    private val GEMMA_SPECIAL_TOKENS = listOf(
        "<start_of_turn>", "<end_of_turn>",
        "<bos>", "<eos>",
        "<pad>", "<unk>",
        "<image_soft_token>", "<audio_soft_token>",
        // Some Gemma fine-tunes emit these zero-padded internal markers.
        "<unused0>", "<unused1>", "<unused2>", "<unused3>",
    )

    private fun stripAll(text: String): String {
        var out = text
            .replace(MEMORY_REGEX, "")
            .replace(REMINDER_ABS_REGEX, "")
            .replace(REMINDER_REL_REGEX, "")
            // Defensive net — any [SAARTHI_*] block of any shape must NOT
            // reach the rendered bubble. Catches malformed markers the strict
            // regexes above miss (empty values, wrong spacing, partial fields,
            // curly quotes) — the actual user-visible bug in Telugu sessions.
            .replace(ANY_SAARTHI_MARKER_REGEX, "")
        for (token in GEMMA_SPECIAL_TOKENS) {
            out = out.replace(token, "")
        }
        return out.trim()
    }
}
