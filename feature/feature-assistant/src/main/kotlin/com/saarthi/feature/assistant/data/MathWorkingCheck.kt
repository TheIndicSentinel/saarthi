package com.saarthi.feature.assistant.data

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * After a calculation reply is generated, re-check each written step
 * `a op b = c` exactly and fix a wrong `c` — device test 2026-09-30: "1.5 hours
 * + 2 hours = 3 hours", "₹2,000 plus ₹360 is ₹2,360" right next to a wrong
 * first answer. Deliberately narrow: a step is only touched when it is one
 * binary operation on plain numbers whose result disagrees beyond rounding.
 * Chained ("2 + 3 + 4 = 9"), bracketed or unit-mixed steps are left alone.
 */

private const val N = """(\d[\d,]*(?:\.\d+)?)"""
// Optional unit after a number: "hours", "km", "km/h", "नोटबुक".
private const val UNIT = """(?:\s*[\p{L}\p{M}]+(?:/[\p{L}\p{M}]+)?)?"""
// Not preceded by an operator or bracket — the left number must START the step.
// "3. 1.5 + 2 = 3" is fine (a list marker); "1.5" must not start at its "5".
private const val STEP_START = """(?<![+\-−×x*÷/(\d]\s{0,3})(?<!\d[.,])(?<![+\-−×x*÷/(]\s{0,3}₹)"""

// A result ends at a whole number (not "1" of "1.5") and is not the middle of a
// chain on the same line ("= 25 × 3"). Next-line list numbers don't count.
private const val RESULT_END = """(?![\d]|[.,]\d)(?![ \t]*[+\-−×x*÷/(])"""

private val BINARY_STEP = Regex(
    """$STEP_START(₹?)$N$UNIT\s*([+\-−×x*÷/])\s*₹?$N$UNIT\s*=\s*(₹?)$N$RESULT_END""",
)

private val PERCENT_STEP = Regex(
    """(?i)$STEP_START$N\s*%\s*(?:of|का|की|के)\s*₹?$N\s*(?:=|is)\s*(₹?)$N$RESULT_END""",
)

private fun num(s: String): BigDecimal = BigDecimal(s.replace(",", ""))

/** True when [exact], rounded half-up to [stated]'s decimals, equals [stated] ("33.33" for 33.333…). */
private fun matches(stated: BigDecimal, exact: BigDecimal): Boolean =
    exact.setScale(maxOf(stated.scale(), 0), RoundingMode.HALF_UP).compareTo(stated) == 0

private fun render(v: BigDecimal): String =
    v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

private data class Fix(val range: IntRange, val replacement: String, val wrong: String)

/** [text] with each wrong single-operation step result replaced by the exact one. */
internal fun verifyWorking(text: String): String {
    val mc = MathContext.DECIMAL64
    val fixes = ArrayList<Fix>()

    for (m in BINARY_STEP.findAll(text)) {
        val a = num(m.groupValues[2]); val op = m.groupValues[3]; val b = num(m.groupValues[4])
        val statedGroup = m.groups[6] ?: continue
        val stated = num(statedGroup.value)
        val exact = when (op) {
            "+" -> a.add(b)
            "-", "−" -> a.subtract(b)
            "×", "x", "*" -> a.multiply(b)
            "÷", "/" -> if (b.signum() == 0) continue else a.divide(b, mc)
            else -> continue
        }
        if (!matches(stated, exact)) fixes += Fix(statedGroup.range, render(exact), statedGroup.value)
    }
    for (m in PERCENT_STEP.findAll(text)) {
        val pct = num(m.groupValues[1]); val base = num(m.groupValues[2])
        val statedGroup = m.groups[4] ?: continue
        val stated = num(statedGroup.value)
        val exact = base.multiply(pct).divide(BigDecimal(100), mc)
        if (!matches(stated, exact)) fixes += Fix(statedGroup.range, render(exact), statedGroup.value)
    }
    if (fixes.isEmpty()) return text

    val out = StringBuilder(text)
    for (f in fixes.sortedByDescending { it.range.first }) {
        out.replace(f.range.first, f.range.last + 1, f.replacement)
    }
    // A final "Answer: X" that repeats the LAST wrong step value follows it.
    val lastFix = fixes.maxByOrNull { it.range.first }!!
    return fixAnswerLine(out.toString(), lastFix.wrong, lastFix.replacement)
}

