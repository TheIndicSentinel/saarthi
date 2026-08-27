package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 3 P13 — claim overlap filters irrelevant Sources lines. */
class CitationClaimOverlapTest {

    private val englishLabels = SupportedLanguage.ENGLISH.citationDisplayLabels()

    private fun chunk(text: String, index: Int = 2) = RetrievedChunk(
        text = text,
        docName = "act.pdf",
        score = 5.0,
        chunkIndex = index,
        docUri = "content://act",
    )

    @Test
    fun `overlap drops chunk unrelated to answer`() {
        val dpdpa = chunk(
            "--- Page 17 ---\nPenalty may extend to two hundred crore rupees under this Act.",
        )
        val gkAnswer = "Black holes form when massive stars collapse under gravity."
        assertFalse(chunkSharesTokensWithAnswer(dpdpa, gkAnswer))
        assertTrue(filterChunksByClaimOverlap(listOf(dpdpa), gkAnswer).isEmpty())
    }

    @Test
    fun `overlap keeps chunk when answer uses excerpt terms`() {
        val dpdpa = chunk(
            "--- Page 17 ---\nPenalty may extend to two hundred crore rupees.",
        )
        val answer = "The penalty may extend to two hundred crore rupees for failure."
        assertTrue(chunkSharesTokensWithAnswer(dpdpa, answer))
        assertEquals(1, filterChunksByClaimOverlap(listOf(dpdpa), answer).size)
    }

    @Test
    fun `outline chunk always passes overlap`() {
        val outline = chunk("Digital Personal Data Protection Act 2023", index = -1)
        assertTrue(chunkSharesTokensWithAnswer(outline, "Photosynthesis converts light energy."))
    }

    @Test
    fun `structure count query skips overlap filter`() {
        assertFalse(
            shouldFilterSourcesByClaimOverlap(
                "How many chapters are there",
                RagTurnMode.DOCUMENT_GROUNDED,
            ),
        )
    }

    @Test
    fun `overview query skips overlap filter`() {
        assertFalse(
            shouldFilterSourcesByClaimOverlap(
                ATTACH_BRIEF_OVERVIEW_QUERY,
                RagTurnMode.DOCUMENT_GROUNDED,
            ),
        )
    }

    @Test
    fun `applyDeterministicSourcesFooter drops footer on zero overlap`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val outline = mapOf(hash to "Digital Personal Data Protection Act, 2023")
        val body = "Photosynthesis is how green plants make food from sunlight."
        val out = applyDeterministicSourcesFooter(
            body,
            listOf(
                chunk(
                    "--- Page 5 ---\nData Principal has the right to access personal data.",
                    index = 3,
                ).copy(docName = hash, docUri = "content://hash"),
            ),
            outline,
            englishLabels,
            claimOverlapQuery = "Explain photosynthesis",
            claimOverlapTurnMode = RagTurnMode.DOCUMENT_GROUNDED,
        )
        assertEquals(body, out)
        assertFalse(out.contains("Sources:"))
    }

    @Test
    fun `applyDeterministicSourcesFooter keeps footer when overlap exists`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val outline = mapOf(hash to "Digital Personal Data Protection Act, 2023")
        val body = "The penalty may extend to two hundred crore rupees."
        val out = applyDeterministicSourcesFooter(
            body,
            listOf(
                chunk("--- Page 17 ---\nPenalty may extend to two hundred crore rupees.", index = 4)
                    .copy(docName = hash, docUri = "content://hash"),
            ),
            outline,
            englishLabels,
            claimOverlapQuery = "What are penalties in the act",
            claimOverlapTurnMode = RagTurnMode.DOCUMENT_GROUNDED,
        )
        assertTrue(out.contains("Sources:"))
        assertTrue(out.contains("two hundred crore"))
        assertTrue(out.contains("Digital Personal Data"))
    }
}
