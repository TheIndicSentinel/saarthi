package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases are the on-device test set from 2026-09-30 (Hindi / Hinglish / English). */
class MathAssistTest {

    private fun result(message: String): String? = verifiedCalculation(message)?.result

    // ── Number words ─────────────────────────────────────────────────────

    @Test
    fun `hindi and english number words become digits`() {
        assertEquals("35 में 12 जोड़ो", normalizeNumberWords("पैंतीस में 12 जोड़ो"))
        assertEquals("250 में 175 जोड़ो।", normalizeNumberWords("दो सौ पचास में 175 जोड़ो।"))
        assertEquals("1200", normalizeNumberWords("एक हज़ार दो सौ"))
        assertEquals("2 + 5 = ?", normalizeNumberWords("2 + five = ?"))
        assertEquals("35 plus 12", normalizeNumberWords("thirty-five plus twelve"))
    }

    @Test
    fun `a lone ek or one stays a word`() {
        assertEquals("एक किसान के पास 3.5 acres", normalizeNumberWords("एक किसान के पास 3.5 acres"))
        assertEquals("If one pen costs ₹15", normalizeNumberWords("If one pen costs ₹15"))
        assertEquals("100 रुपये", normalizeNumberWords("एक सौ रुपये"))
    }

    // ── Verified calculations (the failures from the device test) ───────

    @Test
    fun `explicit calculations are computed exactly`() {
        assertEquals("47", result("पैंतीस में 12 जोड़ो"))
        assertEquals("27", result("What is 50 - 23?"))
        assertEquals("36", result("बारह को 3 से multiply karo."))
        assertEquals("7", result("2 + five = ?"))
        assertEquals("19", result("अट्ठाईस में से 9 घटाओ।"))
        assertEquals("75", result("3/4 of 100 kitna hai?"))
        assertEquals("425", result("दो सौ पचास में 175 जोड़ो।"))
        assertEquals("150", result("15% of 1000"))
        assertEquals("12", result("96 divided by 8"))
        assertEquals("2.5", result("5 ÷ 2"))
    }

    @Test
    fun `word problems and extra numbers are left to the model`() {
        assertNull(verifiedCalculation("Mere paas 4 apples hain, aur mummy ne 3 aur diye. Total kitne apples hue?"))
        assertNull(verifiedCalculation("A shopkeeper buys 20 notebooks at ₹45 each and sells each for ₹60. Total profit kitna hai?"))
        assertNull(verifiedCalculation("Ek train 240 km distance 4 hours mein cover karti hai. Same speed par 7 hours mein kitna distance?"))
        assertNull(verifiedCalculation("5 ÷ 0"))
        assertNull(verifiedCalculation("Tell me about Delhi"))
    }

    // ── Numeric-question detection ───────────────────────────────────────

    @Test
    fun `numeric questions are detected`() {
        assertTrue(isNumericQuestion("Ek shop mein 250 chocolates hain. 87 sell ho gayi. Kitni bachhi?"))
        assertTrue(isNumericQuestion("A train 60 km/h ki speed se 3 hours travel karti hai. Kitna distance cover karegi?"))
        assertTrue(isNumericQuestion("₹1,200 par 10% discount hai. Final price kya hoga?"))
        assertTrue(isNumericQuestion("100 mein se ₹35 kharch kiye. Kitne rupees bache"))
    }

    @Test
    fun `ordinary chat with numbers is not a math question`() {
        assertFalse(isNumericQuestion("My name is Arjun"))
        assertFalse(isNumericQuestion("I have 2 kids aged 5 and 8"))
        assertFalse(isNumericQuestion("Suggest 5-6 activities for 10 people"))
        assertFalse(isNumericQuestion("The train leaves at 5:30 and reaches Pune at 9"))
    }

    // ── User turn ────────────────────────────────────────────────────────

    @Test
    fun `non-numeric messages pass through unchanged`() {
        val msg = "Tell me a joke about cricket"
        assertEquals(msg, mathAwareUserTurn(msg))
    }

    @Test
    fun `numeric turns carry the verified result and a work-first instruction`() {
        val turn = mathAwareUserTurn("पैंतीस में 12 जोड़ो")
        assertTrue(turn.startsWith("पैंतीस में 12 जोड़ो"))
        assertTrue(turn.contains("35 + 12 = 47"))
        assertTrue(turn.contains("\"Answer: <result with unit>\""))
        assertTrue(turn.contains("never restart"))
        val wordProblem = mathAwareUserTurn("Ek shop mein 250 chocolates hain. 87 sell ho gayi. Kitni bachhi?")
        assertTrue(wordProblem.contains("\"Answer: <result with unit>\""))
        assertFalse(wordProblem.contains("computed this exactly"))
    }

    @Test
    fun `numeric turns drop thousands separators from the question`() {
        val turn = mathAwareUserTurn("A family spends ₹18,000 per month. Rent is 35%. Kitna bachta hai?")
        assertTrue(turn.startsWith("A family spends ₹18000 per month."))
        assertEquals("₹120000 and 1500", stripThousandsSeparators("₹1,20,000 and 1,500"))
        assertEquals("1,2", stripThousandsSeparators("1,2"))
    }

    // ── Expression evaluator (notation cases from the 2026-09-30 Hindi run) ──

    @Test
    fun `notation follows operator precedence`() {
        assertEquals("6", result("2+2^2"))
        assertEquals("6", result("2+(2)^2"))
        assertEquals("6", result("2+(2)2"))
        assertEquals("16", result("(2+2)^2"))
        assertEquals("16", result("2 + 2 whole square is equal to"))
        assertEquals("6", result("2 plus 2 square kitna hai"))
        assertEquals("540", result("पैंतालीस × 12 = ?"))
        assertEquals("8", result("2 × 2²"))
        assertEquals("16", result("(2 × 2)²"))
        assertEquals("4", result("√16"))
    }

    @Test
    fun `evaluator rejects what it cannot compute exactly`() {
        assertNull(evaluateExpression("2 +"))
        assertNull(evaluateExpression("4 / 0"))
        assertNull(evaluateExpression("2^0.5"))
        assertNull(evaluateExpression("2^1000"))
    }

    @Test
    fun `dates and sentences are not treated as expressions`() {
        assertNull(verifiedCalculation("Meeting on 2026-09-30"))
        assertNull(verifiedCalculation("I scored 45 + 12 bonus points in the game yesterday"))
    }
}

