package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 5.1–5.3 — structure, topic, and tabular prefer helpers. */
class Tier5RetrievalSubstanceTest {

    @Test
    fun `resolveTabularPreferDocUri prefers single restrict uri`() {
        assertEquals(
            "content://act",
            resolveTabularPreferDocUri(
                "what are penalties in the schedule",
                restrictUris = setOf("content://act"),
                activeDocUri = "content://guide",
            ),
        )
    }

    @Test
    fun `resolveTabularPreferDocUri uses active doc when session is open`() {
        assertEquals(
            "content://act",
            resolveTabularPreferDocUri(
                "monetary penalties in schedule",
                restrictUris = emptySet(),
                activeDocUri = "content://act",
            ),
        )
    }

    @Test
    fun `structureMarkerBm25Expansion covers chapters`() {
        val expansion = structureMarkerBm25Expansion("how many chapters in total")
        assertTrue(expansion.contains("CHAPTER"))
        assertTrue(expansion.contains("अध्याय"))
    }

    @Test
    fun `pickTopicAnchor prefers disqualification heading`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "Section 1 short title only."),
            chunk(uri, 1, "DISQUALIFICATION FOR APPOINTMENT\nNo person shall be appointed."),
        )
        val picked = pickTopicAnchorChunkEntities(chunks, "disqualification for appointment", max = 1)
        assertEquals(1, picked[0].chunkIndex)
        assertTrue(picked[0].text.contains("DISQUALIFICATION"))
    }

    @Test
    fun `pickTopicAnchor prefers special provisions heading`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "General penalties may apply under other chapters."),
            chunk(uri, 1, "SPECIAL PROVISIONS\nCertain entities are exempt."),
        )
        val picked = pickTopicAnchorChunkEntities(chunks, "What are special provisions", max = 1)
        assertEquals(1, picked[0].chunkIndex)
        assertTrue(picked[0].text.contains("SPECIAL PROVISIONS"))
    }

    private fun chunk(uri: String, index: Int, text: String): RagChunkEntity =
        RagChunkEntity(
            sessionId = "s",
            docUri = uri,
            docName = "doc.pdf",
            mimeType = "application/pdf",
            chunkIndex = index,
            text = text,
        )
}
