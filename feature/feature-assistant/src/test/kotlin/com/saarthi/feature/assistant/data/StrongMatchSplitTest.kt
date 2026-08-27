package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 3 P14 — strongMatch requires document intent, not just anchor scores. */
class StrongMatchSplitTest {

    private val anchoredChunk = RetrievedChunk(
        text = "CHAPTER II\nObligations of Data Fiduciary",
        docName = "act.pdf",
        score = ANCHORED_CHUNK_SCORE,
        chunkIndex = 2,
        docUri = "content://act",
    )

    @Test
    fun `anchor hit without doc intent is not strong match`() {
        assertTrue(hasHighConfidenceRetrievalHit(listOf(anchoredChunk)))
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
    fun `anchor hit with doc question is strong match`() {
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
        val weak = anchoredChunk.copy(score = 1.5)
        assertFalse(hasHighConfidenceRetrievalHit(listOf(weak)))
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
}
