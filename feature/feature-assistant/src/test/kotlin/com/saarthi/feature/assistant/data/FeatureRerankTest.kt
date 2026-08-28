package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import com.saarthi.core.rag.Bm25Retriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 4 P16 — BM25 candidate pool + feature rerank. */
class FeatureRerankTest {

    private fun entity(
        index: Int,
        text: String,
        chapterId: String? = null,
        chunkRole: String? = ChunkRole.BODY,
        headingPath: String? = null,
    ) = RagChunkEntity(
        sessionId = "s",
        docUri = "content://doc",
        docName = "act.pdf",
        mimeType = "application/pdf",
        chunkIndex = index,
        text = text,
        chapterId = chapterId,
        headingPath = headingPath,
        chunkRole = chunkRole,
    )

    @Test
    fun `cross encoder rerank stays disabled for on-device budget`() {
        assertFalse(crossEncoderRerankEnabled())
    }

    @Test
    fun `candidate pool clamps between min and max`() {
        assertEquals(20, featureRerankCandidatePoolSize(topK = 4, uniqueDocs = 1, poolSize = 100))
        assertEquals(30, featureRerankCandidatePoolSize(topK = 15, uniqueDocs = 2, poolSize = 200))
        assertEquals(12, featureRerankCandidatePoolSize(topK = 6, uniqueDocs = 2, poolSize = 12))
    }

    @Test
    fun `tabular query reranks schedule chunk above weak penalty mention`() {
        val schedule = entity(
            0,
            "THE SCHEDULE\nMonetary penalty up to ₹250 crore",
            chunkRole = ChunkRole.TABLE,
        )
        val weak = entity(
            1,
            "the board may discuss penalties in general terms without amounts.",
        )
        val ranked = listOf(
            Bm25Retriever.Scored(1, 8.0),
            Bm25Retriever.Scored(0, 6.0),
        )
        val ctx = buildFeatureRerankContext("What are the monetary penalties in the schedule")
        val out = featureRerankBm25Candidates(ranked, listOf(schedule, weak), "penalties schedule", ctx)
        assertEquals(0, out.first().index)
        assertTrue(out.first().score > ranked.first().score)
    }

    @Test
    fun `chapter span query boosts matching chapter metadata`() {
        val chapterVi = entity(
            2,
            "CHAPTER VI\nProcessing of personal data",
            chapterId = "VI",
            chunkRole = ChunkRole.HEADING,
            headingPath = "CHAPTER VI",
        )
        val chapterVii = entity(
            3,
            "CHAPTER VII\nRights of Data Principal",
            chapterId = "VII",
            chunkRole = ChunkRole.HEADING,
        )
        val ranked = listOf(
            Bm25Retriever.Scored(1, 7.0),
            Bm25Retriever.Scored(0, 6.5),
        )
        val query = "Highlights from chapter VI"
        val ctx = buildFeatureRerankContext(query)
        val out = featureRerankBm25Candidates(ranked, listOf(chapterVi, chapterVii), query, ctx)
        assertEquals(0, out.first().index)
    }

    @Test
    fun `heading line-start bonus beats nearby BM25 peak`() {
        val heading = entity(
            0,
            "CHAPTER VII\nRights of Data Principal",
            chapterId = "VII",
            chunkRole = ChunkRole.HEADING,
        )
        val body = entity(
            1,
            "The word chapter appears many times in this long paragraph about other topics.",
        )
        val ranked = listOf(
            Bm25Retriever.Scored(1, 9.0),
            Bm25Retriever.Scored(0, 4.0),
        )
        val query = "What does chapter VII say about rights"
        val out = featureRerankBm25Candidates(
            ranked,
            listOf(heading, body),
            query,
            buildFeatureRerankContext(query),
        )
        assertEquals(0, out.first().index)
    }
}
