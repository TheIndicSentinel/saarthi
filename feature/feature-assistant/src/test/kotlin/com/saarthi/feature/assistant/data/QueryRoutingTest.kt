package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryRoutingTest {

    private val docs = listOf(
        "content://nda" to "Mallikarjuna Rao_NDA Agreement.pdf",
        "content://stmt" to "Account Statement.pdf",
        "content://log" to "saarthi_debug.log.txt",
    )

    @Test
    fun `filename stem agreement matches the NDA uri`() {
        val named = matchNamedDocs("इस agreement में term क्या है", docs)
        assertEquals(setOf("content://nda"), named)
    }

    @Test
    fun `routeQuery sets named boost and hindi expansion together`() {
        val route = routeQuery("इस agreement में जुर्माना क्या है", docs)
        assertEquals(setOf("content://nda"), route.namedDocUris)
        assertFalse(route.equalSlots)
        assertTrue(route.expandedQuery.contains("penalty"))
    }

    @Test
    fun `compare route sets equal slots without requiring a filename hit`() {
        val route = routeQuery("compare both files", docs)
        assertTrue(route.equalSlots)
        assertTrue(route.namedDocUris.isEmpty())
    }

    @Test
    fun `statement matches the bank file not the log`() {
        val named = matchNamedDocs("what is in the statement", docs)
        assertEquals(setOf("content://stmt"), named)
    }

    @Test
    fun `short stopwords do not match every file`() {
        assertTrue(matchNamedDocs("this document please", docs).isEmpty())
    }

    @Test
    fun `compare and dono set equal slots`() {
        assertTrue(isCompareQuery("compare both files"))
        assertTrue(isCompareQuery("दोनों compare करो"))
        assertFalse(isCompareQuery("what is the term"))
    }

    @Test
    fun `which file is detected`() {
        assertTrue(isWhichFileQuery("which file mentions salary"))
        assertTrue(isWhichFileQuery("कौन सी फ़ाइल"))
        assertFalse(isWhichFileQuery("what is the penalty"))
    }

    @Test
    fun `hindi query expands with english hints and filename stems`() {
        val expanded = expandRetrievalQuery("इसमें जुर्माना क्या है", listOf("NDA Agreement.pdf"))
        assertTrue(expanded.contains("agreement"))
        assertTrue(expanded.contains("penalty"))
        assertTrue(queryHasDevanagari("इसमें जुर्माना क्या है"))
    }

    @Test
    fun `latin query is not stuffed with hindi gloss`() {
        val expanded = expandRetrievalQuery("what is the term", listOf("NDA Agreement.pdf"))
        assertTrue(expanded.contains("agreement"))
        assertFalse(expanded.contains("penalty"))
    }
}
