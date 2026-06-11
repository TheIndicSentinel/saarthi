package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [splitTextForTts] guards the voice feature against the engine's hard input
 * cap (TextToSpeech.getMaxSpeechInputLength, ~4000 chars) — a single speak()
 * call over that limit reads nothing. These tests lock the invariants: short
 * replies pass through untouched, long replies split at sentence boundaries,
 * and no chunk ever exceeds the limit (even when one sentence is huge).
 */
class TtsChunkingTest {

    @Test
    fun `short text is returned as a single chunk`() {
        val text = "This is a short reply."
        assertEquals(listOf(text), splitTextForTts(text, maxLen = 100))
    }

    @Test
    fun `blank text yields no chunks`() {
        assertTrue(splitTextForTts("   ", maxLen = 100).isEmpty())
    }

    @Test
    fun `text exactly at the limit is one chunk`() {
        val text = "a".repeat(100)
        assertEquals(1, splitTextForTts(text, maxLen = 100).size)
    }

    @Test
    fun `long text splits into multiple chunks each within the limit`() {
        // 20 sentences of ~30 chars = ~600 chars; cap at 120 → several chunks.
        val text = (1..20).joinToString(" ") { "This is sentence number $it here." }
        val chunks = splitTextForTts(text, maxLen = 120)
        assertTrue("Must split into multiple chunks", chunks.size > 1)
        chunks.forEach { assertTrue("Chunk over limit: ${it.length}", it.length <= 120) }
    }

    @Test
    fun `splitting preserves the full content`() {
        val text = (1..15).joinToString(" ") { "Sentence $it." }
        val chunks = splitTextForTts(text, maxLen = 60)
        // Re-joining the chunks must contain every sentence marker.
        val joined = chunks.joinToString(" ")
        (1..15).forEach { assertTrue("Lost sentence $it", joined.contains("Sentence $it.")) }
    }

    @Test
    fun `prefers sentence boundaries — chunk ends on a terminator`() {
        val text = "First sentence here. Second sentence here. Third sentence here. Fourth one here."
        val chunks = splitTextForTts(text, maxLen = 45)
        assertTrue("Must split", chunks.size > 1)
        // Every chunk except possibly the last should end on sentence punctuation.
        chunks.dropLast(1).forEach { c ->
            assertTrue("Chunk should end on a sentence terminator: '$c'",
                c.trimEnd().endsWith(".") || c.trimEnd().endsWith("!") ||
                    c.trimEnd().endsWith("?") || c.trimEnd().endsWith("।"))
        }
    }

    @Test
    fun `a single oversized sentence is hard-split on whitespace`() {
        // One 500-char sentence with spaces, no terminators until the end.
        val giant = (1..80).joinToString(" ") { "word$it" } + "."
        val chunks = splitTextForTts(giant, maxLen = 100)
        assertTrue("Oversized sentence must be split", chunks.size > 1)
        chunks.forEach { assertTrue("Chunk over limit: ${it.length}", it.length <= 100) }
    }

    @Test
    fun `devanagari danda is treated as a sentence boundary`() {
        val text = "पहला वाक्य है। दूसरा वाक्य है। तीसरा वाक्य है। चौथा वाक्य है।"
        val chunks = splitTextForTts(text, maxLen = 30)
        assertTrue("Must split on danda", chunks.size > 1)
        chunks.forEach { assertTrue("Chunk over limit: ${it.length}", it.length <= 30) }
    }
}
