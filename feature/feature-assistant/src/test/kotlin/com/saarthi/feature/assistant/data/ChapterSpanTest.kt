package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 1 P3 — chapter-span API (title-line → next chapter). */
class ChapterSpanTest {

    @Test
    fun `isChapterSpanQuery detects highlights and what does chapter say`() {
        assertTrue(isChapterSpanQuery("highlights from chapter VII"))
        assertTrue(isChapterSpanQuery("What does chapter 6 say about the Board"))
        assertTrue(isChapterSpanQuery("summarize chapter iv"))
        assertFalse(isChapterSpanQuery("how many chapters are there"))
        assertFalse(isChapterSpanQuery("list all chapters"))
        assertFalse(isChapterSpanQuery("appeal options in the act"))
    }

    @Test
    fun `chapterIdAliases maps roman and digit`() {
        assertTrue(chapterIdAliases("vii").contains("7"))
        assertTrue(chapterIdAliases("7").contains("vii"))
        assertTrue(chapterIdAliases("vi").contains("6"))
        assertEquals(7, chapterNumericId("VII"))
        assertEquals(6, chapterNumericId("6"))
    }

    @Test
    fun `chapterLineMatchTier prefers line-start CHAPTER over cross-ref`() {
        val aliases = chapterIdAliases("vii")
        val title = "CHAPTER VII APPEAL AND ALTERNATE DISPUTE RESOLUTION"
        val crossRef = "Interim orders under Chapter VII of this Act shall apply."
        assertTrue(chapterLineMatchTier(title, aliases)!! < chapterLineMatchTier(crossRef, aliases)!!)
    }

    @Test
    fun `findChapterTitleChunkIndex picks appeal chapter not inquiry cross-ref`() {
        val texts = listOf(
            "Section 108. Inquiry on interim orders under Chapter VII of this Act.",
            "CHAPTER VII\nAPPEAL AND ALTERNATE DISPUTE RESOLUTION",
            "61. Appeal to Appellate Tribunal within sixty days.",
            "CHAPTER VIII\nPENALTIES AND ADJUDICATION",
        )
        val idx = findChapterTitleChunkIndex(texts, chapterIdAliases("vii"))
        assertEquals(1, idx)
    }

    @Test
    fun `resolveChapterSpanWindow spans until next chapter header`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "Section 108. Inquiry under Chapter VII interim."),
            chunk(uri, 1, "CHAPTER VII\nAPPEAL AND ALTERNATE DISPUTE RESOLUTION"),
            chunk(uri, 2, "61. Appeal to Appellate Tribunal within sixty days."),
            chunk(uri, 3, "62. Appeal to Supreme Court."),
            chunk(uri, 4, "CHAPTER VIII\nPENALTIES AND ADJUDICATION"),
            chunk(uri, 5, "33. Monetary penalty provisions."),
        )
        val sorted = chunks.sortedBy { it.chunkIndex }
        val window = resolveChapterSpanWindow(sorted, "vii", maxChunks = 12)!!
        assertEquals(1, window.startChunkIndex)
        assertEquals(4, window.endChunkIndexExclusive)
        assertEquals(7, window.chapterNum)
    }

    @Test
    fun `resolveChapterSpanChunks matches chapter 6 to roman VI header`() {
        val uri = "content://act"
        val chunks = listOf(
            chunk(uri, 0, "CHAPTER V\nMANAGEMENT OF PERSONAL DATA"),
            chunk(uri, 1, "CHAPTER VI\nPROCESSING OF PERSONAL DATA OUTSIDE INDIA"),
            chunk(uri, 2, "40. Transfer of personal data outside India."),
            chunk(uri, 3, "CHAPTER VII\nAPPEAL AND ALTERNATE DISPUTE RESOLUTION"),
        )
        val span = resolveChapterSpanChunks(chunks, "highlights of chapter 6", maxChunks = 10)
        assertEquals(2, span.size)
        assertEquals(1, span.first().chunkIndex)
        assertTrue(span.any { it.text.contains("PROCESSING OF PERSONAL DATA OUTSIDE INDIA") })
    }

    @Test
    fun `chapter span query uses LIST shape for highlights`() {
        assertEquals(
            RagAnswerShape.LIST,
            detectRagAnswerShape("highlights of chapter VII", metaOverview = false),
        )
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
