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

    // ── Indic query-side stemming (C2 fix) ──────────────────────────────────────
    // Each case: corpus has the base noun form only; the query uses an
    // oblique/plural/case-marked form of the SAME word. Before the fix these
    // scored zero overlap (indicStem() returned non-ASCII tokens unchanged).
    // See Bm25Retriever.indicStem's kdoc for scope — full R5 validation corpus
    // lives in IndicStemValidationTest.

    @Test
    fun `tokeniser keeps a word and its combining vowel signs together, not split into fragments`() {
        // Regression guard for a pre-existing tokenizer bug found while
        // building the Indic stemming tests below: an EXACT query/corpus
        // match on the plain word "मूल्य" ("price") — no suffix stripping
        // involved at all — scored zero overlap before \p{M} was added to
        // the tokeniser's character class, because every dependent vowel
        // sign in the word was treated as a word-boundary delimiter,
        // fragmenting it into single characters the length>=2 filter then
        // dropped. See tokenise()'s kdoc for the full explanation.
        val ranked = Bm25Retriever.rank(listOf("मूल्य के बारे में जानकारी"), "मूल्य", topK = 1)
        assertTrue("Exact Devanagari token match must not be empty", ranked.isNotEmpty())
    }

    // Single-word queries, deliberately — isolates the stemming behavior
    // being tested from any coincidental second shared word, matching the
    // style of `plural query matches a singular corpus term` above. Corpus
    // chunk 1 contains ONLY the base word as anything query-overlapping,
    // so a pass here can only be explained by indicStem's suffix strip.

    @Test
    fun `Hindi oblique-plural query matches singular corpus term`() {
        val corpus = listOf(
            "आज मौसम अच्छा है।",                       // 0 — weather, no overlap
            "किसान अपने खेत में काम कर रहा है।",          // 1 — farmer, base form only
        )
        val ranked = Bm25Retriever.rank(corpus, "किसानों", topK = 2)
        assertEquals("किसानों (oblique plural) must match किसान", 1, ranked.first().index)
    }

    @Test
    fun `Hindi feminine plural oblique query matches base scheme noun`() {
        val corpus = listOf(
            "मौसम की जानकारी।",
            "PM-Kisan योजना के लिए आवेदन करें।",
        )
        val ranked = Bm25Retriever.rank(corpus, "योजनाओं", topK = 2)
        assertEquals("योजनाओं must match योजना", 1, ranked.first().index)
    }

    @Test
    fun `Bengali dative-plural query matches base corpus term`() {
        val corpus = listOf(
            "আজ আবহাওয়া ভালো।",                        // 0 — weather, no overlap
            "কৃষক তার জমিতে কাজ করছে।",                  // 1 — farmer, base form only
        )
        val ranked = Bm25Retriever.rank(corpus, "কৃষকদের", topK = 2)
        assertEquals("কৃষকদের must match কৃষক", 1, ranked.first().index)
    }

    @Test
    fun `Gujarati locative-plural query matches base corpus term`() {
        val corpus = listOf(
            "આજે હવામાન સારું છે.",                      // 0 — weather, no overlap
            "ખેતર માં કપાસ વાવ્યો છે.",                   // 1 — field, base form only
        )
        val ranked = Bm25Retriever.rank(corpus, "ખેતરોમાં", topK = 2)
        assertEquals("ખેતરોમાં must match ખેતર", 1, ranked.first().index)
    }

    @Test
    fun `Punjabi oblique-plural query matches base corpus term`() {
        val corpus = listOf(
            "ਅੱਜ ਮੌਸਮ ਚੰਗਾ ਹੈ।",                          // 0 — weather, no overlap
            "ਕਿਸਾਨ ਆਪਣੇ ਖੇਤ ਵਿੱਚ ਕੰਮ ਕਰ ਰਿਹਾ ਹੈ।",         // 1 — farmer, base form only
        )
        val ranked = Bm25Retriever.rank(corpus, "ਕਿਸਾਨਾਂ", topK = 2)
        assertEquals("ਕਿਸਾਨਾਂ must match ਕਿਸਾਨ", 1, ranked.first().index)
    }

    @Test
    fun `Odia plural-genitive query matches base corpus term`() {
        val corpus = listOf(
            "ଆଜି ପାଣିପାଗ ଭଲ ଅଛି।",                       // 0 — weather, no overlap
            "କୃଷକ ନିଜ ଜମିରେ କାମ କରୁଛନ୍ତି।",               // 1 — farmer, base form only
        )
        val ranked = Bm25Retriever.rank(corpus, "କୃଷକମାନଙ୍କ", topK = 2)
        assertEquals("କୃଷକମାନଙ୍କ must match କୃଷକ", 1, ranked.first().index)
    }

    @Test
    fun `Tamil dative-case query matches base corpus term`() {
        val corpus = listOf(
            "இன்று வானிலை நன்றாக உள்ளது.",                // 0 — weather, no overlap
            "விவசாயி தன் நிலத்தில் வேலை செய்கிறார்.",       // 1 — farmer, base form only
        )
        val ranked = Bm25Retriever.rank(corpus, "விவசாயிக்கு", topK = 2)
        assertEquals("விவசாயிக்கு must match விவசாயி", 1, ranked.first().index)
    }

    @Test
    fun `Telugu dative-case query matches base corpus term`() {
        val corpus = listOf(
            "ఈ రోజు వాతావరణం బాగుంది.",                   // 0 — weather, no overlap
            "రైతు తన పొలంలో పని చేస్తున్నాడు.",             // 1 — farmer, base form only
        )
        val ranked = Bm25Retriever.rank(corpus, "రైతుకి", topK = 2)
        assertEquals("రైతుకి must match రైతు", 1, ranked.first().index)
    }

    @Test
    fun `Kannada dative-case query matches base corpus term`() {
        val corpus = listOf(
            "ಇಂದು ಹವಾಮಾನ ಚೆನ್ನಾಗಿದೆ.",                    // 0 — weather, no overlap
            "ರೈತ ತನ್ನ ಹೊಲದಲ್ಲಿ ಕೆಲಸ ಮಾಡುತ್ತಿದ್ದಾನೆ.",       // 1 — farmer, base form only
        )
        val ranked = Bm25Retriever.rank(corpus, "ರೈತಗೆ", topK = 2)
        assertEquals("ರೈತಗೆ must match ರೈತ", 1, ranked.first().index)
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
    fun `rankTokenised matches rank on the same corpus`() {
        val corpus = listOf(
            "Consent must be free, informed and specific before processing data.",
            "A data breach can attract a penalty of up to 250 crore rupees.",
        )
        val query = "penalty for breach"
        val direct = Bm25Retriever.rank(corpus, query, topK = 2)
        val tokenised = corpus.map(Bm25Retriever::tokeniseDocument)
        val cached = Bm25Retriever.rankTokenised(tokenised, query, topK = 2)
        assertEquals(direct.map { it.index }, cached.map { it.index })
        direct.zip(cached).forEach { (a, b) ->
            assertEquals(a.score, b.score, 0.0001)
        }
    }

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

    @Test
    fun `expandQueryTermsForSearch mirrors BM25 query widening`() {
        val terms = Bm25Retriever.expandQueryTermsForSearch("penalties and किसानों")
        assertTrue(terms.contains("penalties"))
        assertTrue(terms.contains("penalty"))
        assertTrue(terms.contains("किसानों") || terms.contains("किसान"))
    }
}