private val ANSWER_LINE = Regex("""(?im)^(\**\s*$ANSWER_LABEL\s*\**\s*[:：]\s*\**\s*₹?)$N""")

private fun fixAnswerLine(text: String, wrong: String, right: String): String {
    val m = ANSWER_LINE.findAll(text).lastOrNull() ?: return text
    val value = m.groups[2] ?: return text
    if (num(value.value).compareTo(num(wrong)) != 0) return text
    return text.replaceRange(value.range, right)
}

// ── Formula-first answers ───────────────────────────────────────────────────

private val FORMULA_LINE = Regex("""^\**\s*(?:$FORMULA_LABEL|सूत्र)\s*\**\s*[:：]\s*(.+)$""", RegexOption.IGNORE_CASE)
private val ANSWER_LABEL_LINE = Regex("""^\**\s*(?:final\s+answer|$ANSWER_LABEL|उत्तर|जवाब)\s*\**\s*[:：]\s*(.*)$""", RegexOption.IGNORE_CASE)
private val FIRST_NUMBER = Regex("""\d[\d,]*(?:\.\d+)?""")
private val LAST_RESULT = Regex("""=\s*₹?\s*(\d[\d,]*(?:\.\d+)?)""")

/**
 * If the reply has a "Formula: <expression>" line the app can evaluate, the
 * result is exact: the formula line shows it, the "Answer:" line carries it
 * (keeping the model's unit words), and the model's own working is kept only
 * when it ends on the same number — device test 2026-09-30: correct working
 * "2500 - 750 = 1750" followed by "Answer: 17500 ml". Returns null when there
 * is no usable formula (the caller then falls back to [verifyWorking]).
 */
internal fun applyFormula(text: String): String? {
    val lines = text.trimEnd().lines()
    val fIdx = lines.indexOfFirst { FORMULA_LINE.matchEntire(it.trim()) != null }
    if (fIdx < 0) return null
    val rawExpr = FORMULA_LINE.matchEntire(lines[fIdx].trim())!!.groupValues[1]
        .substringBefore('=')
        .replace("`", "").replace("₹", "")
        .let { stripThousandsSeparators(it) }
        .trim().trimEnd('.', '।')
    val value = com.saarthi.core.inference.math.ExactExpression.evaluate(rawExpr) ?: return null
    val result = render(value)

    val aIdx = lines.indexOfLast { ANSWER_LABEL_LINE.matchEntire(it.trim()) != null }
    val answerLine = if (aIdx > fIdx) {
        val body = ANSWER_LABEL_LINE.matchEntire(lines[aIdx].trim())!!.groupValues[1]
        val num = FIRST_NUMBER.find(body)
        val newBody = if (num != null) body.replaceRange(num.range, result) else result
        "$ANSWER_LABEL: $newBody"
    } else {
        "$ANSWER_LABEL: $result"
    }
    // Keep the model's working only if it lands on the exact result.
    val working = lines.subList(fIdx + 1, if (aIdx > fIdx) aIdx else lines.size).filter { it.isNotBlank() }
    val workingEnd = working.flatMap { LAST_RESULT.findAll(it).toList() }.lastOrNull()?.groupValues?.get(1)
    val keepWorking = working.isNotEmpty() && workingEnd != null &&
        num(workingEnd).compareTo(value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()) == 0

    return buildString {
        lines.subList(0, fIdx).filter { it.isNotBlank() }.forEach { appendLine(it) }
        appendLine("$FORMULA_LABEL: ${rawExpr.trim()} = $result")
        if (keepWorking) working.forEach { appendLine(it) }
        append(answerLine)
    }
}

