package com.saarthi.feature.assistant.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conservative, multilingual implicit-fact extraction from a user's own
 * chat message, plus the name-value shape guard that protects stored name
 * facts from garbled/sentence-shaped model output.
 *
 * Split out of ChatRepositoryImpl (2026-07-19): this ~450-line regex bank
 * is the single highest-churn area in that file's history (Devanagari
 * combining-mark name-capture bugs, a zodiac fact mislabeled as a name,
 * multiple rounds of multilingual coverage gaps) — a change to prompt
 * budgeting or RAG assembly had no business ever being able to break name
 * capture, but before this split they shared one 2000+-line class with no
 * test seam between them. This class has zero injected dependencies (pure
 * String in, String/List out), so it's directly unit-testable in isolation.
 */
@Singleton
class ImplicitFactExtractor @Inject constructor() {

    /**
     * True when [v] is shaped like an actual person name: 1–3 letter tokens
     * (apostrophe/hyphen ok), no digits, no sentence punctuation, and no
     * pronoun/copula/label filler in any supported script. Model markers
     * sometimes wrap the name in a sentence ("उपयोगकर्ता का नाम अर्जुन है")
     * or glue on a pronoun ("Arjun.mae") — those must never enter a name key,
     * because the home greeting renders the stored value.
     *
     * Also rejects known ZODIAC_SIGNS: a small on-device model sometimes
     * mislabels a zodiac fact as the "name" key when a message states
     * several facts at once ("I am vegetarian and Sagittarius") — field log
     * showed write key=name value="Sagittarius" clobbering the real stored
     * name "अर्जुन" because "Sagittarius" is single-token, punctuation-free,
     * and longer, so the completeness guard let it win. Fillers catch
     * sentence-shaped junk; this catches a real word in the wrong category.
     */
    fun isPlausibleNameValue(v: String): Boolean {
        if (v.length !in 2..40) return false
        if (v.any { it.isDigit() }) return false
        if (Regex("[.,!?;:।/\"()\\[\\]{}]").containsMatchIn(v)) return false
        val tokens = v.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 3) return false
        return tokens.none {
            val low = it.lowercase().trim('\'', '-')
            low in NAME_VALUE_FILLERS || low in ZODIAC_SIGNS
        }
    }

    /**
     * Conservative implicit fact extraction from the USER's own message.
     *
     * Small on-device models frequently fail to emit the [SAARTHI_MEMORY]
     * marker even when the user clearly states a stable fact, so memory never
     * fills. ChatGPT / Gemini extract these heuristically; we mirror that with
     * a SMALL set of high-precision patterns. Precision over recall: a missed
     * fact is harmless (the user can restate), a wrong one is annoying, so the
     * patterns are deliberately strict (anchored, single capture, length-capped).
     *
     * Returns (key, value) pairs; persistence + USER_SCOPE routing is handled
     * by ChatRepositoryImpl.persistMemoryFact.
     */
    fun extractImplicitFacts(message: String): List<Pair<String, String>> {
        val msg = message.trim()
        // Raised from 300 to 500 — introductions are commonly embedded inside
        // longer messages ("Hi, my name is Arjun, I'm a farmer from MP, I need
        // help with…") and were being silently skipped at the old limit.
        if (msg.isEmpty() || msg.length > 500) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        // '।' = Devanagari danda (sentence terminator) — strip it like a period.
        fun clean(s: String) = s.trim().trim('.', ',', '!', '"', '\'', '।').trim()
        // Reject captures whose first word is a stopword — "from the office",
        // "called the doctor", "is a bit busy" are not names/places.
        val stopStarts = setOf("the", "a", "an", "my", "this", "that", "here", "there", "not", "just", "so", "very", "really")
        fun firstWordOk(s: String) = s.split(Regex("\\s+")).firstOrNull()?.lowercase() !in stopStarts
        // A name is 1–3 tokens. Cut the capture at the first conjunction / clause
        // boundary so trailing junk never gets stored:
        // "Arjun and I'm vegetarian" → "Arjun", "Arjun aur main shakahari" → "Arjun".
        // Stops at and/or/with/&, romanised+Devanagari "and" (aur/और), a stray
        // "I'm/I", any copula (hai/hoon/है for Hindi, aahe/आहे for Marathi), or
        // a diet term.
        val nameBreakers = setOf(
            "and", "&", "or", "aur", "और", "with", "i'm", "im", "i", "also", "but",
            "hai", "hain", "hoon", "hun", "hu", "है", "हैं",
            "aahe", "ahe", "आहे",
        )
        fun nameHead(s: String): String {
            val kept = mutableListOf<String>()
            for (t in clean(s).split(Regex("\\s+"))) {
                val low = t.lowercase().trim('.', ',', '।')
                if (low.isEmpty()) continue
                if (low in nameBreakers || low in DIET_TERMS) break
                kept += t
                if (kept.size == 3) break
            }
            return kept.joinToString(" ")
        }

        // ── English ──────────────────────────────────────────────────────────
        // name: "my name is X", "I am called X", "call me X"
        Regex("(?i)\\b(?:my name is|i am called|call me|i'm called)\\s+([\\p{L}][\\p{L}\\p{M}\\s.'-]{1,40})")
            .find(msg)?.groupValues?.get(1)?.let { n ->
                val name = nameHead(n)
                if (name.length in 2..40 && firstWordOk(name)) out += "name" to name
            }
        // profession: "I am a teacher", "I work as an electrician", "I'm an engineer"
        Regex("(?i)\\b(?:i am|i'm|i work as)\\s+(?:a|an)\\s+([\\p{L}][\\p{L}\\s-]{2,30})")
            .find(msg)?.groupValues?.get(1)?.let { p ->
                // Cut at the first conjunction — "a vegetarian and Sagittarius"
                // must not be swallowed whole (field log: profession stored as
                // the truncated garbage "vegetarian and Sagittari").
                val prof = clean(p).split(Regex("(?i)\\s+(?:and|aur|or|but)\\s+|,")).first().trim()
                // Guard against "I am a bit tired" false positives AND diet
                // statements ("I am a vegetarian" is diet, not profession).
                if (prof.length in 3..30 &&
                    prof.lowercase() !in NON_PROFESSION_WORDS &&
                    prof.lowercase() !in DIET_TERMS
                ) out += "profession" to prof
            }
        // location: "I live in X", "I am from X", "I'm based in X"
        Regex("(?i)\\b(?:i live in|i am from|i'm from|i am based in|i'm based in)\\s+([\\p{L}][\\p{L}\\s,'-]{1,40})")
            .find(msg)?.groupValues?.get(1)?.let { c ->
                val city = clean(c).split(Regex("\\s+")).take(3).joinToString(" ")
                if (city.length in 2..40 && firstWordOk(city)) out += "city" to city
            }
        // age: "I am 28 years old", "I'm 28"
        Regex("(?i)\\b(?:i am|i'm)\\s+(\\d{1,3})\\s*(?:years old|yrs old|years|yo)\\b")
            .find(msg)?.groupValues?.get(1)?.let { a ->
                val age = a.toIntOrNull()
                if (age != null && age in 1..120) out += "age" to age.toString()
            }

        // ── Hindi / Devanagari ───────────────────────────────────────────────
        // Only patterns with near-zero false-positive risk are included. These
        // are the top reason "No user memories stored yet" appeared in every
        // session log even after the user clearly stated their name or city.
        //
        // name: "मेरा नाम X है" / "मेरा नाम X" — highest-precision Hindi pattern.
        // \\p{L} matches all Unicode letters including Devanagari correctly.
        Regex("मेरा नाम\\s+([\\p{L}][\\p{L}\\p{M}\\s.'-]{0,38})\\s*(?:है|हैं)?")
            .find(msg)?.groupValues?.get(1)?.let { n ->
                val name = nameHead(n)
                if (name.length in 2..40) out += "name" to name
            }
        // city: "मैं X से हूँ" / "मैं X से हूं" — "I am from X"
        Regex("मैं\\s+([\\p{L}][\\p{L}\\p{M}\\s,'-]{1,38})\\s+से\\s+(?:हूँ|हूं)")
            .find(msg)?.groupValues?.get(1)?.let { c ->
                val city = clean(c.trim()).split(Regex("\\s+")).take(3).joinToString(" ")
                if (city.length in 2..40) out += "city" to city
            }
        // city: "मैं X में रहता/रहती हूँ" — "I live in X"
        Regex("मैं\\s+([\\p{L}][\\p{L}\\p{M}\\s,'-]{1,38})\\s+में\\s+(?:रहता|रहती)\\s+(?:हूँ|हूं)")
            .find(msg)?.groupValues?.get(1)?.let { c ->
                val city = clean(c.trim()).split(Regex("\\s+")).take(3).joinToString(" ")
                if (city.length in 2..40) out += "city" to city
            }
        // profession: "मेरा पेशा X है" — "my profession is X". Implicit
        // extraction only ever had this for English ("I am a teacher") — a
        // Hindi speaker saying "मैं एक किसान हूँ" got nothing captured unless
        // the model happened to emit a marker. Mirrors the name pattern's
        // proven possessive-noun structure exactly.
        Regex("मेरा\\s+पेशा\\s+([\\p{L}][\\p{L}\\p{M}\\s.'-]{2,30})\\s*(?:है|हैं)?")
            .find(msg)?.groupValues?.get(1)?.let { p ->
                val prof = nameHead(p)
                if (prof.length in 3..30) out += "profession" to prof
            }
        // age: "मैं 28 साल का/की हूँ" — "I am 28 years old". Age was English-only.
        Regex("मैं\\s+(\\d{1,3})\\s*साल")
            .find(msg)?.groupValues?.get(1)?.let { a ->
                val age = a.toIntOrNull()
                if (age != null && age in 1..120) out += "age" to age.toString()
            }

        // ── Marathi / Devanagari ─────────────────────────────────────────────
        // name: "माझे नाव X आहे" / "माझं नाव X"
        Regex("(?:माझे|माझं|माझ)\\s+नाव\\s+([\\p{L}][\\p{L}\\p{M}\\s.'-]{0,38})\\s*(?:आहे)?")
            .find(msg)?.groupValues?.get(1)?.let { n ->
                val name = nameHead(n)
                if (name.length in 2..40) out += "name" to name
            }
        // city: "मी X मध्ये राहतो/राहते" — "I live in X"
        Regex("मी\\s+([\\p{L}][\\p{L}\\p{M}\\s,'-]{1,38})\\s+मध्ये\\s+(?:राहतो|राहते)")
            .find(msg)?.groupValues?.get(1)?.let { c ->
                val city = clean(c.trim()).split(Regex("\\s+")).take(3).joinToString(" ")
                if (city.length in 2..40) out += "city" to city
            }
        // profession: "माझा व्यवसाय X आहे" — "my profession is X"
        Regex("माझा\\s+व्यवसाय\\s+([\\p{L}][\\p{L}\\p{M}\\s.'-]{2,30})\\s*(?:आहे)?")
            .find(msg)?.groupValues?.get(1)?.let { p ->
                val prof = nameHead(p)
                if (prof.length in 3..30) out += "profession" to prof
            }
        // age: "मी 28 वर्षांचा/वर्षांची आहे" — "I am 28 years old"
        Regex("मी\\s+(\\d{1,3})\\s*वर्ष")
            .find(msg)?.groupValues?.get(1)?.let { a ->
                val age = a.toIntOrNull()
                if (age != null && age in 1..120) out += "age" to age.toString()
            }

        // ── Other Indian scripts — name only ─────────────────────────────────
        // Name drives the home greeting, and it previously only worked for
        // English/Hindi/Marathi — so a name stated in Telugu/Tamil/Bengali/
        // Kannada/Gujarati/Punjabi/Malayalam/Odia was lost unless the model
        // happened to emit a [SAARTHI_MEMORY] marker (small models often don't).
        // These mirror the multilingual coverage already used for identity
        // questions. High-precision: anchored on each language's "my name (is)"
        // phrase; nameHead() trims trailing clause junk; length-capped.
        if (out.none { it.first == "name" }) {
            Regex(
                "(?:నా\\s*పేరు|என்\\s*பெயர்|எனது\\s*பெயர்|আমার\\s*নাম|ನನ್ನ\\s*ಹೆಸರು|" +
                    "મારુ?ં?\\s*નામ|ਮੇਰਾ\\s*ਨਾਮ|ਮੇਰਾ\\s*ਨਾਂ|എന്റെ\\s*പേര്|ମୋ\\s*ନାମ|ମୋର\\s*ନାମ)" +
                    "\\s+([\\p{L}][\\p{L}\\p{M}\\s.'-]{0,38})",
            ).find(msg)?.groupValues?.get(1)?.let { n ->
                val name = nameHead(n)
                if (name.length in 2..40) out += "name" to name
            }
        }
        // ── Other Indian scripts — profession ─────────────────────────────────
        // Same gap as name had before it got this same multilingual pass:
        // profession was English-only ("I am a teacher") plus Hindi/Marathi
        // just added above — every other supported language got nothing.
        // Mirrors each language's proven "my name is" possessive-noun
        // structure exactly, swapping in that language's word for
        // profession/occupation; nameHead() trims trailing clause junk the
        // same way it already does for the name patterns above.
        if (out.none { it.first == "profession" }) {
            Regex(
                "(?:నా\\s*వృత్తి|(?:என்|எனது)\\s*தொழில்|আমার\\s*পেশা|ನನ್ನ\\s*ವೃತ್ತಿ|" +
                    "મારો?\\s*વ્યવસાય|ਮੇਰਾ\\s*ਕਿੱਤਾ|(?:ମୋ|ମୋର)\\s*ବୃତ୍ତି|എന്റെ\\s*തൊഴിൽ)" +
                    "\\s+([\\p{L}][\\p{L}\\p{M}\\s.'-]{2,30})",
            ).find(msg)?.groupValues?.get(1)?.let { p ->
                val prof = nameHead(p)
                if (prof.length in 3..30) out += "profession" to prof
            }
        }

        // ── Other Indian scripts — age ─────────────────────────────────────────
        // Telugu/Tamil state age with a dative "to me N years" construction
        // (different word order from Hindi/Marathi's "I N years"); Bengali/
        // Kannada/Gujarati/Punjabi/Odia use a possessive "my age N" construction
        // instead, like the name/profession patterns above. Same 1..120 sanity
        // range as the English pattern.
        Regex("(?:నాకు|எனக்கு)\\s+(\\d{1,3})\\s*(?:సంవత్సరాలు|வயது)")
            .find(msg)?.groupValues?.get(1)?.let { a ->
                val age = a.toIntOrNull()
                if (age != null && age in 1..120) out += "age" to age.toString()
            }
        if (out.none { it.first == "age" }) {
            Regex(
                "(?:আমার\\s*বয়স|ನನ್ನ\\s*ವಯಸ್ಸು|મારી\\s*ઉંમર|ਮੇਰੀ\\s*ਉਮਰ|(?:ମୋ|ମୋର)\\s*ବୟସ)" +
                    "\\s+(\\d{1,3})",
            ).find(msg)?.groupValues?.get(1)?.let { a ->
                val age = a.toIntOrNull()
                if (age != null && age in 1..120) out += "age" to age.toString()
            }
        }

        // Romanised equivalents of the same phrases — users frequently type
        // their language in Latin script on a mobile keyboard.
        if (out.none { it.first == "name" }) {
            Regex(
                "(?i)\\b(?:naa? peru|en(?:na|adhu)? peyar|amar naam|nanna hesaru|" +
                    "nimma hesaru|maaru? naam|maru nam|mo(?:ra|r)? naam|ente peru?)" +
                    "\\s+([\\p{L}][\\p{L}\\p{M}\\s.'-]{1,40})",
            ).find(msg)?.groupValues?.get(1)?.let { n ->
                val name = nameHead(n)
                if (name.length in 2..40 && firstWordOk(name)) out += "name" to name
            }
        }

        // ── Transliterated Marathi (Latin script) ────────────────────────────
        // name: "majhe naav X (aahe)" / "maza nav X"
        Regex("(?i)\\b(?:majhe|majha|maza|mazha)\\s+naa?v\\s+([\\p{L}][\\p{L}\\p{M}\\s.'-]{1,40})(?:\\s+aahe|\\s+ahe)?\\b")
            .find(msg)?.groupValues?.get(1)?.let { n ->
                val name = nameHead(n)
                if (name.length in 2..40 && firstWordOk(name)) out += "name" to name
            }

        // ── Transliterated Hindi (Latin script) ─────────────────────────────
        // Very common on mobile — users type Hindi words in Roman script.
        // name: "mera naam X hai" / "mera naam X"
        Regex("(?i)\\bmera\\s+naam\\s+([\\p{L}][\\p{L}\\p{M}\\s.'-]{1,40})(?:\\s+hai)?\\b")
            .find(msg)?.groupValues?.get(1)?.let { n ->
                val name = nameHead(n)
                if (name.length in 2..40 && firstWordOk(name)) out += "name" to name
            }
        // city: "main X se hun/hoon" — "I am from X"
        Regex("(?i)\\bmain\\s+([\\p{L}][\\p{L}\\s,'-]{1,40})\\s+se\\s+(?:hun|hoon|hu)\\b")
            .find(msg)?.groupValues?.get(1)?.let { c ->
                val city = clean(c).split(Regex("\\s+")).take(3).joinToString(" ")
                if (city.length in 2..40 && firstWordOk(city)) out += "city" to city
            }

        // ── Diet / food preference ───────────────────────────────────────────
        // "I'm a vegetarian" → was the exact case in the log that was never
        // captured. Pattern requires a first-person verb before the term to
        // avoid catching "find me a vegetarian restaurant" type queries.
        // findAll (not find): "I'm Arjun and I'm vegetarian" has TWO first-person
        // clauses — find() stopped at "Arjun" (not a diet term) and never saw
        // "vegetarian". Scan all clauses and keep the first that IS a diet term.
        Regex("(?i)\\b(?:i'?m|i am|i eat|i follow|i prefer|main|mai|mae)\\s+(?:a |an |strictly |purely )?([a-zA-Z-]{3,20})\\b")
            .findAll(msg)
            .map { it.groupValues[1].lowercase() }
            .firstOrNull { it in DIET_TERMS }
            ?.let { out += "diet" to it }
        // Conjunction case: "I'm Arjun and vegetarian" — the diet term sits after
        // "and" with no first-person verb of its own, so the pattern above misses
        // it. Require a leading first-person clause so "vegetarian restaurant"
        // style queries don't trigger. Only fill if not already captured.
        if (out.none { it.first == "diet" }) {
            // English "I'm Arjun and vegetarian" + romanised Hindi "Mae/main Arjun
            // and vegetarian" / "main Arjun, vegetarian".
            Regex("(?i)\\b(?:i'?m|i am|main|mai|mae)\\s+[a-z]+(?:\\s+and|,)\\s+(?:a |an |strictly |purely )?([a-zA-Z-]{3,20})\\b")
                .findAll(msg)
                .map { it.groupValues[1].lowercase() }
                .firstOrNull { it in DIET_TERMS }
                ?.let { out += "diet" to it }
        }
        // Devanagari diet (Hindi + Marathi): "मी/मैं ... शाकाहारी/मांसाहारी". Require a
        // first-person word near the term so "शाकाहारी हॉटेल" (veg restaurant) is ignored.
        if (out.none { it.first == "diet" }) {
            val firstPerson = Regex("(?:मी|मैं|माझा|माझे|मेरा|मला|मुझे|मी)")
            when {
                Regex("शाकाहारी").containsMatchIn(msg) && firstPerson.containsMatchIn(msg) -> out += "diet" to "vegetarian"
                Regex("मांसाहारी").containsMatchIn(msg) && firstPerson.containsMatchIn(msg) -> out += "diet" to "non-vegetarian"
            }
        }
        // Same pattern, remaining 7 supported languages — each language's
        // vegetarian/non-vegetarian words are unambiguous enough on their own
        // that a same-message first-person pronoun (any of the ones already
        // established for these languages' name/age patterns) is sufficient
        // to distinguish "I'm vegetarian" from a mention of a veg restaurant.
        if (out.none { it.first == "diet" }) {
            val firstPerson = Regex("(?:నేను|నాకు|நான்|எனக்கு|আমি|ನಾನು|ನನಗೆ|હું|ਮੈਂ|ମୁଁ)")
            when {
                Regex("శాకాహారి|சைவம்|নিরামিষ|ಸಸ್ಯಾಹಾರಿ|શાકાહારી|ਸ਼ਾਕਾਹਾਰੀ|ନିରାମିଷ").containsMatchIn(msg) &&
                    firstPerson.containsMatchIn(msg) -> out += "diet" to "vegetarian"
                Regex("మాంసాహారి|அசைவம்|আমিষ|ಮಾಂಸಾಹಾರಿ|માંસાહારી|ਮਾਸਾਹਾਰੀ|ଆମିଷ").containsMatchIn(msg) &&
                    firstPerson.containsMatchIn(msg) -> out += "diet" to "non-vegetarian"
            }
        }

        // ── Name (generic first-person) ──────────────────────────────────────
        // "I'm Arjun", "I am Arjun", "main Arjun hoon/hu" — the most common way
        // users give their name (the earlier patterns only caught "my name is").
        // High-precision guards: require a Capitalised token (proper noun) and
        // reject diet/profession/stopword captures so "I'm tired"/"I'm vegetarian"
        // never become a name. Native-script names rely on the "मेरा नाम X" /
        // [SAARTHI_MEMORY] paths instead (no reliable capitalisation signal).
        if (out.none { it.first == "name" }) {
            fun nameOk(n: String): Boolean {
                val low = n.lowercase()
                return low !in DIET_TERMS && low !in NON_PROFESSION_WORDS &&
                    low !in NON_NAME_WORDS && firstWordOk(n)
            }
            // English "I'm Arjun" / "I am Arjun": case-insensitive prefix, but the
            // name itself must be Capitalised (a proper noun) to avoid catching
            // "I'm tired"-style states.
            Regex("(?:[Ii]'?m|[Ii] am)\\s+([A-Z][a-zA-Z'-]{1,20})\\b")
                .findAll(msg).map { it.groupValues[1] }
                .firstOrNull(::nameOk)
                ?.let { out += "name" to it }
            // Romanised Hindi "main Arjun hoon/hu/hun" — the trailing copula makes
            // this high-precision even without a capitalisation signal.
            if (out.none { it.first == "name" }) {
                Regex("(?i)\\bmain\\s+([\\p{L}][\\p{L}\\p{M}'-]{1,20})\\s+(?:hoon|hun|hu)\\b")
                    .find(msg)?.groupValues?.get(1)
                    ?.let { it.trim().replaceFirstChar { c -> c.uppercase() } }
                    ?.takeIf(::nameOk)
                    ?.let { out += "name" to it }
            }
            // Romanised Hindi "Mae/main/mai Arjun ..." with no copula — common in
            // Hinglish ("Mae Arjun and vegetarian"). Anchor at the message start
            // and require a Capitalised proper noun so only a deliberate self-intro
            // matches (case-sensitive name; prefix allows either case).
            if (out.none { it.first == "name" }) {
                Regex("^(?:[Mm]ae|[Mm]ain|[Mm]ai)\\s+([A-Z][a-zA-Z'-]{1,20})\\b")
                    .find(msg.trim())?.groupValues?.get(1)
                    ?.takeIf(::nameOk)
                    ?.let { out += "name" to it }
            }
            // Whole-message self-intro with a LOWERCASE name — phone keyboards
            // auto-capitalise only the FIRST word, so "Mae arjun" / "i'm arjun"
            // is how this actually arrives (field report: the name was never
            // captured, so the home greeting stayed generic). Dropping the
            // proper-noun requirement is safe ONLY because the match is the
            // ENTIRE message (pronoun + exactly one word): that shape is
            // unambiguously a self-intro, and nameOk + the expanded
            // NON_NAME_WORDS list (theek/busy/hungry/ghar…) rejects
            // state-of-being words.
            if (out.none { it.first == "name" }) {
                Regex("(?i)^(?:i'?m|i am|mae|main|mai|mein)\\s+([\\p{L}][\\p{L}\\p{M}'-]{1,20})\\s*[.!।]?$")
                    .find(msg.trim())?.groupValues?.get(1)
                    ?.let { it.trim().replaceFirstChar { c -> c.uppercase() } }
                    ?.takeIf(::nameOk)
                    ?.let { out += "name" to it }
            }
            // Native-script whole-message self-intro — "मैं अर्जुन" (hi),
            // "मी अर्जुन" (mr), "నేను అర్జున్" (te), "நான் அர்ஜுன்" (ta),
            // "আমি অর্জুন" (bn), "ನಾನು ಅರ್ಜುನ್" (kn), "હું અર્જુન" (gu),
            // "ਮੈਂ ਅਰਜੁਨ" (pa), "ମୁଁ ଅର୍ଜୁନ" (or). The "मेरा नाम X" patterns
            // above need the word "naam"; this catches the bare-pronoun intro.
            // Whole-message (pronoun + exactly one word) keeps precision;
            // NATIVE_STATE_WORDS rejects "मैं ठीक"-style states.
            if (out.none { it.first == "name" }) {
                Regex("^(?:मैं|मै|मी|నేను|நான்|আমি|ನಾನು|હું|ਮੈਂ|ମୁଁ)\\s+([\\p{L}][\\p{L}\\p{M}·'-]{1,24})\\s*[.!।]?$")
                    .find(msg.trim())?.groupValues?.get(1)?.trim()
                    ?.takeIf { it !in NATIVE_STATE_WORDS && nameOk(it) }
                    ?.let { out += "name" to it }
            }
        }

        // ── Likes / dislikes ─────────────────────────────────────────────────
        // A personal assistant should remember preferences. English + romanised
        // Hindi, first-person only. Captured as free-text values under the
        // 'likes'/'dislikes' keys (the memory layer stores multiple values).
        Regex("(?i)\\bi (?:really )?(?:like|love|enjoy|prefer)\\s+([\\p{L}][\\p{L}\\s'-]{2,40})")
            .findAll(msg).map { clean(it.groupValues[1]) }
            .firstOrNull { it.length in 3..40 && firstWordOk(it) }
            ?.let { out += "likes" to it }
        Regex("(?i)\\bi (?:really )?(?:dislike|hate|don'?t like|do not like|can'?t stand)\\s+([\\p{L}][\\p{L}\\s'-]{2,40})")
            .findAll(msg).map { clean(it.groupValues[1]) }
            .firstOrNull { it.length in 3..40 && firstWordOk(it) }
            ?.let { out += "dislikes" to it }
        // romanised Hindi: "mujhe X pasand hai" (like) / "pasand nahi" (dislike)
        Regex("(?i)\\bmujhe\\s+([\\p{L}][\\p{L}\\s'-]{2,40}?)\\s+pasand\\s+(nahi|nahin|nahee)?\\b")
            .find(msg)?.let { m ->
                val v = clean(m.groupValues[1])
                val neg = m.groupValues[2].isNotBlank()
                if (v.length in 3..40 && firstWordOk(v)) out += (if (neg) "dislikes" else "likes") to v
            }
        // Devanagari likes/dislikes — the romanised pattern above missed native
        // script. Hindi "मुझे X पसंद है / पसंद नहीं", Marathi "मला X आवडते /
        // आवडत नाही". Bare pronoun captures ("मुझे यह पसंद…") are rejected.
        if (out.none { it.first == "likes" || it.first == "dislikes" }) {
            Regex("(?:मुझे|मला)\\s+([\\p{L}][\\p{L}\\p{M}\\s'-]{1,40}?)\\s+(?:पसंद|आवडत[ेो]?|आवडते)\\s*(नहीं|नाही)?")
                .find(msg)?.let { m ->
                    val v = clean(m.groupValues[1])
                    val neg = m.groupValues[2].isNotBlank()
                    val pronounOnly = v in setOf("यह", "वह", "ये", "वो", "इस", "उस", "हे", "ते")
                    if (v.length in 2..40 && !pronounOnly) out += (if (neg) "dislikes" else "likes") to v
                }
        }
        // Remaining 7 supported languages — same dative/possessive "to-me/my
        // X [is-liked]" shape as the Hindi/Marathi pattern above, just each
        // language's own marker + verb. Negation particle only included where
        // it's a simple appended word (Tamil and Gujarati negate the verb
        // itself rather than appending a particle — skipped rather than risk
        // an incorrect pattern; the positive "likes" case is still captured).
        if (out.none { it.first == "likes" || it.first == "dislikes" }) {
            data class LikePattern(val marker: String, val likeWord: String, val dislikeWord: String?)
            val patterns = listOf(
                LikePattern("నాకు", "ఇష్టం", "లేదు"),
                LikePattern("எனக்கு", "பிடிக்கும்", null),
                LikePattern("আমার", "পছন্দ", "না"),
                LikePattern("ನನಗೆ", "ಇಷ್ಟ", "ಇಲ್ಲ"),
                LikePattern("મને", "ગમે", null),
                LikePattern("ਮੈਨੂੰ", "ਪਸੰਦ", "ਨਹੀਂ"),
                LikePattern("ମୋତେ", "ପସନ୍ଦ", "ନାହିଁ"),
            )
            for (p in patterns) {
                val negGroup = if (p.dislikeWord != null) "\\s*(${p.dislikeWord})?" else ""
                val m = Regex("${p.marker}\\s+([\\p{L}][\\p{L}\\p{M}\\s'-]{1,40}?)\\s+${p.likeWord}$negGroup")
                    .find(msg) ?: continue
                val v = clean(m.groupValues[1])
                val neg = p.dislikeWord != null && m.groupValues.getOrNull(2)?.isNotBlank() == true
                if (v.length in 2..40) {
                    out += (if (neg) "dislikes" else "likes") to v
                    break
                }
            }
        }

        // ── Employer ─────────────────────────────────────────────────────────
        // "I work at Infosys", "I'm working for TCS" — distinct from profession
        // (which captures job title; this captures company/organisation name).
        Regex("(?i)\\b(?:i work at|i work for|i'?m working at|i'?m working for|i am working at|i am working for)\\s+([\\p{L}][\\p{L}\\s&.-]{1,30})")
            .find(msg)?.groupValues?.get(1)?.let { e ->
                val employer = clean(e).split(Regex("\\s+")).take(3).joinToString(" ")
                if (employer.length in 2..30 && firstWordOk(employer)) out += "employer" to employer
            }

        return out
    }

    companion object {
        // Dietary preference terms matched by the diet extractor above.
        // Kept separate so adding new terms doesn't risk widening the profession regex.
        private val DIET_TERMS = setOf(
            "vegetarian", "vegan", "jain", "eggetarian",
            "non-vegetarian", "nonvegetarian",
        )

        // Capitalised words that can follow "I'm / I am / main" at a sentence start
        // but are NOT a name — guards the generic name extractor against
        // "I'm Looking…", "I'm Sorry", "Main Hoon" style false positives.
        private val NON_NAME_WORDS = setOf(
            "looking", "trying", "going", "sorry", "here", "there", "back", "done",
            "new", "good", "fine", "okay", "ok", "ready", "happy", "sad", "tired",
            "busy", "glad", "sure", "just", "also", "really", "from", "not", "still",
            "always", "now", "interested", "thinking", "feeling", "hoping", "wondering",
            "hoon", "hun", "hu", "main", "hello", "hi",
            // More English states — needed now that the whole-message self-intro
            // pattern accepts lowercase captures ("i'm hungry" must never be a name).
            "hungry", "bored", "angry", "upset", "sick", "ill", "lost", "late",
            "home", "free", "cold", "hot", "well", "great", "alone", "scared",
            // Romanised Hindi/Marathi state-of-being words — "main theek hoon" /
            // "Mae busy" style messages must never store a name.
            "theek", "thik", "thak", "accha", "acha", "achha", "badhiya", "mast",
            "ghar", "bahar", "pareshan", "khush", "udaas", "bimar", "vyast",
            "stress", "stressed", "bore", "so", "raha", "rahi", "gaya", "gayi",
        )

        // Native-script state-of-being words that follow a bare first-person
        // pronoun ("मैं ठीक", "मी घरी") — must never be captured as a name by the
        // whole-message native self-intro pattern.
        private val NATIVE_STATE_WORDS = setOf(
            // Hindi
            "ठीक", "अच्छा", "अच्छी", "खुश", "उदास", "बीमार", "व्यस्त", "परेशान",
            "घर", "तैयार", "थका", "थकी", "यहाँ", "वहाँ", "हूँ", "हूं", "भूखा", "भूखी",
            // Marathi
            "आनंदी", "आजारी", "थकलो", "थकले", "घरी", "बरा", "बरी", "इथे", "आहे",
            // Telugu / Tamil / Bengali / Kannada / Gujarati / Punjabi / Odia (common states)
            "బాగున్నాను", "అలసిపోయాను", "ఇంట్లో", "நலம்", "சோர்வாக", "வீட்டில்",
            "ভালো", "ক্লান্ত", "বাড়িতে", "ಚೆನ್ನಾಗಿದ್ದೇನೆ", "ಮನೆಯಲ್ಲಿ",
            "સારું", "થાકેલો", "ઘરે", "ਠੀਕ", "ਥੱਕਿਆ", "ਘਰ", "ଭଲ", "ଘରେ",
        )

        // Pronoun / copula / label tokens (any script) that mark a stored name
        // VALUE as sentence-shaped model output rather than an actual name — used
        // by isPlausibleNameValue to keep garbage out of name keys entirely.
        private val NAME_VALUE_FILLERS = setOf(
            // English + romanised Hindi/Marathi
            "user", "users", "name", "is", "my", "the", "a", "an",
            "mera", "meri", "mere", "naam", "nam", "naav", "nav",
            "hai", "hain", "hoon", "hun", "hu", "main", "mai", "mae",
            "ka", "ki", "ke", "majhe", "majha", "aahe", "ahe",
            // Devanagari (Hindi/Marathi)
            "उपयोगकर्ता", "यूज़र", "नाम", "मेरा", "मेरी", "मेरे", "है", "हैं", "हूँ", "हूं",
            "का", "की", "के", "मैं", "नाव", "माझे", "माझं", "आहे", "मी",
            // Telugu / Tamil / Bengali / Kannada / Gujarati / Punjabi / Odia
            "పేరు", "నా", "పెయరు", "பெயர்", "என்", "எனது", "নাম", "আমার",
            "ಹೆಸರು", "ನನ್ನ", "નામ", "મારું", "મારુ", "ਨਾਮ", "ਮੇਰਾ", "ନାମ", "ମୋର", "ମୋ",
        )

        // Words that follow "I am a/an …" but are NOT a profession — guards the
        // implicit profession extractor against "I am a bit tired" style matches.
        private val NON_PROFESSION_WORDS = setOf(
            "bit", "little", "lot", "fan", "big", "huge", "small", "good", "bad",
            "great", "happy", "sad", "tired", "fine", "beginner", "expert", "newbie",
            "student",  // handled as education, not profession
        )

        // Zodiac signs — see isPlausibleNameValue's use of this set: a real word,
        // not sentence-shaped junk, so it needs its own explicit rejection rather
        // than relying on NAME_VALUE_FILLERS.
        private val ZODIAC_SIGNS = setOf(
            "aries", "taurus", "gemini", "cancer", "leo", "virgo",
            "libra", "scorpio", "sagittarius", "capricorn", "aquarius", "pisces",
        )
    }
}
