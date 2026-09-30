package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathBenchmarkTest {

    private val gst = MATH_BENCH_CASES.first { it.id == 17 }

    @Test
    fun `the answer line decides the final answer`() {
        val reply = "20% of 2500 = 500\n18% of 2000 = 360\nAnswer: ₹2,360"
        val r = scoreMathReply(gst, reply, reply)
        assertEquals("2,360", r.finalAnswer)
        assertTrue(r.finalCorrect)
        assertEquals(0, r.corrections)
        assertFalse(r.collapsed)
    }

    @Test
    fun `wrong first then corrected is counted as a correction`() {
        val raw = "The final price will be ₹4,100.\nWait, let me re-read the question.\nThe final price will be ₹2,360."
        val r = scoreMathReply(gst, raw, raw)
        assertTrue(r.finalCorrect)
        assertEquals(1, r.corrections)
    }

    @Test
    fun `runaway numbers are flagged as collapse`() {
        val raw = "क्षमा करें, मैंने गणना में गलती की है। सही उत्तर ₹10,0,000 होगा।"
        val r = scoreMathReply(gst, raw, raw)
        assertFalse(r.finalCorrect)
        assertTrue(r.collapsed)
    }

    @Test
    fun `summary reports totals and per-case consistency`() {
        val results = listOf(
            MathBenchResult(17, "2360", true, true, 0, false),
            MathBenchResult(17, "4100", false, true, 1, false),
        )
        val summary = summarizeMathBenchmark(results, runsPerQuestion = 2)
        assertTrue(summary.contains("Final answer correct: 1/2 (50%)"))
        assertTrue(summary.contains("Consistent across runs: 0/1"))
        assertTrue(summary.contains("✗ #17  1/2  answers=2360,4100"))
    }
}
