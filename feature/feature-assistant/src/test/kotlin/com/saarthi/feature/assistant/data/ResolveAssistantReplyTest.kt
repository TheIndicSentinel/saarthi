package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolveAssistantReply] is the reliability guard that guarantees the chat
 * never shows a blank "broken" bubble — the worst symptom for a paid app, and
 * the one the production logs surfaced (2–3 token / <1 tok/s empty replies under
 * device memory pressure, plus errors that blanked the bubble on completion).
 */
class ResolveAssistantReplyTest {

    @Test
    fun `real reply is returned unchanged`() {
        val out = resolveAssistantReply(
            cleanedText = "PM-KISAN gives eligible farmers Rs 6000 a year.",
            isCancelled = false, isError = false, partialVisible = "",
        )
        assertEquals("PM-KISAN gives eligible farmers Rs 6000 a year.", out)
    }

    @Test
    fun `empty normal completion gives an actionable memory hint, never blank`() {
        val out = resolveAssistantReply(
            cleanedText = "   ",
            isCancelled = false, isError = false, partialVisible = "",
        )
        assertTrue("Must not be blank", out.isNotBlank())
        assertTrue("Should hint at a lighter model. Got: $out",
            out.contains("lighter model", ignoreCase = true))
    }

    @Test
    fun `error before any token preserves the catch message, not a blank bubble`() {
        val out = resolveAssistantReply(
            cleanedText = "",
            isCancelled = false, isError = true,
            partialVisible = "Model not ready. Please pick a model in Setup.",
        )
        assertEquals("Model not ready. Please pick a model in Setup.", out)
    }

    @Test
    fun `error with no surfaced message falls back to a generic notice`() {
        val out = resolveAssistantReply(
            cleanedText = "",
            isCancelled = false, isError = true, partialVisible = "",
        )
        assertTrue("Must not be blank", out.isNotBlank())
        assertTrue("Should be a generic try-again notice. Got: $out",
            out.contains("try again", ignoreCase = true))
    }

    @Test
    fun `cancel keeps partial streamed text`() {
        val out = resolveAssistantReply(
            cleanedText = "",
            isCancelled = true, isError = false,
            partialVisible = "Here are the first three steps",
        )
        assertEquals("Here are the first three steps", out)
    }

    @Test
    fun `cancel with nothing streamed shows a brief stopped note`() {
        val out = resolveAssistantReply(
            cleanedText = "",
            isCancelled = true, isError = false, partialVisible = "",
        )
        assertEquals("Stopped.", out)
    }

    @Test
    fun `real text wins even when flags indicate cancel or error`() {
        // If the model actually produced content, show it regardless of how the
        // stream terminated (e.g. a clean reply followed by a late cancel).
        val out = resolveAssistantReply(
            cleanedText = "Done.",
            isCancelled = true, isError = true, partialVisible = "ignored",
        )
        assertEquals("Done.", out)
    }

    @Test
    fun `the empty-completion message is genuinely non-empty`() {
        // Guards against a future edit accidentally emptying the fallback string.
        val out = resolveAssistantReply("", isCancelled = false, isError = false, partialVisible = "")
        assertFalse(out.isBlank())
    }
}
