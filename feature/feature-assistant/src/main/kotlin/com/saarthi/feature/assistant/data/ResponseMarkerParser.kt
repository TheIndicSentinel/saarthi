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
    // from the rendered chat bubble.
    //
    // The brackets are OPTIONAL. We saw production leaks like:
    //   "saarthi_reminder text=\"India has...\" delay_minutes=\"60\""  (no [ at all)
    //   "[SAARTHI_REMINDER text=\"...\" delay_minutes=\"60\""           (no closing ])
    //   "SAARTHI_REMINDER text=\"...\"]"                                 (no opening [)
    // The earlier strict-bracket pattern matched none of these and the
    // fragment landed in the visible bubble. Both brackets are now optional;
    // the match terminates at the first `]` OR end-of-line so it cannot
    // swallow unrelated text further down the response.
    private val ANY_SAARTHI_MARKER_REGEX = Regex(
        """\[?\s*SAARTHI_(?:REMINDER|MEMORY)\b[^\]\n]*\]?""",
        RegexOption.IGNORE_CASE,
    )

    // Orphan attribute lines. When the marker spans two lines and the strict
    // regexes don't match (or when the model emits only a fragment), a stray
    // line like:
    //     delay_minutes="60"]
    //     time="18:00"
    //     text="Take medicine" delay_minutes="30"
    // can survive the bracket-optional pass above. A line consisting ONLY of
    // one of our known marker attributes (with optional trailing `]`) is a
    // leak by definition — strip the whole line. Multiline mode + anchored
    // ^...$ keeps this from chewing on prose that happens to contain `key=`.
    private val ORPHAN_MARKER_ATTRIBUTE_LINE = Regex(
        """(?m)^\s*(?:text|key|value|time|delay_minutes)\s*=\s*"[^"\n]*"(?:\s+(?:text|key|value|time|delay_minutes)\s*=\s*"[^"\n]*")*\s*\]?\s*$""",
    )

    // YAML / colon-form marker leak. Small models (esp. in non-English) ignore
    // the bracket syntax and instead dump a block like:
    //     marker:
    //     text: "memory_updated"
    //     delay_minutes: 0
    //     time: "11:05 2026"
    //     key: "help_offered"
    //     value: "Arjun is vegetarian"
    // None of the equals-based patterns catch the COLON form, so it leaked into
    // the bubble. Strip a standalone `marker:` label line and any line that is
    // solely one of our marker fields in `field: value` form (quoted or not).
    private val MARKER_LABEL_LINE = Regex("""(?im)^\s*marker\s*:\s*$""")
    private val ORPHAN_MARKER_COLON_LINE = Regex(
        """(?im)^\s*(?:text|key|value|time|delay_minutes)\s*:\s*.*$""",
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
            // Broader English: "(large) language model" / "AI model" / "AI" with
            // no "large" qualifier — the model leaks these too.
            Regex("""(?i)\bI\s+am\s+an?\s+(?:AI\s+)?language\s+model\b""")     to "I am Saarthi",
            Regex("""(?i)\bI'm\s+an?\s+(?:AI\s+)?language\s+model\b""")        to "I'm Saarthi",
            Regex("""(?i)\bI\s+am\s+an?\s+AI\s+model\b""")                    to "I am Saarthi",
            Regex("""(?i)\bI'm\s+an?\s+AI\s+model\b""")                       to "I'm Saarthi",
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

        // ── Localized identity leaks (Devanagari: Hindi / Marathi) ───────────
        // The model says e.g. "मैं एक बड़ा भाषा मॉडल हूँ, जिसे गूगल डीपमाइंड
        // द्वारा विकसित किया गया है" even in non-English sessions. The English
        // patterns above can't catch these. Neutralise the LLM noun-phrase to
        // the brand name and strip any "developed/trained by Google/DeepMind"
        // provenance clause (relative clause bounded by the Devanagari danda
        // so it never eats the next sentence).
        out = Regex("""(एक\s+)?(बड़ा\s+|बड़ी\s+|मोठा\s+|मोठे\s+|विशाल\s+|एआई\s+|AI\s+)*भाषा\s+मॉ(?:डल|डेल)""")
            .replace(out, "सारथी")
        out = Regex("""(एक\s+)?(एआई|AI)\s+मॉ(?:डल|डेल)""").replace(out, "सारथी")
        // Provenance relative clause containing Google / DeepMind (Devanagari or
        // Latin brand spelling). e.g. ", जिसे गूगल डीपमाइंड द्वारा विकसित किया गया है".
        out = Regex(""",?\s*(?:जिसे|जिसको|जो)\s+[^।]*?(?:गूगल|डीपमाइंड|Google|DeepMind)[^।]*""")
            .replace(out, "")
        // Bare provenance without a relative pronoun, ANY Indian script:
        // "<brand> … <developed/trained verb> …". Bound the clause by a sentence
        // terminator (danda ।, period, or newline) so it never crosses into the
        // next sentence and a legitimate brand mention without a provenance verb
        // (e.g. "search on గూగుల్") is never stripped. Brands are matched in
        // Latin AND each native script; verbs cover the common "made/developed/
        // trained/created" forms across languages.
        val brand = "गूगल|डीपमाइंड|గూగుల్|கூகிள்|গুগল|ಗೂಗಲ್|ગૂગલ|ਗੂਗਲ|ଗୁଗଲ|Google|DeepMind"
        val devVerb = "विकसित|प्रशिक्षित|निर्मित|तयार|बनवले|बनाया|अभिवृद्ध|" +
            "అభివృద్ధి|తయారు|రూపొందించ|" + "உருவாக்க|பயிற்சி|" + "তৈরি|প্রশিক্ষিত|" +
            "ತಯಾರಿಸ|ಅಭಿವೃದ್ಧಿ|" + "બનાવ|વિકસાવ|" + "ਬਣਾ|ਵਿਕਸਿਤ|" + "ତିଆରି|ବିକଶିତ|" +
            "developed|trained|created|built|made"
        out = Regex("""[,–-]?\s*(?:$brand)[^।.\n]*?(?:$devVerb)[^।.\n]*""").replace(out, "")

        // ── Other Indian scripts (Telugu/Tamil/Bengali/Kannada/Gujarati/Punjabi/
        // Odia) ──────────────────────────────────────────────────────────────
        // Same neutralisation: replace the localized "(big) language/AI model"
        // noun-phrase with the brand name in that script. Best-effort coverage
        // of the common spellings the model emits; the deterministic identity
        // grounding remains the primary defence. A spelling that doesn't match
        // is simply a no-op (never a degradation).
        val localizedLlmByScript = listOf(
            Regex("""(పెద్ద\s+)?(?:భాషా|ఏఐ|AI)\s+(?:మోడల్|మోడెల్|నమూనా)""") to "సారథి",       // Telugu
            Regex("""(பெரிய\s+)?(?:மொழி|ஏஐ|AI)\s+(?:மாதிரி|மாடல்|மாட்டல்)""") to "சாரதி",      // Tamil
            Regex("""(বড়\s+)?(?:ভাষা|এআই|AI)\s+মডেল""") to "সারথি",                          // Bengali
            Regex("""(ದೊಡ್ಡ\s+)?(?:ಭಾಷಾ|ಎಐ|AI)\s+(?:ಮಾದರಿ|ಮಾಡೆಲ್|ಮಾಡಲ್)""") to "ಸಾರಥಿ",     // Kannada
            Regex("""(મોટું\s+)?(?:ભાષા|એઆઈ|AI)\s+(?:મૉડલ|મોડેલ)""") to "સારથી",              // Gujarati
            Regex("""(ਵੱਡਾ\s+)?(?:ਭਾਸ਼ਾ|ਏਆਈ|AI)\s+ਮਾਡਲ""") to "ਸਾਰਥੀ",                        // Punjabi
            Regex("""(ବଡ଼\s+)?(?:ଭାଷା|ଏଆଇ|AI)\s+(?:ମଡେଲ|ମୋଡେଲ)""") to "ସାରଥୀ",                 // Odia
        )
        for ((pattern, replacement) in localizedLlmByScript) {
            out = pattern.replace(out, replacement)
        }

        // Tidy up any double spaces / dangling punctuation we just created
        // (including a stray comma / danda left after a strip).
        out = out.replace(Regex("""\s{2,}"""), " ")
                 .replace(Regex("""\s+([,.!?])"""), "$1")
                 .replace(Regex("""[\s,]+।"""), "।")
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
            // Compact (Gemma 1B) sometimes echoes the "Saarthi:" transcript
            // anchor as the first token of its reply. STANDARD/LARGE never see
            // that anchor in their prompts, so this regex is a no-op for them.
            .replace(Regex("^\\s*\\**\\s*[Ss]aarthi\\**\\s*:\\s*"), "")
            .replace(MEMORY_REGEX, "")
            .replace(REMINDER_ABS_REGEX, "")
            .replace(REMINDER_REL_REGEX, "")
            // Defensive net — any SAARTHI_* block of any shape must NOT
            // reach the rendered bubble. Catches malformed markers the strict
            // regexes above miss (empty values, wrong spacing, partial fields,
            // curly quotes, missing brackets).
            .replace(ANY_SAARTHI_MARKER_REGEX, "")
            // Second defensive pass — wipe lines that consist solely of one
            // or more marker attribute fragments. Covers the multi-line-leak
            // case where the marker keyword landed on a prior line.
            .replace(ORPHAN_MARKER_ATTRIBUTE_LINE, "")
            // Third pass — the YAML / colon-form leak ("marker:" + "key: ...").
            .replace(MARKER_LABEL_LINE, "")
            .replace(ORPHAN_MARKER_COLON_LINE, "")
        for (token in GEMMA_SPECIAL_TOKENS) {
            out = out.replace(token, "")
        }
        return out.trim()
    }
}
