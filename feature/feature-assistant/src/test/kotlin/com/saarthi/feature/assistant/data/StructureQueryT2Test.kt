package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1-2 — structure count/list routing and marker anchoring. */
class StructureQueryT2Test {

    @Test
    fun `structure count queries are detected`() {
        assertTrue(isStructureCountQuery("How many chapters are there in total"))
        assertTrue(isStructureCountQuery("List total number of chapter"))
        assertTrue(isStructureCountQuery("List total number of chapters mentioned"))
        assertTrue(isStructureCountQuery("number of sections in this document"))
        assertTrue(isStructureCountQuery("कुल अध्याय कितने हैं"))
    }

    @Test
    fun `structure list queries include list plus unit`() {
        assertTrue(isStructureListQuery("list all chapters in the act"))
        assertTrue(isStructureListQuery("enumerate sections"))
        assertTrue(isStructureListQuery("name all headings"))
    }

    @Test
    fun `structure queries bypass meta list and chapters tokens`() {
        val q = "List total number of chapters mentioned"
        assertTrue(bypassMetaForStructureQuery(q))
        assertNull(effectiveMetaRouteReason(q, isFollowUp = false))
        assertEquals("list", RagDocumentRepository.metaRouteReason(q))
    }

    @Test
    fun `structure list keeps LIST shape even when meta would overview`() {
        val q = "List total number of chapters mentioned"
        assertEquals(
            RagAnswerShape.LIST,
            detectRagAnswerShape(q, metaOverview = true),
        )
    }

    @Test
    fun `chapter marker tier prefers line-start CHAPTER`() {
        val header = "CHAPTER II\nRIGHTS AND DUTIES"
        val crossRef = "As provided in chapter ii of this act."
        assertTrue(chapterHeaderMatchTier(header)!! < chapterHeaderMatchTier(crossRef)!!)
    }

    @Test
    fun `pickStructureMarker prefers TOC chunk with many chapter lines`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "As provided in chapter ii of this act."),
            chunk(uri, 1, "CHAPTER I\nPRELIMINARY\nShort title."),
            chunk(
                uri,
                2,
                "CHAPTER I\nPRELIMINARY\nCHAPTER II\nOBLIGATIONS\nCHAPTER III\nRIGHTS\nCHAPTER IV\nSPECIAL PROVISIONS",
            ),
            chunk(uri, 3, "CHAPTER VIII\nPENALTIES AND ADJUDICATION"),
        )
        val picked = pickStructureMarkerChunkEntities(chunks, "total number of chapters", max = 2)
        assertEquals(2, picked.size)
        assertEquals(2, picked[0].chunkIndex)
    }

    @Test
    fun `pickStructureMarker ranks line-start chapter header chunks first`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "Opening preamble without structure markers."),
            chunk(uri, 1, "CHAPTER I\nPRELIMINARY\nShort title and definitions."),
            chunk(uri, 2, "CHAPTER II\nRIGHTS AND DUTIES OF DATA PRINCIPAL"),
            chunk(uri, 3, "Miscellaneous closing text."),
        )
        val picked = pickStructureMarkerChunkEntities(chunks, "list all chapters", max = 2)
        assertEquals(2, picked.size)
        assertEquals(1, picked[0].chunkIndex)
        assertEquals(2, picked[1].chunkIndex)
    }

    @Test
    fun `distinctStructureMarkers counts chapters across corpus`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "CHAPTER I\nPRELIMINARY"),
            chunk(uri, 1, "CHAPTER II\nOBLIGATIONS"),
            chunk(uri, 2, "CHAPTER III\nMORE"),
            chunk(uri, 3, "CHAPTER IV\nSPECIAL PROVISIONS"),
        )
        assertEquals(listOf("I", "II", "III", "IV"), distinctStructureMarkers(chunks, "chapter"))
    }

    @Test
    fun `buildStructureCountHint tells model not to guess`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "CHAPTER I\nA"),
            chunk(uri, 1, "CHAPTER II\nB"),
            chunk(uri, 2, "CHAPTER III\nC"),
        )
        val hint = buildStructureCountHint("total number of chapters", chunks)
        assertTrue(hint != null && hint.contains("3") && hint.contains("do not guess"))
    }

    @Test
    fun `distinctStructureMarkers counts mid-line gazette chapter markers`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "SEC. 1 THE GAZETTE OF INDIA EXTRAORDINARY 11 CHAPTER IV SPECIAL PROVISIONS"),
            chunk(uri, 1, "SEC. 1 THE GAZETTE OF INDIA EXTRAORDINARY 7 CHAPTER II OBLIGATIONS"),
        )
        val markers = distinctStructureMarkers(chunks, "chapter")
        assertTrue(markers.contains("IV"))
        assertTrue(markers.contains("II"))
    }

    @Test
    fun `chapterTitleLineScore matches appeal dispute query`() {
        val score = chapterTitleLineScore(
            "Appeal and dispute resolution",
            "CHAPTER VII APPEAL AND ALTERNATE DISPUTE RESOLUTION",
        )
        assertTrue(score >= 0.5)
    }

    private fun chunk(uri: String, index: Int, text: String): RagChunkEntity =
        RagChunkEntity(
            sessionId = "s",
            docUri = uri,
            docName = "act.pdf",
            mimeType = "application/pdf",
            chunkIndex = index,
            text = text,
        )
}
