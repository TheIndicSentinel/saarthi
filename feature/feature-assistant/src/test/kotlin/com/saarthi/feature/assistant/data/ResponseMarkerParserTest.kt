package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseMarkerParserTest {

    @Test
    fun `parse extracts memories correctly`() {
        val raw = "Sure! [SAARTHI_MEMORY key=\"test_key\" value=\"test_value\"]"
        val result = ResponseMarkerParser.parse(raw)
        
        assertEquals("Sure!", result.cleanText)
        assertEquals(1, result.memories.size)
        assertEquals("test_key", result.memories[0].key)
        assertEquals("test_value", result.memories[0].value)
    }

    @Test
    fun `parse extracts relative reminders correctly`() {
        val raw = "I'll remind you. [SAARTHI_REMINDER text=\"Call Mom\" delay_minutes=\"30\"]"
        val result = ResponseMarkerParser.parse(raw)
        
        assertEquals("I'll remind you.", result.cleanText)
        assertEquals(1, result.reminders.size)
        assertEquals("Call Mom", result.reminders[0].text)
        assertEquals(30, result.reminders[0].delayMinutes)
    }

    @Test
    fun `parse extracts absolute reminders correctly`() {
        val raw = "Okay. [SAARTHI_REMINDER text=\"Meeting\" time=\"14:00\"]"
        val result = ResponseMarkerParser.parse(raw)
        
        assertEquals("Okay.", result.cleanText)
        assertEquals(1, result.reminders.size)
        assertEquals("Meeting", result.reminders[0].text)
        assertEquals("14:00", result.reminders[0].time)
    }

    @Test
    fun `stripAll removes special model turn markers`() {
        val raw = "<start_of_turn>user\nHello<end_of_turn>\n<start_of_turn>model\nHi"
        val result = ResponseMarkerParser.parse(raw)

        assertEquals("user\nHello\nmodel\nHi", result.cleanText)
    }

    // ── Identity rewriter ───────────────────────────────────────────────────

    @Test
    fun rewriteIdentity_replaces_I_am_Gemma_4_with_I_am_Saarthi() {
        val raw = "I am Gemma 4, here to help you today."
        assertEquals(
            "I am Saarthi, here to help you today.",
            ResponseMarkerParser.rewriteIdentity(raw),
        )
    }

    @Test
    fun rewriteIdentity_handles_apostrophe_contraction() {
        val raw = "I'm Gemma3n, ready to assist."
        assertEquals(
            "I'm Saarthi, ready to assist.",
            ResponseMarkerParser.rewriteIdentity(raw),
        )
    }

    @Test
    fun rewriteIdentity_rewrites_large_language_model_claim() {
        val raw = "I am a large language model, here for your questions."
        assertEquals(
            "I am Saarthi, here for your questions.",
            ResponseMarkerParser.rewriteIdentity(raw),
        )
    }

    @Test
    fun rewriteIdentity_strips_developed_by_Google_DeepMind() {
        val raw = "Hello! I am Gemma 4, developed by Google DeepMind. How can I help?"
        val out = ResponseMarkerParser.rewriteIdentity(raw)
        assertEquals("Hello! I am Saarthi. How can I help?", out)
    }

    @Test
    fun rewriteIdentity_leaves_legitimate_Google_mentions_alone() {
        val raw = "You can search for that on Google."
        assertEquals(raw, ResponseMarkerParser.rewriteIdentity(raw))
    }

    @Test
    fun rewriteIdentity_leaves_legitimate_LLM_discussion_alone() {
        // Only neutralise self-identification, never the term itself.
        val raw = "An LLM is a type of AI model."
        assertEquals(raw, ResponseMarkerParser.rewriteIdentity(raw))
    }

    @Test
    fun rewriteIdentity_is_a_no_op_for_normal_text() {
        val raw = "Sure, I will remind you about lunch in 5 minutes."
        assertEquals(raw, ResponseMarkerParser.rewriteIdentity(raw))
    }

    @Test
    fun parse_runs_identity_rewrite_on_cleanText() {
        val raw = "I am Gemma 4. Hi there."
        val result = ResponseMarkerParser.parse(raw)
        assertEquals("I am Saarthi. Hi there.", result.cleanText)
    }
}
