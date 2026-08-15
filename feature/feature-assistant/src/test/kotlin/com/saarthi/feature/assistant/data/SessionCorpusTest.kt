package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Post-rank session-corpus helpers: this-turn boost and per-document
 * minimum slots. Pure functions — no Room.
 */
class SessionCorpusTest {

    private fun chunk(
        docUri: String,
        score: Double,
        name: String = docUri,
        index: Int = 0,
    ) = RetrievedChunk(
        text = "$name-$index",
        docName = name,
        score = score,
        chunkIndex = index,
        docUri = docUri,
    )

    @Test
    fun `two docs both appear in topK even when one dominates BM25`() {
        val hits = (0 until 8).map { i -> chunk("docA", score = 10.0 - i, index = i) } +
            listOf(chunk("docB", score = 1.0, index = 0))
        val allocated = allocatePerDocSlots(hits, topK = 8)
        assertEquals(8, allocated.size)
        assertTrue(allocated.any { it.docUri == "docA" })
        assertTrue(allocated.any { it.docUri == "docB" })
    }

    @Test
    fun `this-turn boost raises that doc's score`() {
        val hits = listOf(
            chunk("older.pdf", score = 10.0),
            chunk("this-turn.pdf", score = 9.0),
        )
        val boosted = applySessionBoost(
            hits,
            boostDocUris = setOf("this-turn.pdf"),
            recencyUri = "this-turn.pdf",
        )
        val thisTurn = boosted.first { it.docUri == "this-turn.pdf" }
        val older = boosted.first { it.docUri == "older.pdf" }
        assertEquals(9.0 * THIS_TURN_BOOST, thisTurn.score, 1e-9)
        assertEquals(10.0, older.score, 1e-9)
        assertTrue(thisTurn.score > older.score)
    }

    @Test
    fun `single-doc allocation is unchanged`() {
        val hits = (0 until 5).map { i -> chunk("only.pdf", score = 5.0 - i, index = i) }
        val allocated = allocatePerDocSlots(hits, topK = 3)
        assertEquals(listOf(5.0, 4.0, 3.0), allocated.map { it.score })
        assertTrue(allocated.all { it.docUri == "only.pdf" })
    }

    @Test
    fun `empty boost set applies recency bump to newest doc`() {
        val hits = listOf(
            chunk("older.pdf", score = 10.0),
            chunk("newest.pdf", score = 9.0),
        )
        val boosted = applySessionBoost(hits, boostDocUris = emptySet(), recencyUri = "newest.pdf")
        assertEquals(10.0, boosted.first { it.docUri == "older.pdf" }.score, 1e-9)
        assertEquals(9.0 * RECENCY_BOOST, boosted.first { it.docUri == "newest.pdf" }.score, 1e-9)
    }

    @Test
    fun `named-file boost beats recency and is weaker than this-turn`() {
        val hits = listOf(
            chunk("nda.pdf", score = 8.0),
            chunk("newest.pdf", score = 10.0),
        )
        val boosted = applySessionBoost(
            hits,
            boostDocUris = emptySet(),
            recencyUri = "newest.pdf",
            namedDocUris = setOf("nda.pdf"),
        )
        assertEquals(8.0 * FILENAME_BOOST, boosted.first { it.docUri == "nda.pdf" }.score, 1e-9)
        assertEquals(10.0, boosted.first { it.docUri == "newest.pdf" }.score, 1e-9)
        assertTrue(THIS_TURN_BOOST > FILENAME_BOOST)
        assertTrue(FILENAME_BOOST > RECENCY_BOOST)
    }

    @Test
    fun `compare minPerDoc keeps roughly equal excerpts`() {
        val hits = (0 until 8).map { i -> chunk("docA", score = 10.0 - i, index = i) } +
            (0 until 8).map { i -> chunk("docB", score = 0.5 - i * 0.01, index = i) }
        val allocated = allocatePerDocSlots(hits, topK = 8, minPerDoc = 4)
        assertEquals(8, allocated.size)
        assertEquals(4, allocated.count { it.docUri == "docA" })
        assertEquals(4, allocated.count { it.docUri == "docB" })
    }
}
