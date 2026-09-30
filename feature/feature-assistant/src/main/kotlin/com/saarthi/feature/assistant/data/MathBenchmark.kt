package com.saarthi.feature.assistant.data

import com.saarthi.core.inference.engine.RecalculationGuard
import java.math.BigDecimal

/**
 * Debug-build maths benchmark ("/mathbench" in the chat): fixed questions from
 * the 2026-09-30 device tests, each run through the real plain-chat pipeline,
 * scored for accuracy AND stability — the failure modes seen on device were
 * wrong-first-then-corrected answers, repeated self-corrections and runaway
 * numbers, not just wrong finals. Questions are fixed strings, not user data.
 */
internal data class MathBenchCase(val id: Int, val question: String, val expected: List<String>)

internal val MATH_BENCH_CASES = listOf(
    MathBenchCase(1, "पैंतीस में 12 जोड़ो", listOf("47")),
    MathBenchCase(2, "What is 50 - 23?", listOf("27")),
    MathBenchCase(3, "Mere paas 4 apples hain, aur mummy ne 3 aur diye. Total kitne apples hue?", listOf("7")),
    MathBenchCase(4, "बारह को 3 से multiply karo.", listOf("36")),
    MathBenchCase(5, "₹100 mein se ₹35 kharch kiye. Kitne rupees bache?", listOf("65")),
    MathBenchCase(6, "अट्ठाईस में से 9 घटाओ।", listOf("19")),
    MathBenchCase(7, "2+2^2", listOf("6")),
    MathBenchCase(8, "(2+2)^2", listOf("16")),
    MathBenchCase(9, "3/4 of 100 kitna hai?", listOf("75")),
    MathBenchCase(10, "Ravi ke paas 2.5 litres milk hai. Usne 750 ml use kiya. Kitna milk bacha?", listOf("1.75", "1750")),
    MathBenchCase(11, "A number ko 8 se multiply karne par 96 milta hai. Number kya hai?", listOf("12")),
    MathBenchCase(12, "A shopkeeper buys 20 notebooks at ₹45 each and sells each for ₹60. Total profit kitna hai?", listOf("300")),
    MathBenchCase(13, "Ek train 240 km distance 4 hours mein cover karti hai. Same speed par 7 hours mein kitna distance cover karegi?", listOf("420")),
    MathBenchCase(14, "Rahul ke paas ₹5,000 hain. Woh 20% shopping par, phir remaining amount ka 10% travel par spend karta hai. Kitne rupees bachenge?", listOf("3600")),
    MathBenchCase(15, "A family spends ₹18,000 per month. Rent is 35%, food is 25%, education is 15%. Baaki expenses ke liye kitna paisa bachta hai?", listOf("4500")),
    MathBenchCase(16, "A bus travels 180 km. First 60 km at 40 km/h and remaining distance at 60 km/h. Total travel time kitna hoga?", listOf("3.5", "210")),
    MathBenchCase(17, "A shop gives 20% discount on a ₹2,500 item, then charges 18% GST on the discounted price. Final price kya hoga?", listOf("2360")),
    MathBenchCase(18, "Mere paas ₹2,400 hain. Maine 1/3 rent par aur remaining ka 25% food par spend kiya. Kitne rupees bache?", listOf("1200")),
    MathBenchCase(19, "₹1,200 par 10% discount hai. Final price kya hoga?", listOf("1080")),
    MathBenchCase(20, "पैंतालीस × 12 = ?", listOf("540")),
)

internal data class MathBenchResult(
    val caseId: Int,
    val finalAnswer: String?,
    val finalCorrect: Boolean,
    val containsCorrect: Boolean,
    val corrections: Int,
    val collapsed: Boolean,
)

private val BENCH_NUMBER = Regex("""\d[\d,]*(?:\.\d+)?""")
private val ANSWER_LINE_NUMBER = Regex("""(?im)^\**\s*(?:final\s+answer|answer|उत्तर|जवाब)\s*\**\s*[:：].*?(\d[\d,]*(?:\.\d+)?)""")

private fun decimal(s: String): BigDecimal? = s.replace(",", "").toBigDecimalOrNull()

private fun sameNumber(a: String, b: String): Boolean {
    val x = decimal(a) ?: return false
    val y = decimal(b) ?: return false
    return x.compareTo(y) == 0
}

/**
 * Score one reply. [raw] is the model output before any trimming (for
 * corrections / collapse); [shown] is what the user would see (for the answer).
 */
internal fun scoreMathReply(case: MathBenchCase, raw: String, shown: String): MathBenchResult {
    val final = ANSWER_LINE_NUMBER.findAll(shown).lastOrNull()?.groupValues?.get(1)
        ?: BENCH_NUMBER.findAll(shown).lastOrNull()?.value
    val finalCorrect = final != null && case.expected.any { sameNumber(it, final) }
    val containsCorrect = BENCH_NUMBER.findAll(shown).any { n -> case.expected.any { sameNumber(it, n.value) } }
    // Collapse: a number 10× larger than anything in the question or the answer.
    val ceiling = (BENCH_NUMBER.findAll(stripThousandsSeparators(case.question)).map { it.value } + case.expected)
        .mapNotNull { decimal(it) }.maxOrNull() ?: BigDecimal.ONE
    val collapsed = BENCH_NUMBER.findAll(raw).any { n -> decimal(n.value)?.let { it > ceiling.multiply(BigDecimal.TEN) } == true }
    return MathBenchResult(
        caseId = case.id,
        finalAnswer = final,
        finalCorrect = finalCorrect,
        containsCorrect = containsCorrect,
        corrections = RecalculationGuard.correctionCount(raw),
        collapsed = collapsed,
    )
}

/** One-screen summary: totals, then one line per case (✓ = every run correct). */
internal fun summarizeMathBenchmark(results: List<MathBenchResult>, runsPerQuestion: Int): String {
    if (results.isEmpty()) return "Math benchmark: no results."
    val total = results.size
    fun pct(n: Int) = "${n * 100 / total}%"
    val finalOk = results.count { it.finalCorrect }
    val anywhere = results.count { it.containsCorrect }
    val corrected = results.count { it.corrections > 0 }
    val collapsed = results.count { it.collapsed }
    val consistent = results.groupBy { it.caseId }.values.count { runs -> runs.map { it.finalAnswer }.distinct().size == 1 }
    return buildString {
        appendLine("Math benchmark — ${results.map { it.caseId }.distinct().size} questions × $runsPerQuestion run(s)")
        appendLine("Final answer correct: $finalOk/$total (${pct(finalOk)})")
        appendLine("Correct value anywhere: $anywhere/$total (${pct(anywhere)})")
        appendLine("Self-corrected: $corrected/$total · Collapsed: $collapsed/$total")
        appendLine("Consistent across runs: $consistent/${results.map { it.caseId }.distinct().size}")
        appendLine()
        for ((id, runs) in results.groupBy { it.caseId }.toSortedMap()) {
            val ok = runs.count { it.finalCorrect }
            val mark = if (ok == runs.size) "✓" else "✗"
            appendLine("$mark #$id  ${ok}/${runs.size}  answers=${runs.map { it.finalAnswer ?: "-" }.joinToString(",")}")
        }
    }.trimEnd()
}
