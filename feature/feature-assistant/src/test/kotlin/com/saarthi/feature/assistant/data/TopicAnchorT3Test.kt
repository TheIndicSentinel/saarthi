package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1-3 — topic/heading anchors for named topics (appeal, provisions, …). */
class TopicAnchorT3Test {

    private val appealOutline = listOf(
        "PRELIMINARY",
        "RIGHTS AND DUTIES OF DATA PRINCIPAL",
        "APPEAL AND ALTERNATE DISPUTE RESOLUTION",
        "PENALTIES AND ADJUDICATION",
    )

    @Test
    fun `activeTopicCategories detects appeal and dispute topics`() {
        assertTrue(activeTopicCategories("Appeal and alternative dispute resolution").any { it.id == "appeal" })
        assertTrue(activeTopicCategories("what is the arbitration process").any { it.id == "appeal" })
        assertTrue(activeTopicCategories("dispute mediation options").any { it.id == "dispute" })
    }

    @Test
    fun `activeTopicCategories detects provision and eligibility topics`() {
        assertTrue(activeTopicCategories("What are special provisions").any { it.id == "provision" })
        assertTrue(
            activeTopicCategories("disqualification for appointment").any { it.id == "eligibility" },
        )
    }

    @Test
    fun `fuzzy heading match handles alternate vs alternative wording`() {
        val matched = matchHeadingFuzzy(
            "appeal and alternative dispute resolution",
            appealOutline,
        )
        assertEquals("APPEAL AND ALTERNATE DISPUTE RESOLUTION", matched)
    }

    @Test
    fun `strict heading match still preferred over fuzzy`() {
        val headings = listOf("SPECIAL PROVISIONS", "PENALTIES AND ADJUDICATION")
        assertEquals("SPECIAL PROVISIONS", matchHeading("What are special provisions", headings))
    }

    @Test
    fun `partial overlap does not fuzzy anchor on single token`() {
        assertNull(matchHeadingFuzzy("what are my rights", listOf("RIGHTS AND DUTIES OF DATA PRINCIPAL")))
    }

    @Test
    fun `pickTopicAnchor prefers appeal header chunk over penalty neighborhood`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "Chapter VIII penalties and monetary fines under the Schedule."),
            chunk(uri, 1, "APPEAL AND ALTERNATE DISPUTE RESOLUTION\nAn appeal may be filed."),
            chunk(uri, 2, "The Board shall conduct inquiry for penalties."),
        )
        val picked = pickTopicAnchorChunkEntities(
            chunks,
            "Appeal and alternative dispute resolution",
            max = 2,
        )
        assertEquals(1, picked.size)
        assertEquals(1, picked[0].chunkIndex)
        assertTrue(picked[0].text.contains("APPEAL"))
    }

    @Test
    fun `topic expansion adds appeal lexicon for BM25`() {
        val expansion = topicAnchorQueryExpansion("Appeal and alternative dispute resolution")
        assertTrue(expansion.contains("appeal"))
        assertTrue(expansion.contains("tribunal"))
    }

    @Test
    fun `list chapters does not trigger provision topic category`() {
        assertTrue(activeTopicCategories("list all chapters").isEmpty())
    }

    private fun chunk(uri: String, index: Int, text: String): RagChunkEntity =
        RagChunkEntity(
            sessionId = "s",
            docUri = uri,
            docName = "doc.pdf",
            mimeType = "application/pdf",
            chunkIndex = index,
            text = text,
        )
}
