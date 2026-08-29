package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6.5 — FTS5 prefilter gate at 500+ chunks; small sessions stay BM25-only. */
class Fts5AtScaleGoldenTest {

    private fun syntheticBulkChunks(count: Int): List<RagChunkEntity> =
        (0 until count).map { i ->
            RagChunkEntity(
                id = i.toLong() + 1,
                sessionId = "bulk-session",
                docUri = "content://bulk",
                docName = "bulk.pdf",
                mimeType = "application/pdf",
                chunkIndex = i,
                text = "SECTION $i filler paragraph unique token bulk$i alpha beta gamma.",
            )
        }

    @Test
    fun `fts prefilter activates only above chunk threshold`() {
        assertTrue(shouldUseFtsPrefilter(FTS5_CHUNK_THRESHOLD + 1, sessionFastPath = false))
        assertFalse(shouldUseFtsPrefilter(FTS5_TYPICAL_DOC_MAX_CHUNKS, sessionFastPath = true))
    }

    @Test
    fun `large session golden retrieve still hits schedule on lexical path`() {
        val bulk = syntheticBulkChunks(FTS5_CHUNK_THRESHOLD + 20)
        val actEntities = goldenDocsToEntities(listOf(DpdpaActFixture.doc), sessionId = "bulk-session")
        val allEntities = bulk + actEntities.map { it.copy(id = it.id + bulk.size.toLong()) }
        val chunkCount = allEntities.count { it.chunkIndex >= 0 }
        assertTrue(shouldUseFtsPrefilter(chunkCount, sessionFastPath = false))

        val hits = goldenSessionRetrieve(
            query = "What are the monetary penalties and amounts in the schedule",
            entities = allEntities,
            sessionFiles = listOf(
                "content://bulk" to "bulk.pdf",
                DpdpaActFixture.URI to DpdpaActFixture.NAME,
            ),
            boostDocUris = setOf(DpdpaActFixture.URI),
        ).retrieved
        val joined = hits.joinToString("\n") { it.text }
        assertTrue(joined.contains("THE SCHEDULE", ignoreCase = true))
    }

    @Test
    fun `large session with structured csv rows still retrieves amounts`() {
        val rows = (1..30).joinToString("\n") { i ->
            "Crop$i,MSP\nWheat$i,${2200 + i}"
        }
        val csvDoc = formatCsvDocument("Crop,MSP\n$rows", maxChars = 50_000)
        val bulk = syntheticBulkChunks(FTS5_CHUNK_THRESHOLD)
        val csvEntities = goldenDocsToEntities(
            listOf(GoldenDoc(uri = "content://msp", name = "msp.csv", text = csvDoc)),
            sessionId = "bulk-session",
        ).map { it.copy(id = it.id + bulk.size.toLong()) }
        val allEntities = bulk + csvEntities
        val chunkCount = allEntities.count { it.chunkIndex >= 0 }
        assertTrue(shouldUseFtsPrefilter(chunkCount, sessionFastPath = false))

        val hits = goldenSessionRetrieve(
            query = "What is the MSP amount for Wheat15",
            entities = allEntities,
            sessionFiles = listOf(
                "content://bulk" to "bulk.pdf",
                "content://msp" to "msp.csv",
            ),
            boostDocUris = setOf("content://msp"),
        ).retrieved
        val joined = hits.joinToString("\n")
        assertTrue(joined.contains("2215") || joined.contains("Wheat15"))
    }
}
