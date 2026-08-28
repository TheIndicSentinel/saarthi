package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import com.saarthi.core.rag.Bm25Retriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 4 P17 — parent heading + neighbor expansion after rerank. */
class RerankNeighborExpansionTest {

    private fun entity(
        id: Long,
        index: Int,
        text: String,
        uri: String = "content://doc",
        chunkRole: String? = ChunkRole.BODY,
    ) = RagChunkEntity(
        id = id,
        sessionId = "s",
        docUri = uri,
        docName = "act.pdf",
        mimeType = "application/pdf",
        chunkIndex = index,
        text = text,
        chunkRole = chunkRole,
    )

    private fun orderedIds(chunks: List<RagChunkEntity>): Map<String, List<Long>> =
        mapOf(chunks.first().docUri to chunks.sortedBy { it.chunkIndex }.map { it.id })

    @Test
    fun `prev neighbor stays in same document`() {
        val byDoc = mapOf(
            "nda" to listOf(1L, 2L, 3L),
            "log" to listOf(10L, 11L),
        )
        assertEquals(2L, prevSameDocNeighborId(3L, "nda", byDoc))
        assertEquals(null, prevSameDocNeighborId(1L, "nda", byDoc))
        assertEquals(null, prevSameDocNeighborId(2L, "log", byDoc))
    }

    @Test
    fun `expansion adds parent heading and next chunk for body hit`() {
        val heading = entity(1L, 4, "CHAPTER VII\nRights of Data Principal", chunkRole = ChunkRole.HEADING)
        val body = entity(2L, 5, "The Data Principal shall have the right to access personal data.")
        val tail = entity(3L, 6, "The Data Principal may also seek correction of data.")
        val chunks = listOf(heading, body, tail)
        val byUri = mapOf(body.docUri to chunks)
        val ranked = listOf(Bm25Retriever.Scored(1, 8.0))
        val expanded = expandRerankedNeighborHits(
            ranked = ranked,
            pool = chunks,
            docChunksByUri = byUri,
            orderedIdsByDoc = orderedIds(chunks),
        )
        assertEquals(2, expanded.size)
        assertEquals(4, expanded[0].first.chunkIndex)
        assertEquals(6, expanded[1].first.chunkIndex)
        assertEquals(4.0, expanded[0].second, 0.0)
        assertEquals(4.0, expanded[1].second, 0.0)
    }

    @Test
    fun `expansion survives collapse as anchored span`() {
        val heading = RetrievedChunk(
            "CHAPTER VII",
            "act.pdf",
            4.0,
            4,
            "content://doc",
            structuralAnchor = StructuralAnchorKind.NEIGHBOR_EXPAND,
        )
        val body = RetrievedChunk("operative clause text", "act.pdf", 9.0, 5, "content://doc")
        val tail = RetrievedChunk(
            "continuation text",
            "act.pdf",
            4.0,
            6,
            "content://doc",
            structuralAnchor = StructuralAnchorKind.NEIGHBOR_EXPAND,
        )
        val out = collapseRedundantChunkRuns(
            listOf(heading, body, tail),
            preserveAnchoredSpans = true,
            anchoredSpanMax = ANCHORED_SPAN_COLLAPSE_MAX,
        )
        assertEquals(3, out.size)
        assertEquals(4, out[0].chunkIndex)
        assertEquals(6, out.last().chunkIndex)
    }

    @Test
    fun `weak half-score neighbor would be collapsed without expansion score`() {
        val heading = RetrievedChunk("CHAPTER VII", "act.pdf", 8.0, 4, "content://doc")
        val body = RetrievedChunk("operative clause", "act.pdf", 9.0, 5, "content://doc")
        val weakNeighbor = RetrievedChunk("continuation", "act.pdf", 4.0, 6, "content://doc")
        val out = collapseRedundantChunkRuns(listOf(heading, body, weakNeighbor))
        assertEquals(2, out.size)
        assertFalse(out.any { it.chunkIndex == 6 })
    }

    @Test
    fun `expansion does not cross documents`() {
        val a = entity(1L, 0, "CHAPTER I\nIntro", uri = "content://a", chunkRole = ChunkRole.HEADING)
        val b = entity(2L, 0, "body in other file", uri = "content://b")
        val ranked = listOf(Bm25Retriever.Scored(1, 7.0))
        val expanded = expandRerankedNeighborHits(
            ranked = ranked,
            pool = listOf(a, b),
            docChunksByUri = mapOf(a.docUri to listOf(a), b.docUri to listOf(b)),
            orderedIdsByDoc = mapOf(a.docUri to listOf(a.id), b.docUri to listOf(b.id)),
        )
        assertTrue(expanded.isEmpty())
    }
}
