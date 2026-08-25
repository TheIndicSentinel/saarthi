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
    fun `compare token on a single doc does not set equal slots`() {
        // G4: a stray "vs"/"compare" token must not force compare mode when
        // there is only one document in the session to compare against.
        val single = listOf("content://only" to "Godrej.pdf")
        assertTrue(isCompareQuery("Godrej vs the rules"))
        assertFalse(routeQuery("Godrej vs the rules", single).equalSlots)
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
    fun `native-script compare phrases set compare`() {
        assertTrue(isCompareQuery("இரண்டும் ஒப்பிடு"))   // Tamil both/compare
        assertTrue(isCompareQuery("দুটো তুলনা করো"))       // Bengali
        assertTrue(isCompareQuery("ಎರಡೂ ಹೋಲಿಕೆ"))          // Kannada
        assertFalse(isCompareQuery("சம்பளம் என்ன"))        // Tamil "what is salary"
    }

    @Test
    fun `native-script which-file and this-document phrases detected`() {
        assertTrue(isWhichFileQuery("எந்த கோப்பு சம்பளம் சொல்கிறது")) // Tamil which file
        assertTrue(isWhichFileQuery("কোন ফাইল"))                        // Bengali
        assertTrue(isThisDocumentQuery("এই নথি"))                       // Bengali this document
        assertTrue(isThisDocumentQuery("ఈ ఫైల్"))                        // Telugu this file
    }

    @Test
    fun `romanized khata query matches the english-named account file`() {
        val named = matchNamedDocs("khata ka detail batao", docs)
        assertEquals(setOf("content://stmt"), named)
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

    @Test
    fun `non-devanagari indic query still gets english hints`() {
        // Tamil: "ஒப்பந்தத்தில் அபராதம் என்ன" (what is the penalty in the agreement)
        val q = "ஒப்பந்தத்தில் அபராதம் என்ன"
        assertTrue(queryHasIndicScript(q))
        assertFalse(queryHasDevanagari(q))
        assertTrue(expandRetrievalQuery(q, listOf("NDA Agreement.pdf")).contains("penalty"))
    }

    @Test
    fun `hinglish romanized term bridges to english and devanagari`() {
        val expanded = expandRetrievalQuery("is agreement me jurmana kitna hai", listOf("x.pdf"))
        assertTrue(expanded.contains("penalty"))
        assertTrue(expanded.contains("जुर्माना"))
    }

    @Test
    fun `plain english query is not indic`() {
        assertFalse(queryHasIndicScript("what is the penalty"))
    }
}
