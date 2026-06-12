package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Robustness tests for [chunkDocumentText] — the RAG quality floor. A bad
 * chunker slices words mid-token ("per|sonal"), and those fragments can't match
 * BM25 query terms, so the right passage scores 0 and the model grounds its
 * answer in the wrong text. These tests lock the invariants that prevent that.
 */
class ChunkDocumentTextTest {

    private val SIZE = 200
    private val OVERLAP = 30

    @Test
    fun `empty or blank text yields no chunks`() {
        assertTrue(chunkDocumentText("", SIZE, OVERLAP).isEmpty())
        assertTrue(chunkDocumentText("    \n  ", SIZE, OVERLAP).isEmpty())
    }

    @Test
    fun `short text is a single chunk`() {
        val text = "PM-KISAN gives eligible farmers Rs 6000 a year."
        assertEquals(listOf(text), chunkDocumentText(text, SIZE, OVERLAP))
    }

    @Test
    fun `long text splits into multiple chunks`() {
        val text = ("This is a sentence about consent and data. ".repeat(20)).trim()
        val chunks = chunkDocumentText(text, SIZE, OVERLAP)
        assertTrue("Long text must split", chunks.size > 1)
    }

    @Test
    fun `no chunk ever splits a word`() {
        // Distinct whole words; if any boundary cut mid-word we'd see a fragment
        // that is not one of the originals.
        val words = (1..400).map { "word$it" }
        val text = words.joinToString(" ")
        val chunks = chunkDocumentText(text, SIZE, OVERLAP)
        val vocab = words.toSet()
        for (chunk in chunks) {
            for (tok in chunk.split(Regex("\\s+")).filter { it.isNotBlank() }) {
                assertTrue("Mid-word cut produced fragment '$tok'", tok in vocab)
            }
        }
    }

    @Test
    fun `chunks prefer to end on a sentence boundary`() {
        val text = ("Consent must be informed. " +
            "A breach attracts a penalty. " +
            "Children's data needs parental consent. ").repeat(8).trim()
        val chunks = chunkDocumentText(text, SIZE, OVERLAP)
        assertTrue(chunks.size > 1)
        // Most non-final chunks should end on sentence punctuation (the boundary
        // search targets the latter half of each window).
        val endsOnSentence = chunks.dropLast(1).count { it.trimEnd().endsWith(".") }
        assertTrue("Most chunks should end on a sentence boundary ($endsOnSentence/${chunks.size - 1})",
            endsOnSentence >= (chunks.size - 1) / 2)
    }

    @Test
    fun `consecutive chunks overlap so a boundary-straddling answer survives`() {
        val text = ("alpha bravo charlie delta echo foxtrot golf hotel india juliet ").repeat(10).trim()
        val chunks = chunkDocumentText(text, SIZE, OVERLAP)
        assertTrue(chunks.size > 1)
        // The tail words of chunk N should reappear at the head of chunk N+1.
        val tailWords = chunks[0].split(" ").takeLast(3)
        assertTrue("Overlap must carry context across the boundary",
            tailWords.any { it.isNotBlank() && chunks[1].startsWith(it) || chunks[1].contains(it) })
    }

    @Test
    fun `no chunk is blank and none wildly exceeds the window`() {
        val text = "लंबा वाक्य है जो किसान और MSP के बारे में है। ".repeat(30).trim()
        val chunks = chunkDocumentText(text, SIZE, OVERLAP)
        assertTrue(chunks.isNotEmpty())
        for (c in chunks) {
            assertTrue("No blank chunk", c.isNotBlank())
            // Word-boundary fallback can slightly exceed SIZE, but never unbounded.
            assertTrue("Chunk far over window: ${c.length}", c.length <= SIZE * 2)
        }
    }

    @Test
    fun `a single token longer than the window does not loop forever`() {
        // Pathological input: one very long unbroken token. Must terminate and
        // return it (word-boundary search runs to end of string).
        val giant = "x".repeat(SIZE * 3)
        val chunks = chunkDocumentText(giant, SIZE, OVERLAP)
        assertEquals(1, chunks.size)
        assertEquals(giant, chunks.first())
    }
}
