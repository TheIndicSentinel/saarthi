package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ── Multi-language robustness ──────────────────────────────────────────
    // These are the regression guards for the v1.0.21 bug where Telugu /
    // Bengali / Tamil sessions leaked raw "[SAARTHI_REMINDER text = ""...]"
    // text into the chat bubble because the model emitted the marker with
    // spaces around `=`, empty values, or curly quotes.

    @Test
    fun stripForDisplay_removes_marker_with_spaces_around_equals() {
        val raw = "Sure, ok.\n[SAARTHI_REMINDER text = \"breakfast\" delay_minutes = \"5\"]"
        val out = ResponseMarkerParser.stripForDisplay(raw)
        assertEquals("Sure, ok.", out)
    }

    @Test
    fun stripForDisplay_removes_malformed_empty_marker() {
        // Telugu-session reproducer — model copied the template verbatim.
        val raw = "ok\n[SAARTHI_REMINDER text = \"\" delay_minutes = \"\"]"
        val out = ResponseMarkerParser.stripForDisplay(raw)
        assertEquals("ok", out)
    }

    @Test
    fun stripForDisplay_removes_marker_with_curly_quotes() {
        // Indic-language keyboards routinely produce smart quotes.
        val raw = "ok [SAARTHI_REMINDER text=“breakfast” delay_minutes=“5”]"
        val out = ResponseMarkerParser.stripForDisplay(raw)
        assertEquals("ok", out)
    }

    @Test
    fun stripForDisplay_removes_yaml_colon_form_marker_block() {
        // Non-English reproducer: the model dumped a "marker:" YAML block with
        // colon-form fields instead of the [SAARTHI_*] bracket syntax. None of
        // the equals-based strippers caught it, so it leaked into the bubble.
        val raw = "मैं आपकी मदद कर सकता हूँ।\n" +
            "marker:\n" +
            "text: \"help_offered\"\n" +
            "delay_minutes: 0\n" +
            "time: \"11:07 2026\"\n" +
            "key: \"help_offered\"\n" +
            "value: \"Can help with various tasks\""
        val out = ResponseMarkerParser.stripForDisplay(raw, streaming = false)
        assertEquals("मैं आपकी मदद कर सकता हूँ।", out)
    }

    @Test
    fun stripForDisplay_removes_partial_or_broken_marker() {
        // Belt-and-braces: even a marker with a missing field must not leak.
        val raw = "ok [SAARTHI_REMINDER text=\"x\"]"
        val out = ResponseMarkerParser.stripForDisplay(raw)
        assertEquals("ok", out)
    }

    @Test
    fun parse_rejects_placeholder_text_value() {
        val raw = "[SAARTHI_REMINDER text=\"...\" delay_minutes=\"5\"]"
        val result = ResponseMarkerParser.parse(raw)
        assertTrue("Reminder with placeholder text MUST NOT schedule", result.reminders.isEmpty())
    }

    @Test
    fun parse_rejects_placeholder_minutes_value() {
        val raw = "[SAARTHI_REMINDER text=\"lunch\" delay_minutes=\"N\"]"
        val result = ResponseMarkerParser.parse(raw)
        assertTrue("Reminder with placeholder delay MUST NOT schedule", result.reminders.isEmpty())
    }

    @Test
    fun parse_rejects_placeholder_time_value() {
        val raw = "[SAARTHI_REMINDER text=\"lunch\" time=\"HH:MM\"]"
        val result = ResponseMarkerParser.parse(raw)
        assertTrue("Reminder with placeholder time MUST NOT schedule", result.reminders.isEmpty())
    }

    @Test
    fun parse_accepts_valid_marker_even_with_spaces_around_equals() {
        // Tolerance fix — model emits valid value but with spaces around `=`.
        val raw = "[SAARTHI_REMINDER text = \"drink water\" delay_minutes = \"30\"]"
        val result = ResponseMarkerParser.parse(raw)
        assertEquals(1, result.reminders.size.toLong())
        assertEquals("drink water", result.reminders[0].text)
        assertEquals(Integer.valueOf(30), result.reminders[0].delayMinutes)
    }

    @Test
    fun parse_rejects_placeholder_memory_values() {
        val raw = "[SAARTHI_MEMORY key=\"short_key\" value=\"value\"]"
        val result = ResponseMarkerParser.parse(raw)
        assertTrue("Memory with placeholder values MUST NOT save", result.memories.isEmpty())
    }

    @Test
    fun stripAll_removes_Gemma_unused_token_leak() {
        val raw = "Hello there <unused0>"
        val out = ResponseMarkerParser.stripForDisplay(raw)
        assertEquals("Hello there", out)
    }

    @Test
    fun stripAll_removes_Gemma_pad_and_unk_tokens() {
        val raw = "Reply<pad><unk>"
        val out = ResponseMarkerParser.stripForDisplay(raw)
        assertEquals("Reply", out)
    }
}
