package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 1 P4 — TOC vs body substance retrieval. */
class TocBodyTest {

    @Test
    fun `isTocLikeChunkText detects multi-chapter TOC block`() {
        val toc = """
            CHAPTER I
            PRELIMINARY
            CHAPTER II
            OBLIGATIONS
            CHAPTER III
            RIGHTS
            CHAPTER IV
            SPECIAL PROVISIONS
        """.trimIndent()
        assertTrue(isTocLikeChunkText(toc))
        assertFalse(isTocLikeChunkText("CHAPTER IV\nSPECIAL PROVISIONS\n41. Processing rules."))
    }

    @Test
    fun `usesTocForRetrieval only for structure count and list`() {
        assertTrue(usesTocForRetrieval("how many chapters are there"))
        assertTrue(usesTocForRetrieval("list all chapters"))
        assertFalse(usesTocForRetrieval("what are special provisions"))
        assertFalse(usesTocForRetrieval("highlights from chapter VII"))
    }

    @Test
    fun `locateHeadingInBodyChunks skips TOC for special provisions`() {
        val uri = "content://act"
        val sorted = listOf(
            chunk(uri, 0, "CHAPTER I\nPRELIMINARY\nCHAPTER II\nOBLIGATIONS\nCHAPTER IV\nSPECIAL PROVISIONS"),
            chunk(uri, 1, "CHAPTER IV\nSPECIAL PROVISIONS\n41. Processing in certain cases."),
            chunk(uri, 2, "42. More special provision operative text."),
            chunk(uri, 3, "CHAPTER V\nMANAGEMENT OF PERSONAL DATA"),
        )
        val idx = locateHeadingInBodyChunks(sorted, "SPECIAL PROVISIONS", substanceOnly = true)
        assertEquals(1, idx)
    }

    @Test
    fun `headingAnchorWindowInBody spans special provisions body not TOC`() {
        val uri = "content://act"
        val sorted = listOf(
            chunk(uri, 0, "CHAPTER I\nA\nCHAPTER II\nB\nCHAPTER IV\nSPECIAL PROVISIONS"),
            chunk(uri, 1, "CHAPTER IV\nSPECIAL PROVISIONS\n41. Body rule one."),
            chunk(uri, 2, "42. Body rule two."),
            chunk(uri, 3, "CHAPTER V\nNEXT CHAPTER"),
        )
        val headings = listOf("SPECIAL PROVISIONS", "CHAPTER V")
        val window = headingAnchorWindowInBody(sorted, "SPECIAL PROVISIONS", headings, maxChunks = 2)!!
        assertEquals(1, window.start)
        assertEquals(3, window.endExclusive)
    }

    @Test
    fun `chapter span skips TOC block for chapter VII`() {
        val uri = "content://act"
        val sorted = listOf(
            chunk(uri, 0, "CHAPTER VI\nA\nCHAPTER VII\nAPPEAL\nCHAPTER VIII\nPENALTIES"),
            chunk(uri, 1, "Section 108 mentions Chapter VII in inquiry context."),
            chunk(uri, 2, "CHAPTER VII\nAPPEAL AND ALTERNATE DISPUTE RESOLUTION"),
            chunk(uri, 3, "61. Appeal to Tribunal within sixty days."),
            chunk(uri, 4, "CHAPTER VIII\nPENALTIES AND ADJUDICATION"),
        )
        val window = resolveChapterSpanWindow(sorted, "vii", maxChunks = 10)!!
        assertEquals(2, window.startChunkIndex)
        assertEquals(4, window.endChunkIndexExclusive)
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
