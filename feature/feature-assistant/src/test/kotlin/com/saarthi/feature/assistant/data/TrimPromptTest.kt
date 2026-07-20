package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [trimPrompt] is the safety net that fires when a single-turn prompt
 * (system + user message, no `<start_of_turn>` markers) exceeds the model
 * tier's char budget. Its `pinnedTail` mechanism used to only protect the
 * raw language directive — losing persona behaviour rules and response-style
 * constraints in the process, which is what caused Saarthi's default persona
 * and Pandit ji to give unexpectedly long, off-persona replies once a long
 * conversation's memory+recap pushed the prompt over budget. These tests
 * lock in that pinnedTail is preserved verbatim, and that the no-overflow
 * fast path never touches the prompt at all.
 */
class TrimPromptTest {

    @Test
    fun `prompt within budget is returned unchanged`() {
        val prompt = "System instructions.\n\nUser message."
        val out = trimPrompt(prompt, budget = 1000, pinnedTail = "User message.")
        assertEquals(prompt, out)
    }

    @Test
    fun `overflow on a single-turn prompt preserves the pinned tail verbatim`() {
        val criticalTail =
            "PERSONA BEHAVIOUR (apply on EVERY reply, in this order of priority):\n" +
                "- Keep replies short.\n" +
                "REPLY-STYLE CONSTRAINTS (the user has set these in Settings — honour them):\n" +
                "Keep it brief.\n\n" +
                "Always reply in Hindi."
        val userMessage = "Bhagwan Ganesh ke baare mein bataiye."
        val pinnedTail = "$criticalTail\n\n$userMessage"

        val bloatedSystemPrefix = "SYSTEM PROMPT PREFIX ".repeat(500)  // way over any real budget
        val prompt = "$bloatedSystemPrefix\n\n$pinnedTail"

        val budget = 500
        val out = trimPrompt(prompt, budget = budget, pinnedTail = pinnedTail)

        assertTrue(
            "Truncated prompt must still end with the exact pinned tail (persona + style + language + user message). Got:\n$out",
            out.endsWith(pinnedTail),
        )
        assertTrue(
            "Truncated prompt must include the persona behaviour rule",
            out.contains("Keep replies short."),
        )
        assertTrue(
            "Truncated prompt must include the response-style constraint",
            out.contains("Keep it brief."),
        )
        assertTrue(
            "Truncated prompt must include the resolved language directive",
            out.contains("Always reply in Hindi."),
        )
    }

    @Test
    fun `overflow with English-override tail keeps the resolved English directive, not app language`() {
        // Simulates: app language is Hindi, but Response Style forced
        // English — ChatRepositoryImpl resolves effectiveLanguage BEFORE
        // building criticalTail, so the pinned tail here must carry the
        // English directive, never the raw Hindi one.
        val criticalTail = "PERSONA BEHAVIOUR (apply on EVERY reply, in this order of priority):\n" +
            "- Keep replies short.\n\n" +
            "Always reply in English."
        val userMessage = "Tell me about Ganesh Chaturthi."
        val pinnedTail = "$criticalTail\n\n$userMessage"

        val bloatedSystemPrefix = "SYSTEM PROMPT PREFIX ".repeat(500)
        val prompt = "$bloatedSystemPrefix\n\n$pinnedTail"

        val out = trimPrompt(prompt, budget = 500, pinnedTail = pinnedTail)

        assertTrue("Must end with the pinned English-override tail", out.endsWith(pinnedTail))
        assertTrue("Must contain the resolved English directive", out.contains("Always reply in English."))
        assertTrue(
            "Must NOT contain a stray Hindi directive that wasn't part of the pinned tail",
            !out.contains("Always reply in Hindi."),
        )
    }

    @Test
    fun `overflow with default persona (empty pinned tail) falls back to keeping the prompt tail`() {
        // Default (Saarthi) persona with no response style / language set:
        // criticalTail is blank, so ChatRepositoryImpl's caller passes just
        // the user message as pinnedTail — trimPrompt must still keep it.
        val userMessage = "What is PM-KISAN?"
        val bloatedSystemPrefix = "SYSTEM PROMPT PREFIX ".repeat(500)
        val prompt = "$bloatedSystemPrefix\n\n$userMessage"

        val out = trimPrompt(prompt, budget = 200, pinnedTail = userMessage)

        assertTrue("Must end with the user message", out.endsWith(userMessage))
    }
}
