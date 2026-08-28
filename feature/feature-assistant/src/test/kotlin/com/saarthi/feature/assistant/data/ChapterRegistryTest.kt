package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 2 — chapter registry at index time + structure hints. */
class ChapterRegistryTest {

    @Test
    fun `buildDocumentChapterRegistry skips TOC and normalizes VI`() {
        val chunks = listOf(
            "CHAPTER I\nPRELIMINARY\nShort title.",
            "CHAPTER II\nOBLIGATIONS OF DATA FIDUCIARY",
            "CHAPTER VI\nPROCESSING OF PERSONAL DATA OUTSIDE INDIA\n40. Transfer rules.",
            "CHAPTER VII\nAPPEAL AND ALTERNATE DISPUTE RESOLUTION",
        )
        val registry = buildDocumentChapterRegistry(chunks)
        assertEquals(4, registry.chapters.size)
        assertEquals("VI", registry.chapters.find { it.chapterNum == 6 }?.romanId)
        assertTrue(registry.chapters.any { it.title.contains("PROCESSING") })
    }

    @Test
    fun `encode and parse registry round trip`() {
        val registry = DocumentChapterRegistry(
            listOf(
                ChapterRegistryEntry(1, "I", "PRELIMINARY", 0),
                ChapterRegistryEntry(2, "II", "OBLIGATIONS", 1),
            ),
        )
        val parsed = parseChapterRegistry(encodeChapterRegistry(registry))
        assertEquals(2, parsed.chapters.size)
        assertEquals("II", parsed.chapters[1].romanId)
    }

    @Test
    fun `buildRegistryCountHint uses titles not marker soup`() {
        val chapters = listOf(
            ChapterRegistryEntry(1, "I", "PRELIMINARY", 0),
            ChapterRegistryEntry(2, "II", "OBLIGATIONS OF DATA FIDUCIARY", 1),
        )
        val hint = buildRegistryCountHint(chapters)
        assertTrue(hint.contains("2 chapters"))
        assertTrue(hint.contains("PRELIMINARY"))
        assertTrue(hint.contains("OBLIGATIONS"))
        assertTrue(hint.contains("indexed registry"))
    }

    @Test
    fun `buildStructureListHint lists registry titles`() {
        val registries = mapOf(
            "doc" to DocumentChapterRegistry(
                listOf(
                    ChapterRegistryEntry(1, "I", "PRELIMINARY", 0),
                    ChapterRegistryEntry(7, "VII", "APPEAL AND ALTERNATE DISPUTE RESOLUTION", 2),
                ),
            ),
        )
        val hint = buildStructureListHint("list all chapters", registries)!!
        assertTrue(hint.contains("Chapter I: PRELIMINARY"))
        assertTrue(hint.contains("Chapter VII: APPEAL"))
        assertTrue(hint.contains("exact titles"))
    }

    @Test
    fun `computeChunkMetadata assigns chapter id and page`() {
        val chunks = listOf(
            "--- Page 3 ---\nCHAPTER II\nOBLIGATIONS",
            "Section 5 duties continue.",
        )
        val registry = buildDocumentChapterRegistry(chunks)
        val meta = computeChunkMetadata(chunks, registry)
        assertEquals("II", meta[0].chapterId)
        assertEquals(ChunkRole.HEADING, meta[0].role)
        assertEquals(3, meta[0].pageNum)
        assertEquals("II", meta[1].chapterId)
        assertEquals("5", meta[1].sectionNum)
    }

    @Test
    fun `digit chapter query maps to roman in registry`() {
        val chunks = listOf(
            "CHAPTER 6\nPROCESSING OUTSIDE INDIA",
        )
        val registry = buildDocumentChapterRegistry(chunks)
        assertEquals(6, registry.chapters.single().chapterNum)
        assertEquals("VI", registry.chapters.single().romanId)
    }
}
