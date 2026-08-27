package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 1 — turn mode classifier lifecycle matrix. */
class RagTurnModeTest {

    @Test
    fun `no docs is plain chat`() {
        assertEquals(
            RagTurnMode.PLAIN_CHAT,
            classifyRagTurnMode("Hi", sessionDocCount = 0, attachmentsThisTurn = false),
        )
        assertEquals(
            RagTurnMode.PLAIN_CHAT,
            classifyRagTurnMode("Explain photosynthesis", sessionDocCount = 0, attachmentsThisTurn = false),
        )
    }

    @Test
    fun `attach turn with overview is document grounded`() {
        assertEquals(
            RagTurnMode.DOCUMENT_GROUNDED,
            classifyRagTurnMode(
                ATTACH_BRIEF_OVERVIEW_QUERY,
                sessionDocCount = 1,
                attachmentsThisTurn = true,
            ),
        )
    }

    @Test
    fun `chapter question with docs is document grounded`() {
        assertEquals(
            RagTurnMode.DOCUMENT_GROUNDED,
            classifyRagTurnMode(
                "How many chapters are there",
                sessionDocCount = 1,
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `general knowledge with docs skips retrieval`() {
        assertEquals(
            RagTurnMode.GENERAL_KNOWLEDGE,
            classifyRagTurnMode(
                "Explain photosynthesis to a school kid",
                sessionDocCount = 1,
                attachmentsThisTurn = false,
            ),
        )
        assertEquals(
            RagTurnMode.GENERAL_KNOWLEDGE,
            classifyRagTurnMode(
                "Explain me black hole in general",
                sessionDocCount = 1,
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `explicit opt-out is general knowledge`() {
        assertEquals(
            RagTurnMode.GENERAL_KNOWLEDGE,
            classifyRagTurnMode(
                "Don't consider document and explain black hole",
                sessionDocCount = 1,
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `greeting with docs attached is general knowledge`() {
        assertEquals(
            RagTurnMode.GENERAL_KNOWLEDGE,
            classifyRagTurnMode("Hi", sessionDocCount = 1, attachmentsThisTurn = false),
        )
    }

    @Test
    fun `document opt-out detector`() {
        assertTrue(isDocumentOptOutQuery("don't consider the document"))
        assertTrue(isDocumentOptOutQuery("explain black hole in general"))
        assertFalse(isDocumentOptOutQuery("what are penalties in the act"))
    }
}
