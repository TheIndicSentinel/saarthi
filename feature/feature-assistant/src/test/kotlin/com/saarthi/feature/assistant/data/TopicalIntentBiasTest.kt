package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5.2 — indexed topical bias and weak-match gates. */
class TopicalIntentBiasTest {

    @Test
    fun `ambiguous topical subject without document cue words`() {
        assertTrue(hasAmbiguousTopicalSubjectCues("Breach notification timeline"))
        assertTrue(hasAmbiguousTopicalSubjectCues("Does it apply to minors"))
        assertTrue(hasAmbiguousTopicalSubjectCues("ye act bachchon par lagu hota hai"))
        assertFalse(hasAmbiguousTopicalSubjectCues("Tell me a joke"))
    }

    @Test
    fun `indexed topical without doc cues classifies grounded`() {
        assertEquals(
            RagTurnMode.DOCUMENT_GROUNDED,
            classifyRagTurnMode(
                query = "Breach notification timeline",
                sessionDocCount = 1,
                attachmentsThisTurn = false,
            ),
        )
        assertTrue(isIndexedSessionTopicalWithoutDocCues("Does it apply to minors"))
    }

    @Test
    fun `topical citation intent without explicit document phrase`() {
        assertTrue(
            isIndexedTopicalCitationIntent(
                "Does it apply to minors",
                RagTurnMode.DOCUMENT_GROUNDED,
            ),
        )
        assertTrue(
            isQueryAboutDocumentForCitation(
                "Does it apply to minors",
                RagTurnMode.DOCUMENT_GROUNDED,
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `weak topical miss when retrieval empty`() {
        assertTrue(
            shouldEmitIndexedTopicalWeakMiss(
                "Does it apply to minors",
                RagTurnMode.DOCUMENT_GROUNDED,
                emptyList(),
            ),
        )
        assertFalse(
            shouldEmitIndexedTopicalWeakMiss(
                "What are penalties in section 33",
                RagTurnMode.DOCUMENT_GROUNDED,
                emptyList(),
            ),
        )
    }

    @Test
    fun `weak topical miss on anchor only hits`() {
        val anchorOnly = RetrievedChunk(
            text = "CHAPTER VIII",
            docName = "act.pdf",
            score = 0.0,
            chunkIndex = 3,
            docUri = "uri",
            structuralAnchor = StructuralAnchorKind.HEADING,
        )
        assertTrue(
            shouldEmitIndexedTopicalWeakMiss(
                "Does it apply to minors",
                RagTurnMode.DOCUMENT_GROUNDED,
                listOf(anchorOnly),
            ),
        )
    }

    @Test
    fun `weak topical miss when body lacks subject tokens`() {
        val unrelated = RetrievedChunk(
            text = "Wind circulates when temperature differences create pressure variation.",
            docName = "guide.pdf",
            score = 4.0,
            chunkIndex = 2,
            docUri = "uri",
        )
        assertTrue(
            shouldEmitIndexedTopicalWeakMiss(
                "Does it apply to minors",
                RagTurnMode.DOCUMENT_GROUNDED,
                listOf(unrelated),
            ),
        )
    }

    @Test
    fun `topical subject covered when breach token in corpus`() {
        val body = RetrievedChunk(
            text = "Processing of personal data of children requires parental consent",
            docName = "act.pdf",
            score = 5.0,
            chunkIndex = 2,
            docUri = "uri",
        )
        assertFalse(
            shouldEmitIndexedTopicalWeakMiss(
                "Does it apply to minors",
                RagTurnMode.DOCUMENT_GROUNDED,
                listOf(body),
            ),
        )
    }
}
