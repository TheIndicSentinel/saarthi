package com.saarthi.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accuracy + performance + robustness tests for [Bm25Retriever] — the
 * production RAG ranker. This is the component that decides which document
 * chunks the model is allowed to answer from, so a regression here directly
 * degrades answer quality (wrong chunk → hallucinated / off-topic answer).
 *
 * Pure + stateless, so everything below runs as fast JVM unit tests.
 */
class Bm25RetrieverTest {

    private fun ids(scored: List<Bm25Retriever.Scored>) = scored.map { it.index }

    // ── Accuracy: the relevant chunk must win ──────────────────────────────────

    @Test
    fun `the chunk on-topic for the query ranks first`() {
        val corpus = listOf(
            "Consent must be free, informed and specific before processing data.",   // 0
            "A data breach can attract a penalty of up to 250 crore rupees.",         // 1
            "Children's data needs verifiable parental consent.",                     // 2
        )
        val ranked = Bm25Retriever.rank(corpus, "what penalty for a breach", topK = 3)
        assertTrue("Expected results", ranked.isNotEmpty())
        assertEquals("The penalty chunk must rank first", 1, ranked.first().index)
    }

    @Test
    fun `a rare term outweighs a common one (IDF)`() {
        // "the" is in every chunk; "rangoli" in only one. The query mixes both —
        // BM25's IDF must let the rare term decide the winner.
        val corpus = listOf(
            "the weather is the topic of the day",          // 0 — only common words
            "the rangoli design for the festival",          // 1 — has the rare term
            "the market and the prices in the town",        // 2
        )
        val ranked = Bm25Retriever.rank(corpus, "the rangoli", topK = 3)
        assertEquals("Rare-term chunk must win", 1, ranked.first().index)
    }

    @Test
    fun `multi-term match beats a single term spammed many times (TF saturation)`() {
        val corpus = listOf(
            "data ".repeat(50),                              // 0 — one term, 50×
            "this clause covers data and the penalty for misuse",   // 1 — both terms once
        )
        val ranked = Bm25Retriever.rank(corpus, "data penalty", topK = 2)
        assertEquals("Chunk matching BOTH query terms must rank first", 1, ranked.first().index)
    }

    @Test
    fun `a shorter chunk is not out-ranked by a long padded one (length normalisation)`() {
        val short = "consent is required"                                    // 0
        val long = "consent is required " + "filler word ".repeat(60)        // 1 — same match, padded
        val ranked = Bm25Retriever.rank(listOf(short, long), "consent", topK = 2)
        assertEquals("Shorter chunk with the same match must rank first", 0, ranked.first().index)
    }

    // ── Recall: light query-side stemming ──────────────────────────────────────

    @Test
    fun `plural query matches a singular corpus term`() {
        val corpus = listOf(
            "The mandi price changed today.",                               // 0
            "Each violation attracts a separate penalty under the rules.",  // 1
        )
        // "penalties" must still find the chunk that says "penalty".
        val ranked = Bm25Retriever.rank(corpus, "penalties", topK = 2)
        assertTrue("Plural query must match singular corpus term", ranked.isNotEmpty())
        assertEquals(1, ranked.first().index)
    }

    // ── Multilingual ───────────────────────────────────────────────────────────

    @Test
    fun `devanagari query ranks the matching devanagari chunk first`() {
        val corpus = listOf(
            "मौसम की जानकारी कल मिलेगी।",          // 0 — weather
            "किसान को गेहूं का MSP मिलता है।",       // 1 — farmer / MSP
        )
        val ranked = Bm25Retriever.rank(corpus, "किसान MSP", topK = 2)
        assertEquals("Devanagari/Latin mixed query must match", 1, ranked.first().index)
    }

    @Test
    fun `tokeniser ignores case and punctuation`() {
        val ranked = Bm25Retriever.rank(listOf("Penalty, breach!"), "PENALTY breach", topK = 1)
        assertEquals(1, ranked.size)
        assertTrue(ranked.first().score > 0.0)
    }

    // ── Edge cases / robustness ────────────────────────────────────────────────

    @Test
    fun `empty corpus returns empty`() =
        assertTrue(Bm25Retriever.rank(emptyList(), "anything", topK = 5).isEmpty())

    @Test
    fun `blank query returns empty`() =
        assertTrue(Bm25Retriever.rank(listOf("some text"), "   ", topK = 5).isEmpty())

    @Test
    fun `non-positive topK returns empty`() =
        assertTrue(Bm25Retriever.rank(listOf("some text"), "text", topK = 0).isEmpty())

    @Test
    fun `a query that matches nothing returns empty, not a wrong chunk`() {
        // Critical for grounding: if nothing matches, return empty so the caller
        // falls back deterministically instead of citing an irrelevant chunk.
        val ranked = Bm25Retriever.rank(listOf("crop calendar for wheat"), "quantum chromodynamics", topK = 3)
        assertTrue("No match must yield empty, not a spurious result", ranked.isEmpty())
    }

    @Test
    fun `topK caps the number of results and they are sorted descending`() {
        val corpus = (1..10).map { "penalty breach consent data clause number $it" }
        val ranked = Bm25Retriever.rank(corpus, "penalty breach consent", topK = 3)
        assertTrue("Must respect topK", ranked.size <= 3)
        val scores = ranked.map { it.score }
        assertEquals("Results must be sorted by descending score", scores.sortedDescending(), scores)
    }

    @Test
    fun `blank and empty chunks are handled without error`() {
        val corpus = listOf("", "   ", "a penalty clause", "")
        val ranked = Bm25Retriever.rank(corpus, "penalty", topK = 5)
        assertEquals("Only the real chunk should score", listOf(2), ids(ranked))
    }

    // ── Performance ────────────────────────────────────────────────────────────

    @Test
    fun `ranks a large corpus well within budget`() {
        // 5000 chunks of ~40 tokens — far larger than any real attached doc set.
        val corpus = (0 until 5000).map { i ->
            "clause number $i about consent data processing and penalty rules " +
                "for fiduciaries and principals in the act ".repeat(2)
        }
        val start = System.nanoTime()
        val ranked = Bm25Retriever.rank(corpus, "penalty for a data breach", topK = 8)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("Must return topK", ranked.size <= 8 && ranked.isNotEmpty())
        // Generous ceiling for CI noise; real corpora are 100x smaller.
        assertTrue("Ranking 5000 chunks took ${elapsedMs}ms (budget 2000ms)", elapsedMs < 2000)
    }
}
