package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalAnswerabilityTest {

    @Test
    fun `extract focus from how do oceans affect`() {
        val focus = extractQueryFocusEntities(
            "How do oceans affect Earth's climate system?",
        )
        assertTrue(focus.any { it.contains("ocean") })
    }

    @Test
    fun `extract focus from atmosphere role question`() {
        val focus = extractQueryFocusEntities(
            "What role does the atmosphere play in regulating Earth's temperature?",
        )
        assertTrue(focus.any { it.contains("atmosphere") })
    }

    @Test
    fun `list components question has no single focus entity`() {
        assertTrue(
            extractQueryFocusEntities(
                "What are the main components of Earth's climate system?",
            ).isEmpty(),
        )
    }

    @Test
    fun `hydrosphere alias covers ocean focus in corpus`() {
        val corpus = "The hydrosphere exerts a major control on climate through ocean currents."
        assertTrue(
            focusEntitiesCoveredInCorpus(
                listOf("oceans"),
                corpus,
            ),
        )
    }

    @Test
    fun `wind only corpus does not cover ocean focus`() {
        val corpus = "Wind is the driving force of weather when temperature creates pressure variation."
        assertFalse(
            focusEntitiesCoveredInCorpus(
                listOf("oceans"),
                corpus,
            ),
        )
    }

    @Test
    fun `answerability expansion includes alias terms`() {
        val expansion = answerabilityQueryExpansion(listOf("oceans"))
        assertTrue(expansion.contains("ocean"))
        assertTrue(expansion.contains("hydrosphere"))
    }

    @Test
    fun `prior query focus enriches expansion for follow-up entity`() {
        val expansion = answerabilityQueryExpansion(
            focusEntities = emptyList(),
            priorQuery = "How does the ocean exert control on climate?",
        )
        assertTrue(expansion.contains("ocean"))
        assertTrue(expansion.contains("hydrosphere"))
    }

    @Test
    fun `emit miss when focus not in retrieved corpus`() {
        val windChunk = RetrievedChunk(
            text = "Wind circulates when temperature differences create pressure variation.",
            docName = "guide.pdf",
            score = 8.0,
            chunkIndex = 2,
            docUri = "content://guide",
        )
        assertTrue(
            shouldEmitAnswerabilityRetrievalMiss(
                query = "How do oceans affect Earth's climate system?",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                retrieved = listOf(windChunk),
            ),
        )
    }

    @Test
    fun `no miss when ocean focus covered in corpus`() {
        val oceanChunk = RetrievedChunk(
            text = "The ocean exerts a major control on climate by absorbing solar energy.",
            docName = "guide.pdf",
            score = 6.0,
            chunkIndex = 4,
            docUri = "content://guide",
        )
        assertFalse(
            shouldEmitAnswerabilityRetrievalMiss(
                query = "How do oceans affect Earth's climate system?",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                retrieved = listOf(oceanChunk),
            ),
        )
    }

    @Test
    fun `overview queries skip answerability miss`() {
        assertFalse(
            shouldEmitAnswerabilityRetrievalMiss(
                query = "give an overview in short",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                retrieved = emptyList(),
            ),
        )
    }

    @Test
    fun `miss message names focus when known`() {
        val msg = buildAnswerabilityRetrievalMissMessage(
            "How do oceans affect Earth's climate system?",
        )
        assertTrue(msg.contains("ocean", ignoreCase = true))
        assertTrue(msg.contains("attached document", ignoreCase = true))
    }

    @Test
    fun `legal atmosphere role covered by atmosphere chunk`() {
        val chunk = RetrievedChunk(
            text = "The atmosphere traps heat through greenhouse gases and regulates surface temperature.",
            docName = "act.pdf",
            score = 5.0,
            chunkIndex = 3,
            docUri = "content://act",
        )
        assertFalse(
            shouldEmitAnswerabilityRetrievalMiss(
                query = "What role does the atmosphere play in regulating Earth's temperature?",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                retrieved = listOf(chunk),
            ),
        )
    }

    @Test
    fun `top organic hits gate answerability not unrelated high-score chunks`() {
        val windChunk = RetrievedChunk(
            text = "Wind circulates when temperature differences create pressure variation.",
            docName = "guide.pdf",
            score = 9.0,
            chunkIndex = 3,
            docUri = "content://guide",
        )
        val oceanChunk = RetrievedChunk(
            text = "The ocean exerts a major control on climate through currents.",
            docName = "guide.pdf",
            score = 1.0,
            chunkIndex = 6,
            docUri = "content://guide",
        )
        assertFalse(
            isRetrievalAnswerableForQuery(
                "How do oceans affect Earth's climate system?",
                listOf(windChunk),
            ),
        )
        assertTrue(
            isRetrievalAnswerableForQuery(
                "How do oceans affect Earth's climate system?",
                listOf(oceanChunk),
            ),
        )
    }
}
