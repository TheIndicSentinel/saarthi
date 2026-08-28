package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2.4 — system-chunk guardrail for BM25/FTS and citations. */
class SystemChunkGuardrailTest {

    private fun entity(
        index: Int,
        role: String? = ChunkRole.BODY,
        text: String = "body text",
    ) = RagChunkEntity(
        sessionId = "s1",
        docUri = "content://doc",
        docName = "act.pdf",
        mimeType = "application/pdf",
        chunkIndex = index,
        text = text,
        chunkRole = role,
    )

    private fun retrieved(index: Int, anchor: StructuralAnchorKind? = null) = RetrievedChunk(
        text = "chunk $index",
        docName = "act.pdf",
        score = 5.0,
        chunkIndex = index,
        docUri = "content://doc",
        structuralAnchor = anchor,
    )

    @Test
    fun `prompt hint role excluded from bm25 pool`() {
        assertFalse(isBm25SearchableChunk(entity(3, ChunkRole.PROMPT_HINT, "use exact titles above")))
        assertTrue(isBm25SearchableChunk(entity(3, ChunkRole.BODY)))
        assertFalse(isBm25SearchableChunk(entity(-1, ChunkRole.OUTLINE)))
    }

    @Test
    fun `outline not citable for narrow doc query`() {
        val outline = retrieved(OUTLINE_CHUNK_INDEX)
        assertFalse(isCitableRetrievalChunk(outline, "What are penalties in the act"))
        assertTrue(citableRetrievalChunks(listOf(outline), "penalties").isEmpty())
    }

    @Test
    fun `outline citable for structure and overview queries`() {
        val outline = retrieved(OUTLINE_CHUNK_INDEX)
        assertTrue(isCitableRetrievalChunk(outline, "How many chapters are there"))
        assertTrue(isCitableRetrievalChunk(outline, ATTACH_BRIEF_OVERVIEW_QUERY))
    }

    @Test
    fun `synthetic hints never citable`() {
        val hint = retrieved(RETRIEVAL_HINT_CHUNK_INDEX)
        val registry = retrieved(STRUCTURE_REGISTRY_CHUNK_INDEX)
        val structureHint = retrieved(2, StructuralAnchorKind.STRUCTURE_HINT)
        assertFalse(isCitableRetrievalChunk(hint, "How many chapters"))
        assertFalse(isCitableRetrievalChunk(registry, "How many chapters"))
        assertFalse(isCitableRetrievalChunk(structureHint, "penalties"))
    }

    @Test
    fun `body chunk citable for grounded query`() {
        assertTrue(isCitableRetrievalChunk(retrieved(2), "penalties in the act"))
    }
}
