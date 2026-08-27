package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1-4 — tabular / schedule / amount retrieval. */
class TabularAmountT4Test {

    @Test
    fun `isTabularAmountQuery covers fees and tariffs`() {
        assertTrue(isTabularAmountQuery("what are the service charges"))
        assertTrue(isTabularAmountQuery("cancellation fee tariff"))
        assertTrue(isTabularAmountQuery("What does document say about penalties"))
    }

    @Test
    fun `tabular tier prefers schedule with multiple amount lines`() {
        val scheduleTable = """
            THE SCHEDULE
            Breach of duty — Rs 200 crore
            Minor breach — Rs 50 crore
            Failure to notify — Rs 10 crore
        """.trimIndent()
        val procedure = "The inquiry and adjudication process is described in the body of the act."
        val procTier = tabularChunkTier(procedure) ?: Int.MAX_VALUE
        assertTrue(tabularChunkTier(scheduleTable)!! < procTier)
    }

    @Test
    fun `pickTabularAmount prefers schedule table chunk`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "Chapter VIII penalties and inquiry procedure only."),
            chunk(uri, 1, "THE SCHEDULE\nMonetary penalty\nBreach — Rs 200 crore\nMinor — Rs 10 crore"),
        )
        val picked = pickTabularAmountChunkEntities(chunks, preferDocUri = null, max = 1)
        assertEquals(1, picked[0].chunkIndex)
    }

    @Test
    fun `tabular list shape instruction mentions amounts`() {
        val instruction = ragAnswerShapeInstruction(
            RagAnswerShape.LIST,
            tabularAmount = true,
        )
        assertTrue(instruction.contains("amount") || instruction.contains("₹"))
    }

    @Test
    fun `weak penalty cross-ref fragment ranks after schedule table`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "the personal data has been processed without consent and may attract a penalty."),
            chunk(uri, 1, "THE SCHEDULE\nBreach — Rs 200 crore\nMinor — Rs 10 crore"),
        )
        val picked = pickTabularAmountChunkEntities(chunks, preferDocUri = null, max = 1)
        assertEquals(1, picked[0].chunkIndex)
        assertTrue(isTabularWeakFragment(chunks[0].text))
        assertTrue(!isTabularWeakFragment(chunks[1].text))
    }

    private fun chunk(uri: String, index: Int, text: String): RagChunkEntity =
        RagChunkEntity(
            sessionId = "s",
            docUri = uri,
            docName = "doc.pdf",
            mimeType = "application/pdf",
            chunkIndex = index,
            text = text,
        )
}
