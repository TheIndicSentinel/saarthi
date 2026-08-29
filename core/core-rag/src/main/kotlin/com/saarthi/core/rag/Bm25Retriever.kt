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
 * Stateless ranker: holds no index. Pass raw corpus via [rank], or pass
 * pre-tokenised docs via [rankTokenised] when a caller caches corpus-side
 * tokens (see [com.saarthi.feature.assistant.data.RagDocumentRepository]).
 * Query tokens are always computed fresh on each call.
 *
 * Devanagari / Tamil / Bengali / Latin all work because tokenisation
 * keeps Unicode letters, digits, AND combining marks (`\p{L}\p{N}\p{M}`)
 * as one token — see [tokenise]'s kdoc for why `\p{M}` specifically is
 * load-bearing, not decorative, for every Brahmic script this app ships.
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
    fun rank(corpus: List<String>, query: String, topK: Int): List<Scored> =
        rankTokenised(corpus.map(::tokeniseDocument), query, topK)

    /**
     * Rank a corpus whose documents are already tokenised (R1 cache path).
     * Indices in [Scored.index] align with [tokenisedDocs] positions.
     */
    fun rankTokenised(tokenisedDocs: List<List<String>>, query: String, topK: Int): List<Scored> {
        if (tokenisedDocs.isEmpty() || query.isBlank() || topK <= 0) return emptyList()

        val docLens = tokenisedDocs.map { it.size }
        val avgDl = if (docLens.isEmpty()) 0.0 else docLens.average()
        val n = tokenisedDocs.size

        // Query-side stemming: expand each token with its normalized form
        // so "penalties" matches a corpus chunk that only says "penalty",
        // and (see indicStem) "किसानों" matches a corpus chunk that only
        // says "किसान". Corpus tokenisation is left vanilla — we only
        // widen the query, never narrow the index.
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
        val scored = ArrayList<Scored>(tokenisedDocs.size)
        for (i in tokenisedDocs.indices) {
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
     * Corpus-side tokenisation — exposed for the R1 per-chunk cache in
     * [com.saarthi.feature.assistant.data.RagDocumentRepository]. Query
     * tokens are still computed fresh on every search (stemming is
     * query-only by design).
     */
    fun tokeniseDocument(text: String): List<String> = tokenise(text)

    /**
     * Unicode-aware tokeniser: lowercase, split on non-letter/digit/mark,
     * keep tokens ≥ 2 chars.
     *
     * `\p{M}` (Unicode general category Mark) is included alongside
     * `\p{L}\p{N}` — this is a correctness fix, not a stylistic choice.
     * Devanagari, Bengali, Gujarati, Gurmukhi, Odia, Tamil, Telugu, and
     * Kannada all build most real words out of a base consonant/vowel
     * letter (category L) PLUS one or more dependent vowel signs and
     * virama (categories Mc/Mn — Unicode's Mark categories, NOT Letter).
     * A word like "किसान" (Hindi, "farmer") is literally
     * क(L) ि(Mc) स(L) ा(Mc) न(L) — every other character is a mark. The
     * previous pattern `[^\p{L}\p{N}]+` treated each of those marks as a
     * word-boundary delimiter, splitting the word into single-character
     * fragments ("क", "स", "न") that the `length >= 2` filter below then
     * silently dropped entirely. The class-level kdoc's claim that
     * "Devanagari / Tamil / Bengali / Latin all work" was true only for
     * the rare word with no dependent vowel signs at all — i.e. false for
     * most real text in these scripts. Confirmed empirically: an exact
     * query/corpus match on the plain word "मूल्य" ("price") scored zero
     * overlap before this fix, with no stemming or suffix-matching
     * involved at all. `\p{M}` makes the tokeniser keep a letter and its
     * attached marks together as one token, the correct behavior for any
     * script that uses combining vowel signs.
     */
    private fun tokenise(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}\\p{M}]+"))
            .filter { it.length >= 2 }

    /**
     * Minimal English plural stemmer. Query-side only — never apply to
     * the corpus, because the asymmetry is what gives us recall without
     * losing precision (an exact-match query token still scores higher
     * than its stemmed sibling thanks to TF saturation).
     *
     * Non-ASCII tokens (Devanagari, Tamil, Bengali, ...) dispatch to
     * [indicStem] below instead of passing through unchanged — see its
     * kdoc for scope and confidence level.
     */
    private fun lightStem(token: String): String {
        if (token.length < 4) return token
        if (token.any { it.code > 127 }) return indicStem(token)
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

    /**
     * Query-side suffix-stripping for the app's 9 supported Indic scripts —
     * the non-ASCII counterpart to [lightStem] above, same additive pattern
     * (never applied to corpus tokenization, so a wrong or over-eager strip
     * only ever adds a probably-unmatched extra query candidate, never
     * corrupts the index).
     *
     * Scope, stated plainly: this is a small, curated table of the noun
     * case/plural suffixes common enough to appear in any basic grammar
     * reference for each language — locative ("in/at"), dative ("to/for"),
     * genitive ("of"), plural/oblique-plural. It is NOT a morphological
     * analyzer and does NOT attempt verb conjugation (materially more
     * ambiguous to strip correctly without a real dictionary/corpus to
     * check against) or comprehensive case coverage. Deliberately
     * conservative — a short, high-confidence list per script, gated on a
     * minimum resulting-stem length — over an attempt at exhaustive
     * coverage. Suffix tables were reviewed in R5 against grammar-textbook
     * paradigms plus document-style BM25 fixtures in [IndicStemValidationTest]
     * (one positive + one negative case per script). Verb conjugations and
     * sandhi-heavy Dravidian locatives are still out of scope.
     *
     * Two different grammatical shapes are involved depending on language
     * family, noted per table below:
     *  • Devanagari/Bengali/Gujarati/Gurmukhi/Odia (Indo-Aryan): most
     *    postpositions are already separate space-delimited tokens (so
     *    already harmless — mostly low-IDF, matched or not on their own).
     *    The suffixes below target OBLIQUE/PLURAL noun-form variation —
     *    a noun changes its own ending before a postposition or in plural
     *    (किसान "farmer" → किसानों "farmers/to farmers") — which is the
     *    actual source of query/corpus token mismatch for these languages.
     *  • Tamil/Telugu/Kannada (Dravidian): case markers are true bound
     *    suffixes agglutinated directly onto the noun with no space
     *    (வீடு "house" → வீட்டில் "in the house") — stripping these is a
     *    more literal reading of "case-marker stripping."
     *
     * Script is detected via Unicode block on the token's first character
     * — a reliable, language-agnostic proxy since the tokenizer only ever
     * sees raw text, never a declared UI language. Devanagari is shared by
     * Hindi and Marathi; the suffix set favors Hindi (the larger
     * single-language user base) with partial Marathi overlap.
     */
    /**
     * Query-side stem for a single token — exposed for R5 validation tests.
     * Applies [lightStem] rules (English plural + [indicStem] for Indic scripts).
     */
    internal fun stemQueryToken(token: String): String = lightStem(token)

    /**
     * BM25-aligned query widening for FTS5 MATCH — same additive stem expansion
     * as [rankTokenised], capped for bounded MATCH clauses.
     */
    fun expandQueryTermsForSearch(query: String, maxTerms: Int = 12): List<String> =
        tokenise(query)
            .flatMap { t -> listOf(t, lightStem(t)) }
            .distinct()
            .take(maxTerms)

    private fun indicStem(token: String): String {
        val first = token.first()
        return when (first.code) {
            in 0x0900..0x097F -> stemDevanagari(token)
            in 0x0980..0x09FF -> stemWithSuffixes(token, BENGALI_SUFFIXES)
            in 0x0A00..0x0A7F -> stemWithSuffixes(token, GURMUKHI_SUFFIXES)
            in 0x0A80..0x0AFF -> stemWithSuffixes(token, GUJARATI_SUFFIXES)
            in 0x0B00..0x0B7F -> stemWithSuffixes(token, ODIA_SUFFIXES)
            in 0x0B80..0x0BFF -> stemWithSuffixes(token, TAMIL_SUFFIXES)
            in 0x0C00..0x0C7F -> stemWithSuffixes(token, TELUGU_SUFFIXES)
            in 0x0C80..0x0CFF -> stemWithSuffixes(token, KANNADA_SUFFIXES)
            else -> token
        }
    }

    private fun stemWithSuffixes(token: String, suffixes: List<String>): String {
        for (suffix in suffixes) {
            if (token.length - suffix.length < MIN_INDIC_STEM_LENGTH) continue
            if (token.endsWith(suffix)) return token.dropLast(suffix.length)
        }
        return token
    }

    /**
     * Devanagari (Hindi/Marathi). Feminine plural oblique …ाओं is handled
     * before the generic table: strip trailing ओं only so योजनाओं → योजना
     * (not योजन).
     */
    private fun stemDevanagari(token: String): String {
        if (token.endsWith("ाओं") && token.length - 2 >= MIN_INDIC_STEM_LENGTH) {
            return token.dropLast(2)
        }
        return stemWithSuffixes(token, DEVANAGARI_SUFFIXES)
    }

    private const val MIN_INDIC_STEM_LENGTH = 2

    // Masculine/neuter oblique-plural (किसानों → किसान). Feminine plural
    // (किताबें → किताब). …ाओं is handled in [stemDevanagari] above.
    private val DEVANAGARI_SUFFIXES = listOf("ों", "ें").sortedByDescending { it.length }

    // গুলো/গুলি: plural classifier. দের: animate dative/genitive plural.
    // এর: genitive. রা: simple plural (কৃষকরা → কৃষক).
    private val BENGALI_SUFFIXES = listOf("গুলো", "গুলি", "দের", "এর", "রা").sortedByDescending { it.length }

    // ોમાં: locative (ઘરોમાં → ઘર "in the houses"). થી: ablative
    // ("from"). નું: genitive, neuter agreement.
    private val GUJARATI_SUFFIXES = listOf("ોમાં", "થી", "નું").sortedByDescending { it.length }

    // ਾਂ: plural/oblique (ਕਿਸਾਨਾਂ → ਕਿਸਾਨ "farmers"). ਨੂੰ: dative/
    // accusative postposition. ਦਾ: genitive, masculine agreement.
    private val GURMUKHI_SUFFIXES = listOf("ਾਂ", "ਨੂੰ", "ਦਾ").sortedByDescending { it.length }

    // ମାନଙ୍କ: plural+genitive combo (ଲୋକମାନଙ୍କ → ଲୋକ "people's").
    // ଠାରୁ: ablative ("from"). ର: genitive ("of").
    private val ODIA_SUFFIXES = listOf("ମାନଙ୍କ", "ଠାରୁ", "ର").sortedByDescending { it.length }

    // Dravidian: true agglutinated case suffixes. இல்: locative ("in").
    // க்கு: dative ("to/for"). இன்: genitive ("of").
    private val TAMIL_SUFFIXES = listOf("இல்", "க்கு", "இன்").sortedByDescending { it.length }

    // లో: locative ("in"). కి: dative ("to"). ను: accusative. యొక్క: genitive ("of").
    private val TELUGU_SUFFIXES = listOf("యొక్క", "లో", "కి", "ను").sortedByDescending { it.length }

    // ದಲ್ಲಿ: locative ("in"). ಗೆ: dative ("to"). ಇಂದ: instrumental/
    // ablative ("by/from").
    private val KANNADA_SUFFIXES = listOf("ದಲ್ಲಿ", "ಗೆ", "ಇಂದ").sortedByDescending { it.length }
}
