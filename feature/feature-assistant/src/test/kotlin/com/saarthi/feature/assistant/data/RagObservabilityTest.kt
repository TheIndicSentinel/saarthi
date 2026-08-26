package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagObservabilityTest {

    @Test
    fun `search line has path timings and route without query text`() {
        val line = ragSearchLogLine(
            docCount = 2,
            boostCount = 1,
            path = RagSearchPath.bm25,
            hitCount = 4,
            queryLen = 18,
            searchMs = 12,
            named = 1,
            metaReason = "list",
            headingChunks = 3,
        )
        assertEquals(
            "docs=2 boost=1 path=bm25 hits=4 queryLen=18 searchMs=12 named=1 equal=0 whichFile=0 thisDoc=0 followUp=0 meta=list headingChunks=3",
            line,
        )
        assertFalse(line.contains("penalty"))
        assertFalse(line.contains("content://"))
        assertFalse(line.contains("Offices"))
    }

    @Test
    fun `offices list query is tagged meta list not the question text`() {
        assertEquals("list", RagDocumentRepository.metaRouteReason("Offices ke list do"))
        assertEquals("overview", RagDocumentRepository.metaRouteReason("Document content ka overview do"))
        assertEquals(null, RagDocumentRepository.metaRouteReason("Pune office ka address do"))
        assertEquals(null, RagDocumentRepository.metaRouteReason("What is journey to compliance"))
    }

    @Test
    fun `native-script summary queries route via the indic meta path`() {
        assertEquals("indic", RagDocumentRepository.metaRouteReason("இந்த ஆவணத்தின் சுருக்கம் தரவும்")) // Tamil
        assertEquals("indic", RagDocumentRepository.metaRouteReason("সারাংশ দাও")) // Bengali
        assertEquals("indic", RagDocumentRepository.metaRouteReason("ఈ పత్రం సారాంశం")) // Telugu
        assertEquals("indic", RagDocumentRepository.metaRouteReason("ಈ ದಾಖಲೆಯ ಸಾರಾಂಶ")) // Kannada
        // Devanagari path is unchanged; a plain question is still not meta.
        assertEquals("devanagari", RagDocumentRepository.metaRouteReason("दस्तावेज़ का सारांश दो"))
        assertEquals(null, RagDocumentRepository.metaRouteReason("சம்பளம் எவ்வளவு")) // Tamil "what is the salary"
    }

    @Test
    fun `chunk line uses nameLen not the filename`() {
        val line = ragChunkLogLine(1, nameLen = 24, chunkIndex = 2, page = "p.4", score = 1.5)
        assertEquals("  [1] nameLen=24 · part 3 · p.4  score=1.50", line)
        assertFalse(line.contains("NDA"))
        val outline = ragChunkLogLine(1, nameLen = 8, chunkIndex = -1, page = null, score = 1.0)
        assertTrue(outline.contains("outline"))
        assertFalse(outline.contains("p."))
    }

    @Test
    fun `index fail logs class not message or filename`() {
        val line = ragIndexFailLogLine(nameLen = 11, exceptionName = "SQLiteException")
        assertEquals("index failed nameLen=11 ex=SQLiteException", line)
        assertFalse(line.contains("statement.pdf"))
        assertFalse(line.contains("database is locked"))
    }

    @Test
    fun `index success is lengths and indexMs`() {
        val line = ragIndexLogLine(21, 9823, hasOutline = true, nameLen = 12, sessionIdLen = 8, indexMs = 40)
        assertTrue(line.contains("indexMs=40"))
        assertTrue(line.contains("nameLen=12"))
        assertFalse(line.contains("Agreement"))
    }

    @Test
    fun `raw preview collapses whitespace and caps at 200c`() {
        val raw = "Please  ask\n\nthe question. " + "x".repeat(300)
        val preview = ragRawModelPreview(raw)
        assertEquals(RAG_RAW_PREVIEW_CHARS, preview.length)
        assertFalse(preview.contains("\n"))
        assertTrue(preview.startsWith("Please ask the question."))
    }

    @Test
    fun `generation line omits preview and document text by default`() {
        val line = ragGenerationLogLine(
            rawChars = 38,
            priorTurnsChars = 120,
            uriLens = listOf(14, 22),
        )
        assertEquals(
            "gen rawChars=38 priorTurnsChars=120 promptDocs=2 uriLens=14,22",
            line,
        )
        assertFalse(line.contains("content://"))
        assertFalse(line.contains("कृपया"))
    }

    @Test
    fun `generation debug preview is appended without URIs`() {
        val line = ragGenerationLogLine(
            rawChars = 12,
            priorTurnsChars = 0,
            uriLens = listOf(8),
            preview = "I am not sure",
        )
        assertTrue(line.contains("preview=I am not sure"))
        assertTrue(line.contains("promptDocs=1"))
        assertFalse(line.contains("content://"))
    }

    @Test
    fun `FTS5 is not warranted at current session scale`() {
        assertFalse(fts5IsWarranted(chunkCount = 40, searchMs = 12))
        assertTrue(fts5IsWarranted(chunkCount = 501, searchMs = 12))
        assertTrue(fts5IsWarranted(chunkCount = 40, searchMs = 51))
        val line = ragFts5CandidateLogLine(520, 60)
        assertEquals("fts5-candidate chunks=520 searchMs=60", line)
        assertFalse(line.contains("content://"))
    }

    @Test
    fun `search log includes fts flag when prefilter used`() {
        val line = ragSearchLogLine(
            docCount = 1,
            boostCount = 0,
            path = RagSearchPath.bm25,
            hitCount = 4,
            queryLen = 12,
            searchMs = 8,
            ftsPrefilter = true,
        )
        assertTrue(line.contains("fts=1"))
    }
}
