package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreInferenceGatesTest {

    private val en = SupportedLanguage.ENGLISH

    @Test
    fun urgentSafetyGate_triggers_on_poison_intent() {
        assertNotNull(UrgentSafetyGate.replyFor("I want to drink poison", en))
        assertNotNull(UrgentSafetyGate.replyFor("Can I eat pesticide", en))
    }

    @Test
    fun urgentSafetyGate_ignores_benign_poison_ivy() {
        assertNull(UrgentSafetyGate.replyFor("How do I treat poison ivy rash", en))
    }

    @Test
    fun urgentSafetyGate_uses_localized_copy_for_hindi() {
        val reply = UrgentSafetyGate.replyFor("I want to drink poison", SupportedLanguage.HINDI)
        assertNotNull(reply)
        assertTrue(reply!!.contains("112"))
        assertTrue(reply.contains("जहर") || reply.contains("विष"))
    }

    @Test
    fun mathGate_evaluates_simple_expression() {
        val reply = DeterministicMathGate.replyFor("What is 15 * 3?", en)
        assertNotNull(reply)
        assertTrue(reply!!.contains("45"))
    }

    @Test
    fun mathGate_handles_precedence() {
        val reply = DeterministicMathGate.replyFor("calculate 2+3*4", en)
        assertNotNull(reply)
        assertTrue(reply!!.contains("14"))
    }

    @Test
    fun mathGate_square_phrase_is_ambiguous() {
        val reply = DeterministicMathGate.replyFor("What is 2 + 2 square?", en)
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
