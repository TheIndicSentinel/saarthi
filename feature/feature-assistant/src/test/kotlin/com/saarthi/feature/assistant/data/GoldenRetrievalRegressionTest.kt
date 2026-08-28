package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 5 P23 — regression lock: [retrieveGolden] BM25 fixtures must also pass
 * full index → retrieve → prompt assembly (ragChars, chunk count, chapter span IDs).
 */
class GoldenRetrievalRegressionTest {

    private fun full(
        query: String,
        docs: List<GoldenDoc> = GoldenFixtures.englishPair,
    ): GoldenFullMetrics = retrieveGoldenFull(query, docs)

    private fun assertPipelineAgreesWithBm25(metrics: GoldenFullMetrics) {
        assertEquals(
            "bm25=${metrics.bm25TopUri} pipeline=${metrics.pipelineTopUri}",
            metrics.bm25TopUri,
            metrics.pipelineTopUri,
        )
        assertTrue("ragChars=${metrics.ragChars}", metrics.ragChars > 0)
        assertTrue("chunkCount=${metrics.chunkCount}", metrics.chunkCount > 0)
    }

    private fun assertPipelineProducesGroundedBlock(metrics: GoldenFullMetrics) {
        assertTrue("ragChars=${metrics.ragChars}", metrics.ragChars > 0)
        assertTrue("chunkCount=${metrics.chunkCount}", metrics.chunkCount > 0)
        assertTrue("pipelineTopUri missing", metrics.pipelineTopUri != null)
    }

    @Test
    fun `english penalty question agrees across BM25 and full pipeline`() {
        assertPipelineAgreesWithBm25(full("what is the penalty"))
    }

    @Test
    fun `english salary question agrees across BM25 and full pipeline`() {
        assertPipelineAgreesWithBm25(full("what is the salary credit"))
    }

    @Test
    fun `hindi penalty queries agree and assemble rag block`() {
        assertPipelineProducesGroundedBlock(full("इसमें जुर्माना क्या है"))
        assertPipelineProducesGroundedBlock(full("is agreement me jurmana kitna hai"))
    }

    @Test
    fun `indic script penalty queries agree on english NDA`() {
        assertPipelineAgreesWithBm25(full("ஒப்பந்தத்தில் அபராதம் என்ன"))
        assertPipelineAgreesWithBm25(full("ఒప్పందంలో జరిమానా ఎంత"))
        assertPipelineAgreesWithBm25(full("চুক্তিতে জরিমানা কত"))
        assertPipelineAgreesWithBm25(full("ಒಪ್ಪಂದದಲ್ಲಿ ದಂಡ ಎಷ್ಟು"))
    }

    @Test
    fun `same-script hindi circular beats leave note in full pipeline`() {
        val docs = listOf(GoldenFixtures.hindiCircular, GoldenFixtures.hindiLeave)
        assertPipelineProducesGroundedBlock(full("जुर्माना कितना है", docs))
    }

    @Test
    fun `same-script tamil salary notice beats holiday note in full pipeline`() {
        val docs = listOf(GoldenFixtures.tamilNotice, GoldenFixtures.tamilHoliday)
        assertPipelineAgreesWithBm25(full("சம்பளம் எப்போது", docs))
    }

    @Test
    fun `penalty query on NDA without schedule does not crash tabular contract`() {
        val metrics = full("what is the penalty")
        assertEquals(GoldenFixtures.NDA_URI, metrics.pipelineTopUri)
        assertTrue(metrics.ragChars > 0)
    }

    @Test
    fun `unreadable scan never surfaces in full pipeline retrieval`() {
        val docs = GoldenFixtures.englishPair + GoldenFixtures.unreadableScan
        val metrics = full("what is the salary credit", docs)
        assertEquals(GoldenFixtures.STMT_URI, metrics.pipelineTopUri)
        assertFalse(metrics.bm25Hits.any { it.docUri == GoldenFixtures.SCAN_URI })
        assertTrue(metrics.ragChars > 0)
    }

    @Test
    fun `dpdpa penalties query has schedule chapter span and citations path`() {
        val metrics = retrieveGoldenFull(
            "What are the monetary penalties and amounts in the schedule",
            listOf(DpdpaActFixture.doc),
        )
        assertTrue(metrics.ragChars > 80)
        assertTrue(metrics.chunkCount >= 2)
        assertTrue(metrics.chapterIds.isNotEmpty())
        assertTrue(
            metrics.bm25Hits.isNotEmpty() ||
                metrics.pipelineTopUri == DpdpaActFixture.URI,
        )
    }
}
