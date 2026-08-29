package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 3 P13 + Phase 2.1 — claim overlap pairs each Sources line to answer text. */
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
    fun `outline chunk exempt only on overview query`() {
        val outline = chunk("Digital Personal Data Protection Act 2023", index = -1)
        val unrelated = "Photosynthesis converts light energy."
        assertTrue(
            chunkSharesTokensWithAnswer(
                outline,
                unrelated,
                query = ATTACH_BRIEF_OVERVIEW_QUERY,
            ),
        )
        assertFalse(chunkSharesTokensWithAnswer(outline, unrelated, query = "What are penalties"))
    }

    @Test
    fun `answerBodyForClaimOverlap uses only From document block in MIXED`() {
        val body =
            "From document: Applicability covers processing of digital personal data.\n\n" +
                "General: Penalties may extend to two hundred crore rupees."
        val slice = answerBodyForClaimOverlap(body, RagTurnMode.MIXED)
        assertTrue(slice.contains("Applicability"))
        assertFalse(slice.contains("Penalties"))
    }

    @Test
    fun `mixed footer ignores general block for pairing`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val outline = mapOf(hash to "Digital Personal Data Protection Act, 2023")
        val body =
            "From document: Applicability is limited to digital personal data processing.\n\n" +
                "General: Penalties may extend to two hundred crore rupees."
        val penaltyChunk = chunk(
            "--- Page 17 ---\nPenalty may extend to two hundred crore rupees.",
            index = 4,
        ).copy(docName = hash, docUri = "content://hash")
        val applicabilityChunk = chunk(
            "--- Page 3 ---\nApplicability of this Act to processing of digital personal data.",
            index = 2,
        ).copy(docName = hash, docUri = "content://hash")
        val out = applyDeterministicSourcesFooter(
            body,
            listOf(penaltyChunk, applicabilityChunk),
            outline,
            englishLabels,
            claimOverlapQuery = "applicability from the document",
            claimOverlapTurnMode = RagTurnMode.MIXED,
        )
        assertTrue(out.contains("Applicability") || out.contains("page 3"))
        assertFalse(out.contains("page 17"))
    }

    @Test
    fun `pool page line dropped when answer does not overlap chunk`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val outline = mapOf(hash to "Digital Personal Data Protection Act, 2023")
        val penaltyChunk = chunk(
            "--- Page 17 ---\nPenalty may extend to two hundred crore rupees.",
            index = 4,
        ).copy(docName = hash, docUri = "content://hash")
        val applicabilityChunk = chunk(
            "--- Page 5 ---\nApplicability provisions for processing outside India.",
            index = 3,
        ).copy(docName = hash, docUri = "content://hash")
        val body = "Applicability provisions apply when processing digital personal data outside India."
        val out = applyDeterministicSourcesFooter(
            body,
            listOf(penaltyChunk, applicabilityChunk),
            outline,
            englishLabels,
            claimOverlapQuery = "applicability outside India",
            claimOverlapTurnMode = RagTurnMode.DOCUMENT_GROUNDED,
        )
        assertTrue(out.contains("page 5") || out.contains("Applicability"))
        assertFalse(out.contains("page 17"))
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
    fun `query terms count toward overlap pairing`() {
        val chunk = RetrievedChunk(
            text = "The ocean exerts a major control on climate through heat transport.",
            docName = "guide.pdf",
            score = 8.0,
            chunkIndex = 4,
            docUri = "content://guide",
        )
        val answer = "Oceans play a major role in regulating regional patterns."
        assertFalse(chunkSharesTokensWithAnswer(chunk, answer, minShared = 2))
        assertTrue(
            chunkSharesTokensWithAnswer(
                chunk,
                claimOverlapPairingCorpus(answer, "How do oceans affect climate?"),
                minShared = 2,
            ),
        )
    }

    @Test
    fun `shape route queries skip overlap filter`() {
        assertFalse(
            shouldFilterSourcesByClaimOverlap(
                "What is the difference between weather and climate?",
                RagTurnMode.DOCUMENT_GROUNDED,
            ),
        )
    }

    @Test
    fun `applyDeterministicSourcesFooter uses retrieval fallback when overlap empty`() {
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
        assertTrue(out.contains("Sources:"))
        assertTrue(out.contains("Photosynthesis"))
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
