package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Formula-first answers — failures are from the 2026-09-30 device run. */
class MathFormulaTest {

    @Test
    fun `the formula result replaces a corrupted answer and wrong working`() {
        val reply = "Formula: 2400 - 2400/3 - (2400 - 2400/3)*25/100\n" +
            "1. 2400 × 1/3 = 800\n" +
            "2. 16000 × 5 = 8030\n" +
            "Answer: 72 रुपये"
        assertEquals(
            "Formula: 2400 - 2400/3 - (2400 - 2400/3)*25/100 = 1200\nAnswer: 1200 रुपये",
            applyFormula(reply),
        )
    }

    @Test
    fun `working that lands on the exact result is kept`() {
        val reply = "Formula: 2500 - 750\n2500 ml - 750 ml = 1750 ml\nAnswer: 17500 ml"
        assertEquals(
            "Formula: 2500 - 750 = 1750\n2500 ml - 750 ml = 1750 ml\nAnswer: 1750 ml",
            applyFormula(reply),
        )
    }

    @Test
    fun `multi-step word problems from the device run`() {
        assertEquals("30", answerOf("Formula: 250*40/100*30/100\nAnswer: 240"))
        assertEquals("3.5", answerOf("Formula: 60/40 + (180-60)/60\nAnswer: 4.3333 घंटे"))
        assertEquals("4500", answerOf("Formula: 18000*(100-35-25-15)/100\nAnswer: ऋणात्मक राशि"))
        assertEquals("200", answerOf("Formula: 250/1.25\nAnswer: 1200"))
        assertEquals("1.225", answerOf("Formula: 3.5*(100-40-25)/100\nAnswer: 1.77 एकड़"))
        assertEquals("3600", answerOf("Formula: 5,000*0.8*0.9\nAnswer: 10.00000000000"))
    }

    @Test
    fun `an answer line is added when missing and text before the formula is kept`() {
        assertEquals(
            "राहुल के पास ₹5000 हैं।\nFormula: 5000*80/100 = 4000\nAnswer: 4000",
            applyFormula("राहुल के पास ₹5000 हैं।\nFormula: 5000*80/100"),
        )
    }

    @Test
    fun `no usable formula falls back`() {
        assertNull(applyFormula("20 × 45 = 900\nAnswer: 900"))
        assertNull(applyFormula("Formula: speed × time\nAnswer: 180"))
    }

    private fun answerOf(reply: String): String? =
        applyFormula(reply)?.lines()?.last()?.removePrefix("Answer: ")?.substringBefore(' ')
}
