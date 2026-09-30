package com.saarthi.feature.assistant.data

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Arithmetic help for the on-device model. Small models understand WHAT to
 * calculate but slip on the digits (device test 2026-09-30: 35+12 → 34,
 * 12×3 → 33, 28−9 → 8), so the app does the exact part itself — offline,
 * deterministic — and hands the model the verified result to phrase.
 *
 * Deliberately conservative: [verifiedCalculation] only fires for an
 * explicit calculation whose numbers are ALL accounted for by one operation
 * (a wrong "verified" fact would be worse than none). Word problems fall
 * back to the model, helped by [isNumericQuestion] (low-temperature sampler
 * + work-first instruction).
 */

/** An exact result the app computed, e.g. expression "35 + 12", result "47". */
internal data class VerifiedCalculation(val expression: String, val result: String)

// ── Number words → digits ───────────────────────────────────────────────────

private val HINDI_UNITS: Map<String, Long> = buildMap {
    fun put(n: Long, vararg words: String) = words.forEach { put(it, n) }
    put(0, "शून्य"); put(1, "एक"); put(2, "दो"); put(3, "तीन"); put(4, "चार")
    put(5, "पाँच", "पांच"); put(6, "छह", "छः", "छे"); put(7, "सात"); put(8, "आठ"); put(9, "नौ")
    put(10, "दस"); put(11, "ग्यारह"); put(12, "बारह"); put(13, "तेरह"); put(14, "चौदह")
    put(15, "पंद्रह", "पन्द्रह"); put(16, "सोलह"); put(17, "सत्रह"); put(18, "अठारह"); put(19, "उन्नीस")
    put(20, "बीस"); put(21, "इक्कीस"); put(22, "बाईस"); put(23, "तेईस"); put(24, "चौबीस")
    put(25, "पच्चीस"); put(26, "छब्बीस"); put(27, "सत्ताईस"); put(28, "अट्ठाईस", "अठ्ठाईस", "अठाईस"); put(29, "उनतीस")
    put(30, "तीस"); put(31, "इकतीस", "इकत्तीस"); put(32, "बत्तीस"); put(33, "तैंतीस", "तेंतीस"); put(34, "चौंतीस", "चौतीस")
    put(35, "पैंतीस", "पैतीस"); put(36, "छत्तीस"); put(37, "सैंतीस", "सैतीस"); put(38, "अड़तीस", "अडतीस"); put(39, "उनतालीस")
    put(40, "चालीस"); put(41, "इकतालीस"); put(42, "बयालीस"); put(43, "तैंतालीस", "तेंतालीस"); put(44, "चवालीस", "चौवालीस")
    put(45, "पैंतालीस", "पैतालीस"); put(46, "छियालीस"); put(47, "सैंतालीस", "सैतालीस"); put(48, "अड़तालीस", "अडतालीस"); put(49, "उनचास")
    put(50, "पचास"); put(51, "इक्यावन"); put(52, "बावन"); put(53, "तिरेपन", "तिरपन"); put(54, "चौवन", "चौव्वन")
    put(55, "पचपन"); put(56, "छप्पन"); put(57, "सत्तावन"); put(58, "अट्ठावन", "अठावन"); put(59, "उनसठ")
    put(60, "साठ"); put(61, "इकसठ"); put(62, "बासठ"); put(63, "तिरसठ"); put(64, "चौंसठ", "चौसठ")
    put(65, "पैंसठ", "पैसठ"); put(66, "छियासठ"); put(67, "सड़सठ", "सडसठ"); put(68, "अड़सठ", "अडसठ"); put(69, "उनहत्तर")
    put(70, "सत्तर"); put(71, "इकहत्तर"); put(72, "बहत्तर"); put(73, "तिहत्तर"); put(74, "चौहत्तर")
    put(75, "पचहत्तर"); put(76, "छिहत्तर"); put(77, "सतहत्तर"); put(78, "अठहत्तर"); put(79, "उन्यासी", "उनासी")
    put(80, "अस्सी"); put(81, "इक्यासी"); put(82, "बयासी"); put(83, "तिरासी"); put(84, "चौरासी")
    put(85, "पचासी"); put(86, "छियासी"); put(87, "सत्तासी"); put(88, "अट्ठासी", "अठासी"); put(89, "नवासी", "नवासी")
    put(90, "नब्बे"); put(91, "इक्यानवे"); put(92, "बानवे"); put(93, "तिरानवे"); put(94, "चौरानवे")
    put(95, "पचानवे", "पंचानवे"); put(96, "छियानवे"); put(97, "सत्तानवे"); put(98, "अट्ठानवे", "अठानवे"); put(99, "निन्यानवे")
}

private val HINDI_SCALES: Map<String, Long> = mapOf(
    "सौ" to 100L, "हज़ार" to 1_000L, "हजार" to 1_000L, "लाख" to 100_000L, "करोड़" to 10_000_000L, "करोड" to 10_000_000L,
)

private val ENGLISH_UNITS: Map<String, Long> = mapOf(
    "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
    "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
    "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
    "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
)

