package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreInferenceGatesTest {

    @Test
    fun urgentSafetyGate_triggers_on_poison_intent() {
        assertNotNull(UrgentSafetyGate.replyFor("I want to drink poison"))
        assertNotNull(UrgentSafetyGate.replyFor("Can I eat pesticide"))
    }

    @Test
    fun urgentSafetyGate_ignores_benign_poison_ivy() {
        assertNull(UrgentSafetyGate.replyFor("How do I treat poison ivy rash"))
    }

    @Test
    fun mathGate_evaluates_simple_expression() {
        val reply = DeterministicMathGate.replyFor("What is 15 * 3?")
        assertNotNull(reply)
        assertTrue(reply!!.contains("45"))
    }

    @Test
    fun mathGate_handles_precedence() {
        val reply = DeterministicMathGate.replyFor("calculate 2+3*4")
        assertNotNull(reply)
        assertTrue(reply!!.contains("14"))
    }

    @Test
    fun mathGate_square_phrase_is_ambiguous() {
        val reply = DeterministicMathGate.replyFor("What is 2 + 2 square?")
        assertNotNull(reply)
        assertTrue(reply!!.contains("6"))
        assertTrue(reply.contains("16"))
    }

    @Test
    fun mathEvaluator_rejects_invalid() {
        assertNull(MathExpressionEvaluator.evaluate("2++2"))
        assertNull(MathExpressionEvaluator.evaluate("abc"))
    }

    @Test
    fun mathEvaluator_power() {
        assertEquals(8.0, MathExpressionEvaluator.evaluate("2^3")!!, 0.001)
    }
}
