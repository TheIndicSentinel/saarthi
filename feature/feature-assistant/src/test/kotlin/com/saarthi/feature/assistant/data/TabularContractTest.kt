package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 4 P18 — tabular contract siblings (Schedule + §33). */
class TabularContractTest {

    private fun chunk(
        uri: String,
        index: Int,
        text: String,
        id: Long = index.toLong() + 1,
    ) = RagChunkEntity(
        id = id,
        sessionId = "s",
        docUri = uri,
        docName = "act.pdf",
        mimeType = "application/pdf",
        chunkIndex = index,
        text = text,
    )

    @Test
    fun `contract returns schedule and section 33 from same document`() {
        val uri = "content://dpdpa"
        val chunks = listOf(
            chunk(uri, 0, "Earlier obligations text."),
            chunk(
                uri,
                1,
                "CHAPTER VIII\nPENALTIES AND ADJUDICATION\n33. Penalties\nFactors include nature of breach.",
            ),
            chunk(
                uri,
                2,
                "THE SCHEDULE\nMonetary penalty\nBreach — ₹250 crore\nMinor — ₹10 crore",
            ),
            chunk(uri, 3, "Cross-reference to penalties without amounts."),
        )
        val picked = tabularContractChunkEntities(chunks)
        assertEquals(2, picked.size)
        assertTrue(picked.any { isScheduleContractChunk(it.text) })
        assertTrue(picked.any { isSection33ContractChunk(it.text) })
    }

    @Test
    fun `contract prefers same document when section penalty combo`() {
        val actUri = "content://act"
        val otherUri = "content://other"
        val chunks = listOf(
            chunk(otherUri, 0, "THE SCHEDULE\nOther doc schedule."),
            chunk(actUri, 1, "33. Penalties\nFactors for adjudication."),
            chunk(actUri, 2, "THE SCHEDULE\nBreach — ₹200 crore"),
        )
        val picked = tabularContractChunkEntities(chunks, preferDocUri = actUri)
        assertEquals(2, picked.size)
        assertEquals(actUri, picked.first().docUri)
        assertTrue(picked.all { it.docUri == actUri })
    }

    @Test
    fun `contract picks amount heavy chunk without schedule`() {
        val uri = "content://nda"
        val chunks = listOf(
            chunk(uri, 0, "Penalty for breach of this clause is Rs 5 lakh."),
            chunk(uri, 1, "Term is 24 months from the effective date."),
        )
        val picked = tabularContractChunkEntities(chunks)
        assertEquals(1, picked.size)
        assertTrue(picked[0].text.contains("5 lakh"))
    }

    @Test
    fun `requires tabular contract for penalty schedule queries`() {
        assertTrue(requiresTabularContract("What are the monetary penalties"))
        assertTrue(requiresTabularContract("cancellation fee tariff"))
        assertFalse(requiresTabularContract("give an overview in short"))
    }

    @Test
    fun `contract chunks survive collapse as anchored span`() {
        val schedule = RetrievedChunk(
            "THE SCHEDULE\n₹200 crore",
            "act.pdf",
            0.0,
            2,
            "content://act",
            structuralAnchor = StructuralAnchorKind.TABULAR_CONTRACT,
        )
        val section33 = RetrievedChunk(
            "33. Penalties\nFactors",
            "act.pdf",
            0.0,
            1,
            "content://act",
            structuralAnchor = StructuralAnchorKind.TABULAR_CONTRACT,
        )
        val body = RetrievedChunk("operative", "act.pdf", 8.0, 3, "content://act")
        val out = collapseRedundantChunkRuns(
            listOf(section33, schedule, body),
            preserveAnchoredSpans = true,
            anchoredSpanMax = ANCHORED_SPAN_COLLAPSE_MAX,
        )
        assertEquals(3, out.size)
        assertTrue(out.any { it.chunkIndex == 1 })
        assertTrue(out.any { it.chunkIndex == 2 })
    }
}
