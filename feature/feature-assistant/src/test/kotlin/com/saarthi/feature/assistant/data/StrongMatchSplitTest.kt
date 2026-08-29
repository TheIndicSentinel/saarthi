package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 3 P14 + Phase 0.1 — strongMatch requires lexical overlap, not anchor injection. */
class StrongMatchSplitTest {

    private val anchoredChunk = RetrievedChunk(
        text = "CHAPTER II\nObligations of Data Fiduciary",
        docName = "act.pdf",
        score = 0.0,
        chunkIndex = 2,
        docUri = "content://act",
        structuralAnchor = StructuralAnchorKind.HEADING,
    )

    @Test
    fun `structural anchor without doc intent is not strong match`() {
        assertFalse(
            hasHighConfidenceRetrievalHit(
                query = "Explain black holes to a kid",
                retrieved = listOf(anchoredChunk),
            ),
        )
        assertFalse(
            shouldUseStrongMatchPromptRules(
                retrieved = listOf(anchoredChunk),
                query = "Explain black holes to a kid",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `structural anchor with doc question and overlap is strong match`() {
        assertTrue(
            hasHighConfidenceRetrievalHit(
                query = "What are obligations in the act",
                retrieved = listOf(anchoredChunk),
            ),
        )
        assertTrue(
            shouldUseStrongMatchPromptRules(
                retrieved = listOf(anchoredChunk),
                query = "What are obligations in the act",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `weak bm25 alone is not strong match`() {
        val weak = anchoredChunk.copy(score = 1.5, structuralAnchor = null)
        assertFalse(
            hasHighConfidenceRetrievalHit(
                query = "What are penalties in the act",
                retrieved = listOf(weak),
            ),
        )
        assertFalse(
            shouldUseStrongMatchPromptRules(
                retrieved = listOf(weak),
                query = "What are penalties in the act",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                attachmentsThisTurn = false,
            ),
        )
    }

    @Test
    fun `assemble uses weak rules for off-topic anchor hit`() {
        val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()
        val result = assembleRagPromptBlock(
            retrieved = listOf(anchoredChunk),
            unreadableThisTurn = emptyList(),
            tier = com.saarthi.core.inference.prompt.SystemPromptProvider.ModelTier.STANDARD,
            charBudget = 4000,
            citationLabels = labels,
            ragQuery = "Tell me a joke",
            turnMode = RagTurnMode.DOCUMENT_GROUNDED,
            attachmentsThisTurn = false,
        )
        assertTrue(result.block.isNotEmpty())
        assertTrue(
            result.block.contains("NOT about the document") ||
                result.block.contains("not about the document"),
        )
    }

    @Test
    fun `strong match blocked when top organic misses focus entity`() {
        val windChunk = RetrievedChunk(
            text = "Wind is the driving force of weather when temperature creates pressure variation.",
            docName = "guide.pdf",
            score = 9.0,
            chunkIndex = 2,
            docUri = "content://guide",
        )
        assertFalse(
            shouldUseStrongMatchPromptRules(
                retrieved = listOf(windChunk),
                query = "How do oceans affect Earth's climate system?",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                attachmentsThisTurn = false,
            ),
        )
    }
}
