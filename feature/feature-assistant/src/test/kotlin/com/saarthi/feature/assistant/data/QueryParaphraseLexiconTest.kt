package com.saarthi.feature.assistant.data

import com.saarthi.core.rag.Bm25Retriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 6 P25 — paraphrase lexicon + weak-match retrieval retry. */
class QueryParaphraseLexiconTest {

    @Test
    fun `data boss query expands fiduciary obligations`() {
        val expansion = paraphraseQueryExpansion("What are data boss duties in this act")
        assertTrue(expansion.contains("Fiduciary"))
        assertTrue(expansion.contains("obligations"))
    }

    @Test
    fun `retry runs when score weak and expansion exists`() {
        assertTrue(
            shouldRunParaphraseRetrievalRetry(
                topOrganicScore = 1.2,
                hasAnchoredHits = false,
                paraphraseExpansion = "Data Fiduciary",
            ),
        )
        assertFalse(
            shouldRunParaphraseRetrievalRetry(
                topOrganicScore = 5.0,
                hasAnchoredHits = false,
                paraphraseExpansion = "Data Fiduciary",
            ),
        )
        assertFalse(
            shouldRunParaphraseRetrievalRetry(
                topOrganicScore = 1.2,
                hasAnchoredHits = true,
                paraphraseExpansion = "Data Fiduciary",
            ),
        )
    }

    @Test
    fun `merge ranked keeps best score per chunk index`() {
        val primary = listOf(
            Bm25Retriever.Scored(0, 3.0),
            Bm25Retriever.Scored(1, 2.0),
        )
        val secondary = listOf(
            Bm25Retriever.Scored(0, 4.5),
            Bm25Retriever.Scored(2, 5.0),
        )
        val merged = mergeRankedBm25Results(primary, secondary, maxKeep = 3)
        assertEquals(3, merged.size)
        assertEquals(2, merged[0].index)
        assertEquals(4.5, merged.first { it.index == 0 }.score, 0.01)
    }
}
