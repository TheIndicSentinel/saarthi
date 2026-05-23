package com.saarthi.core.rag

import kotlin.math.ln
import kotlin.math.max

/**
 * BM25 ranker — the same probabilistic IR scoring function Lucene,
 * Elasticsearch, and OpenSearch ship by default. Industry standard since
 * 1994; still the right baseline for any production RAG that doesn't
 * carry a bundled embedding model.
 *
 * Beats naive keyword overlap because:
 *  • IDF — rare query terms outweigh stopword-ish ones, so "rangoli"
 *    dominates "the" automatically (no curated stopword list needed).
 *  • TF saturation (k1) — repeating a term 50× in a chunk does not
 *    overwhelm a different query term that lands once.
 *  • Length normalisation (b) — long chunks are not favoured just for
 *    being long; matters when one document dwarfs another in the corpus.
 *
 * Stateless: the ranker holds no index. Pass the corpus + query, get
 * back the top-K (index, score) pairs. For Saarthi's corpus sizes (a
 * handful of attached files per chat → tens of chunks) the per-call
 * tokenisation cost is negligible and is easier to reason about than
 * maintaining an on-disk inverted index.
 *
 * Devanagari / Tamil / Bengali / Latin all work because tokenisation
 * splits on Unicode non-letter-or-digit (`\p{L}\p{N}`), not ASCII.
 */
object Bm25Retriever {

    private const val K1 = 1.2
    private const val B = 0.75

    data class Scored(val index: Int, val score: Double)

    /**
     * Rank [corpus] against [query]. Returns up to [topK] scored entries,
     * sorted by descending score. Chunks with zero score (no query terms
     * matched) are dropped — callers should fall back to a deterministic
     * pick (first chunk per doc, etc.) when this is empty.
     */
    fun rank(corpus: List<String>, query: String, topK: Int): List<Scored> {
        if (corpus.isEmpty() || query.isBlank() || topK <= 0) return emptyList()

        val tokenisedDocs = corpus.map(::tokenise)
        val docLens = tokenisedDocs.map { it.size }
        val avgDl = if (docLens.isEmpty()) 0.0 else docLens.average()
        val n = corpus.size

        // Query-side stemming: expand each English token with its plural
        // / singular partner so "penalties" matches a corpus chunk that
        // only says "penalty", "fines" matches "fine", "issues" matches
        // "issue", and so on. Corpus tokenisation is left vanilla — we
        // only widen the query, never narrow the index. Devanagari and
        // other non-ASCII scripts are skipped (different morphology).
        val queryTerms = tokenise(query)
            .flatMap { listOf(it, lightStem(it)) }
            .distinct()
        if (queryTerms.isEmpty()) return emptyList()

        // Pre-compute document frequency and IDF for every query term.
        val idf = HashMap<String, Double>(queryTerms.size)
        for (qt in queryTerms) {
            val df = tokenisedDocs.count { it.contains(qt) }
            // BM25+ IDF — the trailing `+ 1` inside ln prevents the
            // negative IDF that classical BM25 can produce when a term
            // appears in more than half the corpus.
            idf[qt] = ln(((n - df + 0.5) / (df + 0.5)) + 1.0)
        }

        // Score every chunk.
        val scored = ArrayList<Scored>(corpus.size)
        for (i in corpus.indices) {
            val tokens = tokenisedDocs[i]
            if (tokens.isEmpty()) continue
            val tf = tokens.groupingBy { it }.eachCount()
            val dl = docLens[i]
            var s = 0.0
            for (qt in queryTerms) {
                val f = tf[qt] ?: 0
                if (f == 0) continue
                val numerator = f * (K1 + 1)
                val denominator = f + K1 * (1.0 - B + B * (dl / max(avgDl, 1.0)))
                s += (idf[qt] ?: 0.0) * (numerator / denominator)
            }
            if (s > 0.0) scored += Scored(i, s)
        }
        scored.sortByDescending { it.score }
        return if (scored.size <= topK) scored else scored.subList(0, topK)
    }

    /**
     * Unicode-aware tokeniser: lowercase, split on non-letter-or-digit,
     * keep tokens ≥ 2 chars. Devanagari, Tamil, Bengali, Latin, digits —
     * all preserved because we use `\p{L}\p{N}`, not `[A-Za-z0-9]`.
     */
    private fun tokenise(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }

    /**
     * Minimal English plural stemmer. Query-side only — never apply to
     * the corpus, because the asymmetry is what gives us recall without
     * losing precision (an exact-match query token still scores higher
     * than its stemmed sibling thanks to TF saturation).
     *
     * Skips non-ASCII tokens (Devanagari, Tamil, Bengali) entirely
     * because their morphology is different and suffix-stripping would
     * mangle them. Returns the token unchanged when no rule applies.
     */
    private fun lightStem(token: String): String {
        if (token.length < 4) return token
        if (token.any { it.code > 127 }) return token   // ASCII only
        return when {
            token.endsWith("ies")                                                  -> token.dropLast(3) + "y"   // penalties → penalty
            token.endsWith("ches") || token.endsWith("shes") ||
            token.endsWith("ses")  || token.endsWith("xes")  ||
            token.endsWith("zes")                                                  -> token.dropLast(2)         // boxes → box, lashes → lash
            token.endsWith("es")   && token.length > 3                             -> token.dropLast(1)         // fines → fine, issues → issue
            token.endsWith("s")    && token.length > 3 && !token.endsWith("ss")    -> token.dropLast(1)         // cats → cat (but not "loss")
            else                                                                   -> token
        }
    }
}
