package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [formatConversationContext] is the multi-turn context block fed into every
 * FRESH prompt. It is the fix for "context is very weak" — it carries Saarthi's
 * PRIOR ANSWERS (not just the user's questions) so follow-ups resolve against
 * what was actually said. These tests lock the anti-echo framing and the
 * tier / grounded budgeting that keep it from overflowing the prompt window.
 */
class ConversationContextTest {

    private fun turn(u: String, a: String) = u to a

    // ── Empty / degenerate ──────────────────────────────────────────────────

    @Test
    fun `empty turns yield empty block`() {
        assertEquals("", formatConversationContext(emptyList(), isLarge = true, grounded = false))
    }

    // ── Includes BOTH sides (the actual fix) ───────────────────────────────────

    @Test
    fun `block includes both the user question and Saarthi's answer`() {
        val out = formatConversationContext(
            listOf(turn("What is PM-KISAN?", "It is a central income-support scheme for farmers.")),
            isLarge = true,
            grounded = false,
        )
        assertTrue("Must label the user line. Got:\n$out", out.contains("User: What is PM-KISAN?"))
        assertTrue("Must include Saarthi's prior answer. Got:\n$out",
            out.contains("Saarthi: It is a central income-support scheme for farmers."))
    }

    @Test
    fun `header frames the block as context and forbids repeating`() {
        val out = formatConversationContext(
            listOf(turn("hi", "hello")),
            isLarge = true,
            grounded = false,
        )
        assertTrue("Header must forbid restating. Got:\n$out",
            out.contains("do not repeat", ignoreCase = true))
        assertTrue("Header must point at the NEW message. Got:\n$out",
            out.contains("NEW message", ignoreCase = false))
        // Anti-echo: never use "You:" for the assistant (the old regression).
        assertFalse("Must not label the assistant 'You:'. Got:\n$out", out.contains("You:"))
    }

    // ── Truncation bounds echo ─────────────────────────────────────────────────

    @Test
    fun `long assistant reply is truncated with an ellipsis`() {
        val longReply = "x".repeat(2000)
        val out = formatConversationContext(
            listOf(turn("q", longReply)),
            isLarge = true,
            grounded = false,
        )
        assertTrue("Long reply must be truncated with …. Got length=${out.length}", out.contains("…"))
        // LARGE non-grounded caps a reply at 320 chars; the whole block must be
        // far smaller than the raw 2000-char reply.
        assertTrue("Block must be bounded well under the raw reply. len=${out.length}", out.length < 600)
    }

    // ── Window / recency ───────────────────────────────────────────────────────

    @Test
    fun `LARGE keeps at most the last three turns`() {
        val turns = (1..6).map { turn("q$it", "a$it") }
        val out = formatConversationContext(turns, isLarge = true, grounded = false)
        // Most-recent three present …
        assertTrue(out.contains("User: q6")); assertTrue(out.contains("Saarthi: a6"))
        assertTrue(out.contains("User: q4")); assertTrue(out.contains("Saarthi: a4"))
        // … older ones dropped.
        assertFalse("Turn 3 should be outside the LARGE window. Got:\n$out", out.contains("q3"))
        assertFalse("Turn 1 should be outside the LARGE window. Got:\n$out", out.contains("q1"))
    }

    @Test
    fun `roomy LARGE deepens recap on grounded (document) turns`() {
        val turns = (1..6).map { turn("q$it", "a$it") }
        // Deep recap is reserved for DOCUMENT (grounded) turns on a high-end
        // window — there the document anchors the model and "explain more"
        // follow-ups need continuity.
        val roomy = formatConversationContext(turns, isLarge = true, grounded = true, roomy = true)
        assertTrue("Turn 1 should be kept on a roomy grounded turn. Got:\n$roomy", roomy.contains("q1"))
        assertTrue(roomy.contains("q6"))
        // …and it carries strictly more than the tighter mid-range default.
        val default = formatConversationContext(turns, isLarge = true, grounded = true, roomy = false)
        assertTrue("roomy must carry at least as much as default", roomy.length >= default.length)
        assertFalse("default LARGE must still drop turn 1", default.contains("q1"))
    }

    @Test
    fun `roomy does NOT deepen plain (non-grounded) chat — small-model loop guard`() {
        val turns = (1..6).map { turn("q$it", "a$it") }
        // Plain chat keeps the tight isLarge caps even on a roomy device: feeding
        // a 2B its own long prior answers back triggered repetition loops, so
        // non-grounded chat must stay at the 3-turn window (turn 1 dropped).
        val roomy = formatConversationContext(turns, isLarge = true, grounded = false, roomy = true)
        assertFalse("Plain roomy chat must not deepen to turn 1. Got:\n$roomy", roomy.contains("q1"))
    }

    @Test
    fun `roomy only deepens LARGE, not STANDARD`() {
        val turns = (1..6).map { turn("q$it", "a$it") }
        // STANDARD ignores roomy (mid-range 3n must stay within its small window).
        val std = formatConversationContext(turns, isLarge = false, grounded = false, roomy = true)
        assertFalse("STANDARD must not deepen even when roomy. Got:\n$std", std.contains("q3"))
    }

    @Test
    fun `STANDARD keeps a smaller window than LARGE`() {
        val turns = (1..4).map { turn("q$it", "a$it") }
        val out = formatConversationContext(turns, isLarge = false, grounded = false)
        // STANDARD = last 2 turns only.
        assertTrue(out.contains("q4")); assertTrue(out.contains("q3"))
        assertFalse("Turn 2 should be outside the STANDARD window. Got:\n$out", out.contains("q2"))
    }

    // ── Budget never blown ─────────────────────────────────────────────────────

    @Test
    fun `block stays within the LARGE non-grounded budget`() {
        // Three sizeable turns — must still fit the 1500c LARGE budget by
        // dropping the oldest if necessary. Markers are at the START of each
        // string so they survive per-turn truncation.
        val turns = (1..3).map { turn("Q$it " + "filler ".repeat(40), "A$it " + "lorem ".repeat(80)) }
        val out = formatConversationContext(turns, isLarge = true, grounded = false)
        assertTrue("LARGE block must stay within ~1500c. len=${out.length}", out.length <= 1500)
        // The most recent turn must always survive (its leading marker is kept).
        assertTrue("Most recent turn must be kept. Got:\n$out",
            out.contains("Q3 ") || out.contains("A3 "))
    }

    @Test
    fun `grounded turns shrink the budget versus non-grounded`() {
        val turns = (1..3).map { turn("q".repeat(120) + it, "a".repeat(400) + it) }
        val grounded = formatConversationContext(turns, isLarge = true, grounded = true)
        val open = formatConversationContext(turns, isLarge = true, grounded = false)
        assertTrue("Grounded block must be no larger than the open one", grounded.length <= open.length)
        assertTrue("Grounded LARGE block must stay within ~1100c. len=${grounded.length}",
            grounded.length <= 1100)
    }
}
