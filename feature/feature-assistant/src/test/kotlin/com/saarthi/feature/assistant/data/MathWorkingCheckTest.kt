package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Steps are taken from the 2026-09-30 device transcripts. */
class MathWorkingCheckTest {

    @Test
    fun `a wrong step result is corrected, with units`() {
        assertEquals(
            "4. Total time: 1.5 hours + 2 hours = 3.5 hours",
            verifyWorking("4. Total time: 1.5 hours + 2 hours = 3 hours"),
        )
    }

    @Test
    fun `the answer line follows the corrected last step`() {
        val reply = "1. 60 / 40 = 1.5\n2. 120 / 60 = 2\n3. 1.5 + 2 = 3\nAnswer: 3 hours"
        assertEquals(
            "1. 60 / 40 = 1.5\n2. 120 / 60 = 2\n3. 1.5 + 2 = 3.5\nAnswer: 3.5 hours",
            verifyWorking(reply),
        )
    }

    @Test
    fun `percent steps and currency are checked`() {
        assertEquals("GST: 18% of ₹2000 = ₹360", verifyWorking("GST: 18% of ₹2000 = ₹99990"))
        assertEquals("₹5000 का 20% = ₹1000", verifyWorking("₹5000 का 20% = ₹1000"))
    }

    @Test
    fun `correct steps, rounding and chains are left alone`() {
        val ok = "20 × 45 = 900\n1200 - 900 = 300\n200 / 60 = 3.33\nAnswer: ₹300"
        assertEquals(ok, verifyWorking(ok))
        val chain = "2 + 3 + 4 = 9"
        assertEquals(chain, verifyWorking(chain))
        val bracket = "(100 ÷ 4) × 3 = 75"
        assertEquals(bracket, verifyWorking(bracket))
        val prose = "I was born in 1990 and have 2 kids."
        assertEquals(prose, verifyWorking(prose))
    }
}
