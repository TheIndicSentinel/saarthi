package com.saarthi.feature.assistant.data

import com.saarthi.core.rag.Bm25Retriever
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
    fun `devanagari filename stem matches query with combining vowel signs`() {
        // Regression: without \p{M}, QUERY_SPLIT fragments "मूल्य" into single
        // letters that filenameTokens drops (length < 4), so named-doc routing fails.
        val indicDocs = listOf("content://price" to "मूल्य_Report.pdf")
        assertEquals(setOf("content://price"), matchNamedDocs("मूल्य क्या है", indicDocs))
        assertEquals(
            Bm25Retriever.tokeniseDocument("मूल्य"),
            "मूल्य".lowercase().split(Regex("[^\\p{L}\\p{N}\\p{M}]+")).filter { it.length >= 2 },
        )
    }

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

    @Test
    fun `blank attach send becomes overview query`() {
        assertEquals(ATTACH_OVERVIEW_QUERY, attachTurnQuery("", hasAttachments = true))
        assertEquals("", attachTurnQuery("  ", hasAttachments = false))
        assertEquals("what is the penalty", attachTurnQuery("what is the penalty", hasAttachments = true))
    }

    @Test
    fun `overview quick-action restricts to the newest attachment`() {
        val uris = listOf("content://a", "content://b")
        assertEquals(setOf("content://b"), restrictUrisForAttachTurn("", uris))
        assertEquals(setOf("content://b"), restrictUrisForAttachTurn(ATTACH_OVERVIEW_QUERY, uris))
        assertEquals(uris.toSet(), restrictUrisForAttachTurn("what is the penalty", uris))
        assertTrue(restrictUrisForAttachTurn("give an overview", emptyList()).isEmpty())
    }

    @Test
    fun `identical consecutive attach queries are duplicates`() {
        val uris = setOf("content://a")
        assertTrue(isDuplicateTurn("give an overview", uris, "Give an overview", uris))
        assertFalse(isDuplicateTurn("give an overview", uris, "give an overview", setOf("content://b")))
        assertFalse(isDuplicateTurn(null, emptySet(), "give an overview", uris))
        assertFalse(isDuplicateTurn("give an overview", uris, "what is the penalty", uris))
    }

    @Test
    fun `brief overview requests map to OVERVIEW_SHORT`() {
        assertEquals(
            RagAnswerShape.OVERVIEW_SHORT,
            detectRagAnswerShape("Give Document content overview in short", metaOverview = true),
        )
        assertEquals(
            RagAnswerShape.OVERVIEW_SHORT,
            detectRagAnswerShape("Document content ka overview do संक्षिप्त में", metaOverview = true),
        )
        assertEquals(
            RagAnswerShape.OVERVIEW,
            detectRagAnswerShape("Document content ka overview do", metaOverview = true),
        )
    }

    @Test
    fun `narrow factual questions map to NARROW_QA`() {
        assertEquals(RagAnswerShape.NARROW_QA, detectRagAnswerShape("What is data protection board", metaOverview = false))
    }

    @Test
    fun `penalty questions map to LIST answer shape B1`() {
        assertEquals(RagAnswerShape.LIST, detectRagAnswerShape("Penalties kya hai?", metaOverview = false))
        assertEquals(RagAnswerShape.LIST, detectRagAnswerShape("What does document say about penalties", metaOverview = false))
    }

    @Test
    fun `explicit list requests map to LIST`() {
        assertEquals(RagAnswerShape.LIST, detectRagAnswerShape("list all penalties in the act", metaOverview = false))
        assertEquals(RagAnswerShape.LIST, detectRagAnswerShape("list the rights", metaOverview = false))
    }

    @Test
    fun `isBriefRequest detects english and devanagari`() {
        assertTrue(isBriefRequest("overview in short"))
        assertTrue(isBriefRequest("संक्षिप्त अवलोकन"))
        assertFalse(isBriefRequest("what are penalties"))
    }

    @Test
    fun `overview english without meta route still maps to OVERVIEW`() {
        assertEquals(
            RagAnswerShape.OVERVIEW,
            detectRagAnswerShape("give me an overview of this document", metaOverview = false),
        )
    }

    @Test
    fun `topK scales with answer shape`() {
        assertEquals(4, topKForAnswerShape(RagAnswerShape.NARROW_QA, equalSlots = false))
        assertEquals(5, topKForAnswerShape(RagAnswerShape.OVERVIEW_SHORT, equalSlots = false))
        assertEquals(6, topKForAnswerShape(RagAnswerShape.OVERVIEW, equalSlots = false))
        assertEquals(6, topKForAnswerShape(RagAnswerShape.LIST, equalSlots = false))
        assertEquals(
            RagDocumentRepository.DEFAULT_TOP_K,
            topKForAnswerShape(RagAnswerShape.NARROW_QA, equalSlots = true),
        )
    }

    @Test
    fun `brief overview quick-action restricts to the newest attachment`() {
        val uris = listOf("content://a", "content://b")
        assertEquals(setOf("content://b"), restrictUrisForAttachTurn(ATTACH_BRIEF_OVERVIEW_QUERY, uris))
    }

    @Test
    fun `reply length short nudges overview shape to OVERVIEW_SHORT`() {
        assertEquals(
            RagAnswerShape.OVERVIEW_SHORT,
            applyReplyLengthToAnswerShape(RagAnswerShape.OVERVIEW, com.saarthi.core.i18n.ReplyLength.SHORT),
        )
        assertEquals(
            RagAnswerShape.NARROW_QA,
            applyReplyLengthToAnswerShape(RagAnswerShape.LIST, com.saarthi.core.i18n.ReplyLength.SHORT),
        )
    }

    @Test
    fun `reply length short keeps LIST for structure and chapter highlights`() {
        assertEquals(
            RagAnswerShape.LIST,
            applyReplyLengthToAnswerShape(
                RagAnswerShape.LIST,
                com.saarthi.core.i18n.ReplyLength.SHORT,
                query = "How many chapters are there",
            ),
        )
        assertEquals(
            RagAnswerShape.LIST,
            applyReplyLengthToAnswerShape(
                RagAnswerShape.LIST,
                com.saarthi.core.i18n.ReplyLength.SHORT,
                query = "Highlights from chapter VI",
            ),
        )
    }

    @Test
    fun `chapter highlights with short reply keeps LIST topK`() {
        val query = "Highlights from chapter VI"
        val shape = applyReplyLengthToAnswerShape(
            detectRagAnswerShape(query, metaOverview = false),
            com.saarthi.core.i18n.ReplyLength.SHORT,
            query = query,
        )
        assertEquals(RagAnswerShape.LIST, shape)
        assertEquals(6, topKForAnswerShape(shape, equalSlots = false))
        assertEquals(SPAN_PRESERVING_TOP_K, effectiveRetrievalTopK(query, shape, equalSlots = false))
    }

    @Test
    fun `filterRankedByScoreGap drops weak tail hits`() {
        val ranked = listOf(
            Bm25Retriever.Scored(0, 10.0),
            Bm25Retriever.Scored(1, 8.0),
            Bm25Retriever.Scored(2, 2.0),
            Bm25Retriever.Scored(3, 1.0),
        )
        val out = filterRankedByScoreGap(ranked, maxKeep = 4)
        assertEquals(2, out.size)
        assertEquals(0, out[0].index)
        assertEquals(1, out[1].index)
    }
}
