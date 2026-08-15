package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Point 11 — one JVM test per product case. Composes helpers from points
 * 1–10. No Room, no ChatRepositoryImpl.
 *
 * Detach → [RagDocumentRepository.deleteByDoc] is the production path
 * (chip remove). Not asserted here without a DAO.
 */
class RagCaseContractTest {

    private val nda = "content://nda" to "Mallikarjuna Rao_NDA Agreement.pdf"
    private val stmt = "content://stmt" to "Account Statement.pdf"
    private val log = "content://log" to "saarthi_debug.log.txt"
    private val docs = listOf(nda, stmt, log)

    private fun chunk(
        uri: String,
        score: Double,
        name: String = uri,
        index: Int = 0,
        text: String = "$name-$index",
    ) = RetrievedChunk(
        text = text,
        docName = name,
        score = score,
        chunkIndex = index,
        docUri = uri,
    )

    @Test
    fun `single-file follow-up has no new-files notice`() {
        assertEquals("", newFilesThisTurnNotice(emptyList()))
        assertFalse(isCompareQuery("and the penalty?"))
    }

    @Test
    fun `A then B about B prefers this-turn B`() {
        val notice = newFilesThisTurnNotice(listOf(shortDocName(stmt.second)))
        assertTrue(notice.contains(shortDocName(stmt.second)))
        assertTrue(notice.contains("do not reuse answers about earlier documents"))
        val hits = listOf(
            chunk(nda.first, score = 10.0, name = nda.second),
            chunk(stmt.first, score = 9.0, name = stmt.second),
        )
        val boosted = applySessionBoost(
            hits,
            boostDocUris = setOf(stmt.first),
            recencyUri = stmt.first,
            namedDocUris = emptySet(),
        )
        assertTrue(
            boosted.first { it.docUri == stmt.first }.score >
                boosted.first { it.docUri == nda.first }.score,
        )
        assertTrue(boosted.any { it.docUri == nda.first })
    }

    @Test
    fun `A then B about A still names A and does not drop it`() {
        val named = matchNamedDocs("इस agreement में term क्या है", docs)
        assertEquals(setOf(nda.first), named)
        val hits = listOf(
            chunk(nda.first, 8.0, nda.second),
            chunk(stmt.first, 8.0, stmt.second),
        )
        val boosted = applySessionBoost(
            hits,
            boostDocUris = setOf(stmt.first),
            recencyUri = stmt.first,
            namedDocUris = named,
        )
        assertTrue(boosted.any { it.docUri == nda.first })
        assertTrue(boosted.any { it.docUri == stmt.first })
    }

    @Test
    fun `two-file compare keeps both names and equal slots`() {
        val route = routeQuery("compare both files", docs)
        assertTrue(route.equalSlots)
        val hits = (0 until 8).map { i -> chunk(nda.first, 10.0 - i, nda.second, i) } +
            (0 until 8).map { i -> chunk(stmt.first, 0.4 - i * 0.01, stmt.second, i) }
        val allocated = allocatePerDocSlots(hits, topK = 8, minPerDoc = 4)
        assertEquals(4, allocated.count { it.docUri == nda.first })
        assertEquals(4, allocated.count { it.docUri == stmt.first })
        val manifest = sessionManifestLine(listOf(nda.second, stmt.second))
        assertTrue(manifest.contains(shortDocName(nda.second)))
        assertTrue(manifest.contains(shortDocName(stmt.second)))
        assertTrue(ragCitationRules(compact = false).contains("cite each file that contributed"))
    }

    @Test
    fun `unreadable B is an error and must not be cited`() {
        val msg = extractionFailureMessage("[PDF: Scan had little readable text]")
        assertNotNull(msg)
        assertTrue(isUnreadableThisTurn(error = msg, extractedText = null))
        assertTrue(UNREADABLE_FILES_INTRO.contains("do not cite them"))
        assertEquals("", newFilesThisTurnNotice(emptyList()))
    }

    @Test
    fun `hindi question on english PDFs expands and is not only file 1`() {
        val route = routeQuery("इस agreement में जुर्माना क्या है", docs)
        assertTrue(route.expandedQuery.contains("penalty"))
        assertEquals(setOf(nda.first), route.namedDocUris)
        assertFalse(route.namedDocUris.contains(log.first))
        assertTrue(queryHasDevanagari("इस agreement में जुर्माना क्या है"))
    }

    @Test
    fun `header format matches manifest short names`() {
        val raw = nda.second
        val name = shortDocName(raw)
        val header = formatExcerptHeader(1, raw, "--- Page 3 ---\nTerm", chunkIndex = 0)
        val manifest = sessionManifestLine(listOf(raw, stmt.second))
        assertEquals("[1] $name · p.3\n", header)
        assertTrue(manifest.contains(name))
        assertTrue(manifest.contains(shortDocName(stmt.second)))
    }

    @Test
    fun `restart empty this-turn boost still has both docs and recency`() {
        val hits = listOf(
            chunk(nda.first, 10.0, nda.second),
            chunk(stmt.first, 9.0, stmt.second),
        )
        val boosted = applySessionBoost(
            hits,
            boostDocUris = emptySet(),
            recencyUri = stmt.first,
        )
        assertEquals(2, boosted.map { it.docUri }.distinct().size)
        assertEquals(9.0 * RECENCY_BOOST, boosted.first { it.docUri == stmt.first }.score, 1e-9)
        assertEquals(10.0, boosted.first { it.docUri == nda.first }.score, 1e-9)
    }

    @Test
    fun `ocr and extract sentinels are failures not indexable content`() {
        assertNotNull(extractionFailureMessage("[Image: No text detected in this image]"))
        assertNotNull(extractionFailureMessage("[PDF: No readable text found]"))
        assertNotNull(extractionFailureMessage("[PDF: Scan had little readable text]"))
        assertEquals(null, extractionFailureMessage("Account Statement\nBalance: 12000"))
        assertFalse(pdfExtractLooksUsable("Page 1\nDate: 12/03"))
    }

    @Test
    fun `zero-score multi-file fallback is per-doc contiguous`() {
        val scatter = listOf(
            chunk(log.first, 0.0, log.second, index = 0),
            chunk(nda.first, 0.0, nda.second, index = 0),
            chunk(log.first, 0.0, log.second, index = 6),
            chunk(nda.first, 0.0, nda.second, index = 6),
        )
        val fallback = listOf(
            chunk(log.first, 1.0, log.second, index = -1),
            chunk(log.first, 0.0, log.second, index = 0),
            chunk(log.first, 0.0, log.second, index = 1),
            chunk(nda.first, 1.0, nda.second, index = -1),
            chunk(nda.first, 0.0, nda.second, index = 0),
            chunk(nda.first, 0.0, nda.second, index = 1),
        )
        val resolved = keepOrFallback(scatter, fallback)
        assertEquals(listOf(0, 1), resolved.filter { it.docUri == log.first && it.chunkIndex >= 0 }.map { it.chunkIndex })
        assertEquals(listOf(0, 1), resolved.filter { it.docUri == nda.first && it.chunkIndex >= 0 }.map { it.chunkIndex })
        assertEquals(listOf(0, 1, 2, 3), pickZeroScoreBodyIndices(10, 4, ZeroScorePick.CONTIGUOUS))
    }
}
