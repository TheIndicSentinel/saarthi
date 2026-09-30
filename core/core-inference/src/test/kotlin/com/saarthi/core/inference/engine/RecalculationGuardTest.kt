package com.saarthi.core.inference.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transcripts are trimmed from the 2026-09-30 device runs. */
class RecalculationGuardTest {

    private val oneCorrection = "The final price will be ₹4,100.\n" +
        "Final Price: ₹2,000 plus ₹360 is ₹2,360.\n" +
        "Wait, let me re-read the question carefully.\n" +
        "The final price will be ₹2,360."

    @Test
    fun `a single correction is allowed`() {
        assertFalse(RecalculationGuard.shouldStop(oneCorrection))
        assertEquals(oneCorrection, RecalculationGuard.trimAtSecondCorrection(oneCorrection))
    }

    @Test
    fun `a second correction stops and is trimmed away`() {
        val runaway = "कुल लाभ ₹300 है।\n" +
            "क्षमा करें, मैंने गणना में गलती की है। सही लाभ ₹30 है।\n" +
            "यह भी गलत है।\n" +
            "चलिए, सही गणना करते हैं।"
        assertTrue(RecalculationGuard.shouldStop(runaway))
        assertEquals(
            "कुल लाभ ₹300 है।\nक्षमा करें, मैंने गणना में गलती की है। सही लाभ ₹30 है।",
            RecalculationGuard.trimAtSecondCorrection(runaway),
        )
    }

    @Test
    fun `one correction plus number-only rewrites of a line stops`() {
        val text = "Wait, let me recalculate.\n" +
            "शॉपिंग पर खर्च = ₹5000 का 20% = ₹10000 (यह गलत)\n" +
            "शॉपिंग पर खर्च = ₹5000 का 20% = ₹100000 (यह गलत)\n" +
            "शॉपिंग पर खर्च = ₹50000 का 20% = ₹100000 (यह गलत)\n"
        assertTrue(RecalculationGuard.shouldStop(text))
    }

    @Test
    fun `ordinary answers with the word wrong or tables are untouched`() {
        val quiz = "यह कथन गलत है। दूसरा कथन भी गलत है क्योंकि पृथ्वी गोल है।"
        assertFalse(RecalculationGuard.shouldStop(quiz))
        val table = "| Item | Price |\n| Pen | 15 |\n| Book | 45 |\n| Bag | 450 |"
        assertFalse(RecalculationGuard.shouldStop(table))
    }
}
