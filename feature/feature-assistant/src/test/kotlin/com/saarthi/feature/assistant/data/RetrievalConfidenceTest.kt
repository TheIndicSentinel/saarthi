package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 1 P5 — chapter-typed retrieval confidence. */
class RetrievalConfidenceTest {

    @Test
    fun `isChapterTypedQuery excludes structure count`() {
        assertTrue(isChapterTypedQuery("highlights from chapter VII"))
        assertFalse(isChapterTypedQuery("how many chapters are there"))
    }

    @Test
    fun `hasRequestedChapterTitleLine detects body chapter header`() {
        val hits = listOf(
            retrieved(1, "CHAPTER VII\nAPPEAL AND ALTERNATE DISPUTE RESOLUTION"),
        )
        assertTrue(hasRequestedChapterTitleLine(hits, 7))
        assertFalse(hasRequestedChapterTitleLine(hits, 6))
    }

    @Test
    fun `applyChapterRetrievalConfidence replaces wrong chapter with span retry`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "CHAPTER V\nMANAGEMENT OF PERSONAL DATA"),
            chunk(uri, 1, "Government under section 18 board powers."),
            chunk(uri, 2, "CHAPTER VI\nPROCESSING OF PERSONAL DATA OUTSIDE INDIA"),
            chunk(uri, 3, "40. Transfer outside India rules."),
            chunk(uri, 4, "CHAPTER VII\nAPPEAL AND ALTERNATE DISPUTE RESOLUTION"),
        )
        val wrongHits = listOf(
            retrieved(0, chunks[0].text, uri),
            retrieved(1, chunks[1].text, uri),
        )
        val query = "highlights of chapter VI"
        val out = applyChapterRetrievalConfidence(
            query,
            wrongHits,
            chunks,
            expandedSpanChunks = 10,
        )
        assertTrue(hasRequestedChapterTitleLine(out, 6))
        assertFalse(out.any { hasConflictingChapterTitleLine(it, 6) })
        assertTrue(out.any { it.text.contains("PROCESSING OF PERSONAL DATA OUTSIDE INDIA") })
    }

    @Test
    fun `applyChapterRetrievalConfidence adds miss hint when chapter absent`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "CHAPTER I\nPRELIMINARY"),
            chunk(uri, 1, "CHAPTER II\nOBLIGATIONS"),
        )
        val wrongHits = listOf(retrieved(0, chunks[0].text, uri))
        val out = applyChapterRetrievalConfidence(
            "highlights of chapter IX",
            wrongHits,
            chunks,
            expandedSpanChunks = 10,
        )
        assertTrue(out.any { it.chunkIndex == RETRIEVAL_HINT_CHUNK_INDEX })
        assertTrue(out.first().text.contains("not found"))
        assertFalse(out.any { hasConflictingChapterTitleLine(it, 9) })
    }

  @Test
    fun `stripConflictingChapterBody removes wrong chapter title chunks`() {
        val hits = listOf(
            retrieved(0, "CHAPTER V\nMANAGEMENT"),
            retrieved(1, "Some neutral body text."),
        )
        val stripped = stripConflictingChapterBody(hits, 6)
        assertEquals(1, stripped.size)
        assertTrue(stripped[0].text.contains("neutral"))
    }

    private fun chunk(uri: String, index: Int, text: String): RagChunkEntity =
        RagChunkEntity(
            sessionId = "s",
            docUri = uri,
            docName = "act.pdf",
            mimeType = "application/pdf",
            chunkIndex = index,
            text = text,
        )

    private fun retrieved(index: Int, text: String, uri: String = "content://act"): RetrievedChunk =
        RetrievedChunk(text, "act.pdf", 5.0, index, uri)
}
