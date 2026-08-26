package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B2-1 — strict section locate + section+penalty combo anchoring helpers. */
class SectionAnchoringB2Test {

    @Test
    fun `locateSection prefers explicit Section header over cross reference`() {
        val chunks = listOf(
            "Rights include correction. Breach in observance under section 15 may apply.",
            "Section 15. Breach in observance of the duties\n(a) comply with laws.",
            "Later penalties.",
        )
        val idx = locateSectionInChunks(chunks, SectionRef("section", "15"))
        assertEquals(1, idx)
    }

    @Test
    fun `locateSection prefers Section header over bare numbered list line`() {
        val chunks = listOf(
            "--- Page 11 ---\n15. minor editorial correction on earlier clause.",
            "Section 15. Breach in observance of the duties under this Act.",
        )
        val idx = locateSectionInChunks(chunks, SectionRef("section", "15"))
        assertEquals(1, idx)
    }

    @Test
    fun `locateSection still finds numbered act heading when no Section label`() {
        val chunks = listOf(
            "Earlier text.",
            "15. Duties of Data Principal\n(a) comply with laws.",
            "Later penalties.",
        )
        val idx = locateSectionInChunks(chunks, SectionRef("section", "15"))
        assertEquals(1, idx)
    }

    @Test
    fun `sectionHeaderMatchTier ranks explicit label above cross reference`() {
        val crossRef = "Rights include correction under section 15 for duties."
        val header = "Section 15. Breach in observance of duties"
        assertTrue(sectionHeaderMatchTier(header, "15")!! < sectionHeaderMatchTier(crossRef, "15")!!)
    }

    @Test
    fun `isSectionPenaltyComboQuery matches section plus penalty ask`() {
        assertTrue(isSectionPenaltyComboQuery("What is the penalty under section 15"))
        assertFalse(isSectionPenaltyComboQuery("What is section 15 about"))
        assertFalse(isSectionPenaltyComboQuery("What are the penalties in this document"))
    }

    @Test
    fun `pickPenaltySchedule prefers same document when requested`() {
        val actUri = "content://act"
        val otherUri = "content://other"
        val chunks = listOf(
            penaltyChunk(otherUri, 0, "Penalty for breach is Rs 5 lakh."),
            penaltyChunk(actUri, 1, "THE SCHEDULE\nMonetary penalty rows."),
            penaltyChunk(otherUri, 2, "THE SCHEDULE\nOther schedule text."),
        )
        val picked = pickPenaltyScheduleChunkEntities(chunks, preferDocUri = actUri, max = 2)
        assertEquals(2, picked.size)
        assertEquals(actUri, picked.first().docUri)
        assertTrue(picked.any { it.docUri == actUri })
    }

    private fun penaltyChunk(uri: String, index: Int, text: String): RagChunkEntity =
        RagChunkEntity(
            sessionId = "s",
            docUri = uri,
            docName = "doc.pdf",
            mimeType = "application/pdf",
            chunkIndex = index,
            text = text,
        )
}
