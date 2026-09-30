package com.saarthi.core.inference.engine

/**
 * Detects a small model "recalculating" itself into garbage — device tests
 * 2026-09-30: "क्षमा करें, मैंने गणना में गलती की है…" followed by ₹10,000,
 * ₹100,000, ₹10,0,000 until the length limit; "Wait, let me re-read…" then
 * corrupted numbers. [isRepetitionLoop] can't catch it: each cycle has
 * different numbers, so nothing repeats back-to-back.
 *
 * Policy: ONE correction is allowed (it often fixes the answer — the English
 * GST run did); the SECOND stops generation, and the reply is trimmed to just
 * before it. Phrases are specific self-correction wording, not plain "गलत"
 * ("wrong"), which legitimate answers use.
 */
object RecalculationGuard {

    private val CORRECTION_PHRASES = listOf(
        // English
        "wait, let me", "let me re-read", "let me reread", "let me recalculate", "let me re-calculate",
        "let's recalculate", "let's re-calculate", "i made a mistake", "i made an error", "my mistake",
        "sorry, i made", "apologies, i made", "correction:",
        // Hindi
        "मैंने गणना में गलती", "गणना में गलती की", "मुझसे गलती", "मैंने पहले गलत", "यह भी गलत है",
        "फिर से गणना", "दोबारा गणना", "सही गणना करते हैं", "क्षमा करें, मैंने", "माफ़ कीजिए, मैंने",
        "माफ कीजिए, मैंने", "माफ़ करना, मैंने", "माफ करना, मैंने", "क्षमाफ़ कीजिए",
    )

    private val SENTENCE_END = charArrayOf('\n', '।', '.', '!', '?')

    /** Start of the sentence containing [offset]; a '.' between digits ("1.5") is not a sentence end. */
    private fun sentenceStart(text: String, offset: Int): Int {
        var i = offset - 1
        while (i >= 0) {
            val c = text[i]
            val decimalPoint = c == '.' && i > 0 && text[i - 1].isDigit() && text.getOrNull(i + 1)?.isDigit() == true
            if (c in SENTENCE_END && !decimalPoint) return i + 1
            i--
        }
        return 0
    }

    /**
     * Start offset of each sentence that contains a correction phrase, in
     * order. One sentence counts once — "क्षमा करें, मैंने गणना में गलती की है"
     * matches several overlapping phrases but is a single correction.
     */
    private fun correctionOffsets(text: String): List<Int> {
        val lower = text.lowercase()
        return CORRECTION_PHRASES
            .flatMap { p -> Regex(Regex.escape(p)).findAll(lower).map { it.range.first }.toList() }
            .map { offset -> sentenceStart(text, offset) }
            .distinct()
            .sorted()
    }

    /** A working line repeated with only its numbers changed ("कुल खरीद मूल्य: 20 × ₹45 = ₹910000"). */
    private fun hasNumberOnlyVariantLines(text: String): Boolean {
        val masked = text.lines()
            .map { it.trim() }
            .filter { line -> line.length >= 25 && line.any { it.isLetter() } && line.any { it.isDigit() } }
            .map { it.replace(Regex("""[\d.,]+"""), "#") }
        return masked.groupingBy { it }.eachCount().values.any { it >= 3 }
    }

    /** Number of self-corrections (one per sentence) in [text] — used by the maths benchmark. */
    fun correctionCount(text: String): Int = correctionOffsets(text).size

    /** True when generation should stop: a second correction, or one plus number-only rewrites. */
    fun shouldStop(text: String): Boolean {
        val corrections = correctionOffsets(text).size
        if (corrections >= 2) return true
        return corrections == 1 && hasNumberOnlyVariantLines(text)
    }

    /**
     * [text] cut back to the sentence / line start of the SECOND correction,
     * so the saved reply keeps the answer and at most one correction. Unchanged
     * when there are fewer than two corrections.
     */
    fun trimAtSecondCorrection(text: String): String {
        val offsets = correctionOffsets(text)
        if (offsets.size < 2) return text
        // Offsets are already sentence starts.
        return text.substring(0, offsets[1]).trimEnd()
    }
}
