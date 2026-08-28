package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RagFts5Test {

    @Test
    fun `buildFtsMatchQuery joins tokenised terms with OR`() {
        val match = buildFtsMatchQuery("What are penalties?")
        assertTrue(match != null && match.contains("penalties"))
    }

    @Test
    fun `buildFtsMatchQuery returns null for blank query`() {
        assertNull(buildFtsMatchQuery("   "))
    }

    @Test
    fun `shouldUseFtsPrefilter when over chunk threshold or large fast path corpus`() {
        assertTrue(shouldUseFtsPrefilter(FTS5_CHUNK_THRESHOLD + 1, sessionFastPath = false))
        assertTrue(shouldUseFtsPrefilter(FTS5_TYPICAL_DOC_MAX_CHUNKS + 1, sessionFastPath = true))
        assertFalse(shouldUseFtsPrefilter(40, sessionFastPath = true))
        assertFalse(shouldUseFtsPrefilter(40, sessionFastPath = false))
    }

    @Test
    fun `search log marks fts prefilter`() {
        val line = ragSearchLogLine(
            docCount = 2,
            boostCount = 1,
            path = RagSearchPath.bm25,
            hitCount = 4,
            queryLen = 20,
            searchMs = 60,
            ftsPrefilter = true,
        )
        assertTrue(line.contains("fts=1"))
    }
}