private val ENGLISH_SCALES: Map<String, Long> = mapOf("hundred" to 100L, "thousand" to 1_000L, "lakh" to 100_000L, "crore" to 10_000_000L)

private fun unitValue(word: String): Long? = HINDI_UNITS[word] ?: ENGLISH_UNITS[word.lowercase()]
private fun scaleValue(word: String): Long? = HINDI_SCALES[word] ?: ENGLISH_SCALES[word.lowercase()]

private val WORD_OR_GAP = Regex("""[\p{L}\p{M}]+(?:-[\p{L}\p{M}]+)?|[^\p{L}\p{M}]+""")

private fun numberWordValue(word: String): Long? {
    unitValue(word)?.let { return it }
    val parts = word.split('-')
    if (parts.size == 2) {
        val a = ENGLISH_UNITS[parts[0].lowercase()] ?: return null
        val b = ENGLISH_UNITS[parts[1].lowercase()] ?: return null
        return a + b
    }
    return null
}

/**
 * Replace spelled-out numbers (Hindi or English, including compounds like
 * "दो सौ पचास" or "twenty-five") with digits. Other words are untouched. A
 * lone "एक" / "one" stays a word — "एक किसान" is "a farmer", not a number.
 */
internal fun normalizeNumberWords(text: String): String {
    val tokens = WORD_OR_GAP.findAll(text).map { it.value }.toList()
    val out = StringBuilder()
    var i = 0
    while (i < tokens.size) {
        val first = tokens[i]
        if (numberWordValue(first) == null && scaleValue(first) == null) {
            out.append(first); i++; continue
        }
        // Consume number words separated only by single spaces (or "and").
        var total = 0L
        var current = 0L
        var words = 0
        var j = i
        var end = i
        while (j < tokens.size) {
            val t = tokens[j]
            val unit = numberWordValue(t)
            val scale = scaleValue(t)
            when {
                unit != null -> { current += unit; words++; end = j }
                scale != null -> {
                    if (scale == 100L) current = (if (current == 0L) 1L else current) * 100L
                    else { total += (if (current == 0L) 1L else current) * scale; current = 0L }
                    words++; end = j
                }
                t.isBlank() && t.length <= 2 -> { j++; continue }
                t.equals("and", ignoreCase = true) && words > 0 -> { j++; continue }
                else -> break
            }
            j++
        }
        val value = total + current
        val loneOne = words == 1 && value == 1L
        if (loneOne) {
            out.append(first); i++; continue
        }
        out.append(value)
        i = end + 1
    }
    return out.toString()
}

// ── Verified calculation ────────────────────────────────────────────────────

private const val NUM = """(\d+(?:\.\d+)?)"""
private val DIGIT_NUMBER = Regex("""\d+(?:\.\d+)?""")

/** Strip currency signs and thousands separators so "₹1,200" reads as 1200. */
private fun cleanNumbers(text: String): String =
    text.replace("₹", "").replace(Regex("""(?<=\d),(?=\d{2,3}\b)"""), "")

private data class Template(val regex: Regex, val op: Char, val swap: Boolean = false)

private val TEMPLATES = listOf(
    // Hindi / Hinglish word forms.
    Template(Regex("""$NUM\s*(?:में|mein|me)\s+(?:से|se)\s+$NUM\s*(?:घटाओ|घटाएं|घटाएँ|घटा|ghatao|minus|subtract)""", RegexOption.IGNORE_CASE), '-'),
    Template(Regex("""$NUM\s*(?:में|mein|me)\s+$NUM\s*(?:जोड़ो|जोड़ें|जोड़|jodo|jod|add)""", RegexOption.IGNORE_CASE), '+'),
    Template(Regex("""$NUM\s*(?:को|ko)\s+$NUM\s*(?:से|se)\s+(?:गुणा|guna|multiply)""", RegexOption.IGNORE_CASE), '*'),
    Template(Regex("""$NUM\s*(?:को|ko)\s+$NUM\s*(?:से|se)\s+(?:भाग|bhaag|bhag|divide)""", RegexOption.IGNORE_CASE), '/'),
    // English word forms.
    Template(Regex("""$NUM\s+plus\s+$NUM""", RegexOption.IGNORE_CASE), '+'),
    Template(Regex("""$NUM\s+minus\s+$NUM""", RegexOption.IGNORE_CASE), '-'),
    Template(Regex("""$NUM\s+(?:times|multiplied\s+by)\s+$NUM""", RegexOption.IGNORE_CASE), '*'),
    Template(Regex("""$NUM\s+divided\s+by\s+$NUM""", RegexOption.IGNORE_CASE), '/'),
    Template(Regex("""subtract\s+$NUM\s+from\s+$NUM""", RegexOption.IGNORE_CASE), '-', swap = true),
    // Percent / fraction "of".
    Template(Regex("""$NUM\s*%\s*(?:of|का|की|के)\s*$NUM""", RegexOption.IGNORE_CASE), '%'),
    Template(Regex("""$NUM\s*(?:का|की|के)\s*$NUM\s*%""", RegexOption.IGNORE_CASE), '%', swap = true),
    // Symbolic "a op b" (op must sit between two numbers).
    Template(Regex("""$NUM\s*\+\s*$NUM"""), '+'),
    Template(Regex("""$NUM\s*[-−]\s*$NUM"""), '-'),
    Template(Regex("""$NUM\s*[×xX*]\s*$NUM"""), '*'),
    Template(Regex("""$NUM\s*÷\s*$NUM"""), '/'),
)

