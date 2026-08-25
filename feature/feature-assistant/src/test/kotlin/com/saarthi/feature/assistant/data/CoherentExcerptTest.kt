package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R6 — unit tests for [coherentExcerptForLowRelevance]. */
class CoherentExcerptTest {

    private fun chunk(index: Int, score: Double, text: String = "body $index") =
        RetrievedChunk(text = text, docName = "Doc.pdf", score = score, chunkIndex = index)

    private fun outline(text: String = "outline") =
        RetrievedChunk(text = text, docName = "Doc.pdf", score = 1.0, chunkIndex = -1)

    @Test
    fun `small result sets pass through unchanged`() {
        val input = listOf(outline(), chunk(0, 0.0), chunk(1, 0.0))
        assertEquals(input, coherentExcerptForLowRelevance(input))
    }

    @Test
    fun `any positive body score passes through unchanged`() {
        val input = listOf(
            outline(),
            chunk(0, 0.0),
            chunk(3, 0.0),
            chunk(7, 1.5, "penalty clause"),
            chunk(9, 0.0),
        )
        assertEquals(input, coherentExcerptForLowRelevance(input))
    }

    @Test
    fun `all-zero body scores collapse to outline plus first four in order`() {
        val entities = (0..9).map { i ->
            RagChunkEntity(
                id = i.toLong(), sessionId = "s1", docUri = "d", docName = "Doc.pdf",
                mimeType = "application/pdf", chunkIndex = i, text = "body $i",
            )
        }
        val input = listOf(
            outline(),
            chunk(9, 0.0),
            chunk(1, 0.0),
            chunk(5, 0.0),
            chunk(0, 0.0),
            chunk(3, 0.0),
        )
        val result = coherentExcerptForLowRelevance(input, entities)
        assertEquals(listOf(-1, 0, 1, 2, 3), result.map { it.chunkIndex })
        assertTrue(result.first().chunkIndex < 0)
    }

    @Test
    fun `heading anchor score counts as relevant and is preserved`() {
        val input = (0..8).map { chunk(it, if (it == 2) 50.0 else 0.0) }
        assertEquals(input, coherentExcerptForLowRelevance(input))
    }
}
