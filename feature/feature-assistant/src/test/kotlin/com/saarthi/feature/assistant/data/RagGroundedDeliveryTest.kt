package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import com.saarthi.core.inference.prompt.SystemPromptProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 1 — grounded excerpt assembly and delivery invariant. */
class RagGroundedDeliveryTest {

  private val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()

    @Test
    fun `force grounded fits smallest content when huge outline would not fit`() {
        val hugeOutline = RetrievedChunk(
            text = "CHAPTER I\n" + "PRELIMINARY section line\n".repeat(400),
            docName = "Act.pdf",
            score = 1.35,
            chunkIndex = -1,
            docUri = "content://a",
        )
        val smallBody = RetrievedChunk(
            text = "The Act may be called the Digital Personal Data Protection Act, 2023.",
            docName = "Act.pdf",
            score = 0.0,
            chunkIndex = 1,
            docUri = "content://a",
        )
        val retrieved = listOf(hugeOutline, smallBody)
        val result = assembleRagPromptBlock(
            retrieved = retrieved,
            unreadableThisTurn = emptyList(),
            tier = SystemPromptProvider.ModelTier.LARGE,
            charBudget = 800,
            citationLabels = labels,
            forceGroundedDelivery = true,
        )
        assertFalse(result.groundedDeliveryFailed)
        assertTrue(result.block.isNotEmpty())
        assertTrue(result.block.contains("Digital Personal Data Protection Act"))
    }

    @Test
    fun `without force grounded empty block is ok when budget tight`() {
        val hugeOutline = RetrievedChunk(
            text = "X".repeat(2000),
            docName = "Act.pdf",
            score = 1.0,
            chunkIndex = -1,
            docUri = "content://a",
        )
        val result = assembleRagPromptBlock(
            retrieved = listOf(hugeOutline),
            unreadableThisTurn = emptyList(),
            tier = SystemPromptProvider.ModelTier.LARGE,
            charBudget = 400,
            citationLabels = labels,
            forceGroundedDelivery = false,
        )
        assertFalse(result.groundedDeliveryFailed)
        assertTrue(result.block.isEmpty())
    }

    @Test
    fun `force grounded fails only when hits exist but nothing fits`() {
        val huge = RetrievedChunk(
            text = "Z".repeat(5000),
            docName = "Act.pdf",
            score = 1.0,
            chunkIndex = 1,
            docUri = "content://a",
        )
        val result = assembleRagPromptBlock(
            retrieved = listOf(huge),
            unreadableThisTurn = emptyList(),
            tier = SystemPromptProvider.ModelTier.LARGE,
            charBudget = 50,
            citationLabels = labels,
            forceGroundedDelivery = true,
        )
        assertTrue(result.groundedDeliveryFailed)
        assertTrue(result.block.isEmpty())
    }
}
