package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagObservabilityTest {

    @Test
    fun `search line has path timings and route without query text`() {
        val line = ragSearchLogLine(
            docCount = 2,
            boostCount = 1,
            path = RagSearchPath.bm25,
            hitCount = 4,
            queryLen = 18,
            searchMs = 12,
            named = 1,
            metaReason = "list",
            headingChunks = 3,
        )
        assertEquals(
            "docs=2 boost=1 path=bm25 hits=4 queryLen=18 searchMs=12 named=1 equal=0 whichFile=0 thisDoc=0 followUp=0 meta=list headingChunks=3",
            line,
        )
        assertFalse(line.contains("penalty"))
        assertFalse(line.contains("content://"))
        assertFalse(line.contains("Offices"))
    }

    @Test
    fun `offices list query is tagged meta list not the question text`() {
        assertEquals("list", RagDocumentRepository.metaRouteReason("Offices ke list do"))
        assertEquals("overview", RagDocumentRepository.metaRouteReason("Document content ka overview do"))
        assertEquals(null, RagDocumentRepository.metaRouteReason("Pune office ka address do"))
        assertEquals(null, RagDocumentRepository.metaRouteReason("What is journey to compliance"))
    }

    @Test
    fun `chunk line uses nameLen not the filename`() {
        val line = ragChunkLogLine(1, nameLen = 24, chunkIndex = 2, page = "p.4", score = 1.5)
        assertEquals("  [1] nameLen=24 · part 3 · p.4  score=1.50", line)
        assertFalse(line.contains("NDA"))
        val outline = ragChunkLogLine(1, nameLen = 8, chunkIndex = -1, page = null, score = 1.0)
        assertTrue(outline.contains("outline"))
        assertFalse(outline.contains("p."))
    }

    @Test
    fun `index fail logs class not message or filename`() {
        val line = ragIndexFailLogLine(nameLen = 11, exceptionName = "SQLiteException")
        assertEquals("index failed nameLen=11 ex=SQLiteException", line)
        assertFalse(line.contains("statement.pdf"))
        assertFalse(line.contains("database is locked"))
    }

    @Test
    fun `index success is lengths and indexMs`() {
        val line = ragIndexLogLine(21, 9823, hasOutline = true, nameLen = 12, sessionIdLen = 8, indexMs = 40)
        assertTrue(line.contains("indexMs=40"))
        assertTrue(line.contains("nameLen=12"))
        assertFalse(line.contains("Agreement"))
    }
}
