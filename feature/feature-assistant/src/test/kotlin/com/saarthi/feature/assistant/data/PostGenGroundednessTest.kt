package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 6 P27 — post-gen groundedness for amounts, sections, and shall. */
class PostGenGroundednessTest {

    private val englishLabels = SupportedLanguage.ENGLISH.citationDisplayLabels()

    private fun chunk(text: String) = RetrievedChunk(
        text = text,
        docName = "act.pdf",
        score = 8.0,
        chunkIndex = 2,
        docUri = "content://act",
    )

    @Test
    fun `grounded penalty amount passes audit`() {
        val corpus = "THE SCHEDULE\nBreach category 5 — monetary penalty up to ₹125 crore"
        val answer = "Category 5 carries a penalty up to ₹125 crore."
        val audit = auditPostGenGroundedness(answer, corpus)
        assertTrue(audit.isFullyGrounded)
    }

    @Test
    fun `hallucinated crore amount fails audit`() {
        val corpus = "Penalty factors include nature gravity and repetition."
        val answer = "The maximum penalty is ₹999 crore for any breach."
        val audit = auditPostGenGroundedness(answer, corpus)
        assertFalse(audit.isFullyGrounded)
        assertTrue(audit.ungroundedAmounts.isNotEmpty())
    }

    @Test
    fun `section 33 must appear in corpus`() {
        val corpus = "33. Penalties\nThe Board may impose monetary penalties."
        assertTrue(isSectionNumberGrounded("33", corpus))
        val audit = auditPostGenGroundedness("Under Section 33 the Board may impose fines.", corpus)
        assertTrue(audit.isFullyGrounded)
    }

    @Test
    fun `ungrounded section reference fails audit`() {
        val corpus = "General obligations on data fiduciaries."
        val audit = auditPostGenGroundedness("Section 99 sets the maximum fine.", corpus)
        assertFalse(audit.isFullyGrounded)
        assertTrue(audit.ungroundedSections.contains("99"))
    }

    @Test
    fun `applyDeterministicSourcesFooter drops footer and adds caveat`() {
        val body = "The penalty is ₹999 crore for any breach."
        val out = applyDeterministicSourcesFooter(
            body,
            listOf(chunk("Factors include nature of breach.")),
            emptyMap(),
            englishLabels,
            claimOverlapQuery = "what is the penalty",
            claimOverlapTurnMode = RagTurnMode.DOCUMENT_GROUNDED,
        )
        assertTrue(out.contains(englishLabels.groundednessCaveat))
        assertFalse(out.contains(englishLabels.sourcesHeader))
    }

    @Test
    fun `grounded answer still receives sources footer`() {
        val corpus = "--- Page 17 ---\nPenalty may extend to two hundred crore rupees."
        val body = "The penalty may extend to two hundred crore rupees."
        val out = applyDeterministicSourcesFooter(
            body,
            listOf(chunk(corpus)),
            emptyMap(),
            englishLabels,
            claimOverlapQuery = "what is the penalty",
            claimOverlapTurnMode = RagTurnMode.DOCUMENT_GROUNDED,
        )
        assertTrue(out.contains(englishLabels.sourcesHeader))
        assertFalse(out.contains(englishLabels.groundednessCaveat))
    }
}
