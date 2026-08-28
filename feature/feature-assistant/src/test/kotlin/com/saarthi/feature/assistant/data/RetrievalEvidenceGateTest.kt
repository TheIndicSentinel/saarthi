package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 — evidence gate and topical intent helpers. */
class RetrievalEvidenceGateTest {

    @Test
    fun `explicit lookup detects section and tabular queries`() {
        assertTrue(isExplicitLookupQuery("What is the Mumbai address in section 15"))
        assertTrue(isExplicitLookupQuery("what is the salary credit"))
        assertFalse(isExplicitLookupQuery("Tell me a joke"))
    }

    @Test
    fun `miss when explicit lookup has only structural anchors`() {
        val anchorOnly = RetrievedChunk(
            text = "CHAPTER VIII",
            docName = "act.pdf",
            score = 0.0,
            chunkIndex = 3,
            docUri = "content://act",
            structuralAnchor = StructuralAnchorKind.HEADING,
        )
        assertTrue(
            shouldEmitDeterministicRetrievalMiss(
                query = "What is the Mumbai address from the document",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                retrieved = listOf(anchorOnly),
            ),
        )
    }

    @Test
    fun `no miss when lexical hit supports explicit lookup`() {
        val hit = RetrievedChunk(
            text = "Office address Mumbai Maharashtra",
            docName = "act.pdf",
            score = 6.0,
            chunkIndex = 2,
            docUri = "content://act",
        )
        assertFalse(
            shouldEmitDeterministicRetrievalMiss(
                query = "What is the Mumbai address",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                retrieved = listOf(hit),
            ),
        )
    }

    @Test
    fun `indexed topical question classifies as document grounded`() {
        assertEquals(
            RagTurnMode.DOCUMENT_GROUNDED,
            classifyRagTurnMode(
                query = "Is this act applicable to children's personal data",
                sessionDocCount = 1,
                attachmentsThisTurn = false,
            ),
        )
        assertEquals(
            RagTurnMode.PLAIN_CHAT,
            classifyRagTurnMode(
                query = "Tell me a joke",
                sessionDocCount = 1,
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `follow-up topic carry grounds continuation after topical prior`() {
        assertEquals(
            RagTurnMode.DOCUMENT_GROUNDED,
            classifyRagTurnMode(
                query = "also explain more",
                sessionDocCount = 1,
                attachmentsThisTurn = false,
                priorQuery = "Is this act applicable to children's data",
            ),
        )
    }

    @Test
    fun `follow-up scope upgrade grounds section follow-up after topical prior`() {
        assertEquals(
            RagTurnMode.DOCUMENT_GROUNDED,
            classifyRagTurnMode(
                query = "what about section 15",
                sessionDocCount = 1,
                attachmentsThisTurn = false,
                priorQuery = "Is this applicable to processing",
            ),
        )
    }
}
