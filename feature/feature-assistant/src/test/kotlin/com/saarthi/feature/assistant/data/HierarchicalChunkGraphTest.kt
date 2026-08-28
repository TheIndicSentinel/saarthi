package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import com.saarthi.core.rag.Bm25Retriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 6 P24 — hierarchical section parent links and complete-section expansion. */
class HierarchicalChunkGraphTest {

    private fun scheduleSectionText(): String = buildString {
        append("THE SCHEDULE\n")
        append((1..18).joinToString("\n") { i ->
            "Breach category $i — monetary penalty up to ₹${i * 25} crore"
        })
    }

    @Test
    fun `legal schedule section assigns parent links to follow-on chunks`() {
        val indexed = chunkLegalGazetteDocumentWithParents(
            "CHAPTER VIII\nPENALTIES\n\n${scheduleSectionText()}",
        )
        assertTrue(indexed.size >= 2)
        val scheduleChunks = indexed.filter { it.text.contains("THE SCHEDULE") }
        assertTrue(scheduleChunks.isNotEmpty())
        val rootGlobalIdx = indexed.indexOfFirst { it.text.contains("THE SCHEDULE") && it.parentChunkIndex == null }
        assertTrue(rootGlobalIdx >= 0)
        val children = indexed.filter { it.parentChunkIndex == rootGlobalIdx }
        assertTrue(children.isNotEmpty())
    }

    @Test
    fun `section expansion pulls every sibling when one schedule chunk ranks`() {
        val indexed = chunkLegalGazetteDocumentWithParents(
            "CHAPTER VIII\nPENALTIES\n\n${scheduleSectionText()}",
        )
        assertTrue(indexed.size >= 2)
        val rootIdx = indexed.indexOfFirst { it.text.contains("THE SCHEDULE") }
        val entities = indexed.mapIndexed { idx, chunk ->
            RagChunkEntity(
                id = idx.toLong() + 1,
                sessionId = "s",
                docUri = "content://act",
                docName = "act.pdf",
                mimeType = "application/pdf",
                chunkIndex = idx,
                text = chunk.text,
                parentChunkIndex = chunk.parentChunkIndex,
            )
        }
        val sectionGroups = buildSectionGroupsByDoc(entities)
        val hitIdx = indexed.indexOfFirst { it.text.contains("Breach category 12") }
        assertTrue(hitIdx >= 0)
        val ranked = listOf(Bm25Retriever.Scored(hitIdx, 9.0))
        val expanded = expandHierarchicalSectionHits(
            ranked = ranked,
            pool = entities,
            sectionGroupsByDoc = sectionGroups,
        )
        val hit = entities[hitIdx]
        val root = sectionRootChunkIndex(hit)
        val sectionSize = sectionGroups["content://act"]?.get(root)?.size ?: 0
        assertTrue(sectionSize >= 2)
        assertEquals(sectionSize - 1, expanded.size)
    }

    @Test
    fun `anchor seed expands schedule siblings when bm25 ranked list omits section`() {
        val indexed = chunkLegalGazetteDocumentWithParents(
            "CHAPTER VIII\nPENALTIES\n\n${scheduleSectionText()}",
        )
        val entities = indexed.mapIndexed { idx, chunk ->
            RagChunkEntity(
                id = idx.toLong() + 1,
                sessionId = "s",
                docUri = "content://act",
                docName = "act.pdf",
                mimeType = "application/pdf",
                chunkIndex = idx,
                text = chunk.text,
                parentChunkIndex = chunk.parentChunkIndex,
            )
        }
        val sectionGroups = buildSectionGroupsByDoc(entities)
        val scheduleRootIdx = indexed.indexOfFirst { it.text.contains("THE SCHEDULE") && it.parentChunkIndex == null }
        assertTrue(scheduleRootIdx >= 0)
        val anchor = entities[scheduleRootIdx]
        val unrelatedIdx = indexed.indexOfFirst { it.text.contains("CHAPTER VIII") }
        val ranked = listOf(Bm25Retriever.Scored(unrelatedIdx, 4.0))
        val expanded = expandHierarchicalSectionHits(
            ranked = ranked,
            pool = entities,
            sectionGroupsByDoc = sectionGroups,
            anchorSeeds = listOf(anchor),
        )
        assertTrue(expanded.size >= 2)
        assertTrue(expanded.any { it.first.text.contains("Breach category") })
    }

    @Test
    fun `single chunk section does not expand`() {
        val entity = RagChunkEntity(
            id = 1,
            sessionId = "s",
            docUri = "content://act",
            docName = "act.pdf",
            mimeType = "application/pdf",
            chunkIndex = 0,
            text = "Short penalty clause only.",
        )
        val groups = buildSectionGroupsByDoc(listOf(entity))
        val expanded = expandHierarchicalSectionHits(
            ranked = listOf(Bm25Retriever.Scored(0, 8.0)),
            pool = listOf(entity),
            sectionGroupsByDoc = groups,
        )
        assertTrue(expanded.isEmpty())
    }

    @Test
    fun `section root resolves parent or self`() {
        val root = RagChunkEntity(
            sessionId = "s",
            docUri = "u",
            docName = "d",
            mimeType = "application/pdf",
            chunkIndex = 4,
            text = "THE SCHEDULE",
        )
        val child = root.copy(id = 2, chunkIndex = 5, parentChunkIndex = 4, text = "row 2")
        assertEquals(4, sectionRootChunkIndex(root))
        assertEquals(4, sectionRootChunkIndex(child))
    }
}