private val FRACTION_OF = Regex("""(\d+)\s*/\s*(\d+)\s*(?:of|का|की|के)\s*$NUM""", RegexOption.IGNORE_CASE)

/**
 * The exact result of the ONE explicit calculation in [message], or null.
 * Null when there is no clear calculation, when the message holds numbers
 * the calculation doesn't use (a word problem — the model must reason), or
 * on division by zero.
 */
internal fun verifiedCalculation(message: String): VerifiedCalculation? {
    val text = cleanNumbers(normalizeNumberWords(message))
    val totalNumbers = DIGIT_NUMBER.findAll(text).count()

    FRACTION_OF.find(text)?.let { m ->
        if (totalNumbers != 3) return null
        val num = BigDecimal(m.groupValues[1]); val den = BigDecimal(m.groupValues[2]); val base = BigDecimal(m.groupValues[3])
        if (den.signum() == 0) return null
        val r = base.multiply(num).divide(den, MathContext.DECIMAL64)
        return VerifiedCalculation("${m.groupValues[1]}/${m.groupValues[2]} of ${m.groupValues[3]}", format(r))
    }

    for (t in TEMPLATES) {
        val m = t.regex.find(text) ?: continue
        if (totalNumbers != 2) return null        // extra numbers → a word problem
        var a = BigDecimal(m.groupValues[1]); var b = BigDecimal(m.groupValues[2])
        if (t.swap) { val tmp = a; a = b; b = tmp }
        val (expr, r) = when (t.op) {
            '+' -> "${plain(a)} + ${plain(b)}" to a.add(b)
            '-' -> "${plain(a)} - ${plain(b)}" to a.subtract(b)
            '*' -> "${plain(a)} × ${plain(b)}" to a.multiply(b)
            '/' -> {
                if (b.signum() == 0) return null
                "${plain(a)} ÷ ${plain(b)}" to a.divide(b, MathContext.DECIMAL64)
            }
            '%' -> "${plain(a)}% of ${plain(b)}" to b.multiply(a).divide(BigDecimal(100), MathContext.DECIMAL64)
            else -> return null
        }
        return VerifiedCalculation(expr, format(r))
    }
    return null
}

private fun plain(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()

private fun format(v: BigDecimal): String =
    v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

// ── Numeric-question detection ──────────────────────────────────────────────

private val MATH_CUES = Regex(
    // Minus only when spaced ("50 - 23") or the − sign: unspaced "5-6 people" is a range.
    "(?i)[+×÷=%−]|\\d\\s*[*/x]\\s*\\d|\\d\\s+-\\s+\\d|" +
        "\\b(?:how\\s+much|how\\s+many|total|sum|difference|product|average|calculate|solve|profit|loss|discount|" +
        "price|cost|speed|distance|interest|percent|percentage|remaining|add|subtract|multiply|divide|" +
        "kitna|kitne|kitni|bacha|bache|bachi|bachhi|jodo|ghatao|guna)\\b|" +
        "कितना|कितने|कितनी|कुल|बच|जोड़|घटा|गुणा|भाग|लाभ|हानि|मुनाफ़ा|मुनाफा|छूट|कीमत|दाम|गति|दूरी|औसत|प्रतिशत|ब्याज",
)

/**
 * True for a question that needs arithmetic: at least two numbers (digits or
 * number words) plus a calculation cue. A false positive only lowers the
 * sampler temperature and asks for shown working.
 */
internal fun isNumericQuestion(message: String): Boolean {
    val text = cleanNumbers(normalizeNumberWords(message))
    if (DIGIT_NUMBER.findAll(text).count() < 2) return false
    return MATH_CUES.containsMatchIn(text)
}

/**
 * The user turn as sent to the model. Unchanged for non-numeric messages; for
 * a numeric question, adds the app's exact result (when there is one) and a
 * work-first instruction — a small model that states the answer first commits
 * to a guess before calculating (device test: "87 बचीं", then the correct 163
 * in its own working).
 */
internal fun mathAwareUserTurn(userMessage: String): String {
    val verified = verifiedCalculation(userMessage)
    if (verified == null && !isNumericQuestion(userMessage)) return userMessage
    return buildString {
        append(userMessage)
        append("\n\n[Calculation — ")
        if (verified != null) {
            append("the app computed this exactly: ${verified.expression} = ${verified.result}. Use this result. ")
        }
        append("Work it out in a few short steps with digits (0-9), check each step, then give the final answer ")
        append("on the last line. Plain text only (use × ÷ = %), no LaTeX or \$ signs. Don't comment on how easy it is.]")
    }
}
