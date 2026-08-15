package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroScoreFallbackTest {

    private fun chunk(
        docUri: String,
        index: Int,
        score: Double,
        name: String = docUri,
    ) = RetrievedChunk(
        text = "$name-$index",
        docName = name,
        score = score,
        chunkIndex = index,
        docUri = docUri,
    )

    @Test
    fun `contiguous opening is sequential not first-middle-last`() {
        val idx = pickZeroScoreBodyIndices(size = 10, count = 4, mode = ZeroScorePick.CONTIGUOUS)
        assertEquals(listOf(0, 1, 2, 3), idx)
        assertFalse(idx == pickZeroScoreBodyIndices(10, 4, ZeroScorePick.SPACED))
    }

    @Test
    fun `which-file spaced still covers first and last`() {
        val idx = pickZeroScoreBodyIndices(size = 10, count = 3, mode = ZeroScorePick.SPACED)
        assertEquals(0, idx.first())
        assertEquals(9, idx.last())
    }

    @Test
    fun `tail mode is a contiguous end window`() {
        assertEquals(
            listOf(7, 8, 9),
            pickZeroScoreBodyIndices(size = 10, count = 3, mode = ZeroScorePick.TAIL),
        )
    }

    @Test
    fun `real BM25 body hit is not replaced`() {
        val hits = listOf(
            chunk("nda", index = 5, score = 2.4),
            chunk("nda", index = 0, score = 0.0),
        )
        val fallback = listOf(chunk("nda", index = 0, score = 0.0), chunk("nda", index = 1, score = 0.0))
        assertEquals(hits, keepOrFallback(hits, fallback))
    }

    @Test
    fun `all-zero body uses per-file fallback not global index sort`() {
        val scatter = listOf(
            chunk("log", index = 0, score = 0.0),
            chunk("nda", index = 0, score = 0.0),
            chunk("log", index = 6, score = 0.0),
            chunk("nda", index = 6, score = 0.0),
        )
        val fallback = listOf(
            chunk("log", index = -1, score = 1.0),
            chunk("log", index = 0, score = 0.0),
            chunk("log", index = 1, score = 0.0),
            chunk("nda", index = -1, score = 1.0),
            chunk("nda", index = 0, score = 0.0),
            chunk("nda", index = 1, score = 0.0),
        )
        val resolved = keepOrFallback(scatter, fallback)
        assertEquals(fallback, resolved)
        val logBody = resolved.filter { it.docUri == "log" && it.chunkIndex >= 0 }
        val ndaBody = resolved.filter { it.docUri == "nda" && it.chunkIndex >= 0 }
        assertEquals(listOf(0, 1), logBody.map { it.chunkIndex })
        assertEquals(listOf(0, 1), ndaBody.map { it.chunkIndex })
        assertTrue(resolved.any { it.docUri == "log" })
        assertTrue(resolved.any { it.docUri == "nda" })
    }

    @Test
    fun `outline-only scores do not count as a body hit`() {
        val hits = listOf(
            chunk("nda", index = -1, score = 1.0),
            chunk("nda", index = 0, score = 0.0),
            chunk("nda", index = 3, score = 0.0),
        )
        assertFalse(hasPositiveBodyHit(hits))
    }
}
