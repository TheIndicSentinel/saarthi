package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagObservabilityTest {

    @Test
    fun `search line has path and timings without query text`() {
        val line = ragSearchLogLine(
            docCount = 2,
            boostCount = 1,
            path = RagSearchPath.bm25,
            hitCount = 4,
            queryLen = 18,
            searchMs = 12,
        )
        assertEquals("docs=2 boost=1 path=bm25 hits=4 queryLen=18 searchMs=12", line)
        assertFalse(line.contains("penalty"))
        assertFalse(line.contains("content://"))
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
