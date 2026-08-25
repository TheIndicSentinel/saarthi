package com.saarthi.feature.assistant.data

/**
 * Point 5 — query routing and query-side expansion. Pure functions so
 * Hindi/filename/compare behaviour is unit-testable without Room.
 * Corpus tokenisation and BM25 math are unchanged.
 */

internal data class QueryRoute(
    val namedDocUris: Set<String>,
    val equalSlots: Boolean,
    val whichFile: Boolean,
    val thisDocument: Boolean,
    val expandedQuery: String,
)

private val QUERY_SPLIT = Regex("[^\\p{L}\\p{N}]+")

private val FILENAME_STOPWORDS = setOf(
    "the", "this", "that", "document", "documents", "file", "files",
    "pdf", "txt", "log", "docx", "doc", "attachment", "attached",
)

/**
 * Conservative English terms added to an Indic-script question so it can match
 * an English (or bilingual) document. Indian legal/finance/government PDFs are
 * overwhelmingly English or English-heavy even when the user types in a
 * regional script, so these hints help for Hindi, Tamil, Telugu, Bengali, …
 * alike — not just Devanagari. Harmless on a pure-regional doc: the extra
 * English tokens simply score 0 there.
 */
internal val HINDI_QUERY_ENGLISH_HINTS = listOf(
    "document", "agreement", "amount", "penalty", "section", "clause",
    "account", "statement", "confidential", "term",
)

/**
 * Hinglish / romanized-Indic bridge. Common finance & legal query words typed
 * in Latin script, mapped to their English AND Devanagari equivalents so a
 * romanized question ("jurmana kitna hai") still matches either an English or a
 * Hindi document. High-precision keys only — each is an unambiguous domain term,
 * so false expansions are unlikely. Native-script values help Devanagari docs;
 * English values help English docs.
 */
internal val ROMANIZED_INDIC_HINTS: Map<String, List<String>> = mapOf(
    "jurmana" to listOf("penalty", "जुर्माना"),
    "jurmaana" to listOf("penalty", "जुर्माना"),
    "dhara" to listOf("section", "धारा"),
    "samjhauta" to listOf("agreement", "समझौता"),
    "samjhota" to listOf("agreement", "समझौता"),
    "khata" to listOf("account", "खाता"),
    "rakam" to listOf("amount", "रकम"),
    "raqam" to listOf("amount", "रकम"),
    "byaj" to listOf("interest", "ब्याज"),
    "vetan" to listOf("salary", "वेतन"),
    "gopniya" to listOf("confidential", "गोपनीय"),
    "gopaniya" to listOf("confidential", "गोपनीय"),
)

private val COMPARE_TOKENS = setOf("compare", "both", "versus", "vs")
private val COMPARE_PHRASES = listOf("दोनों", "तुलना", "each of")
private val WHICH_FILE_PHRASES = listOf(
    "which file", "which document", "which pdf", "which one",
    "कौन सी फ़ाइल", "कौन सी फाइल", "कौन सा", "किस फ़ाइल", "किस फाइल",
)
private val THIS_DOC_PHRASES = listOf(
    "this document", "this pdf", "the pdf", "this file",
    "इस दस्तावेज़", "इस दस्तावेज", "इस फाइल", "इस फ़ाइल", "ये वाली",
)

internal fun filenameTokens(name: String): Set<String> {
    var stem = name.lowercase()
    for (ext in listOf(".pdf", ".docx", ".doc", ".txt", ".log")) {
        if (stem.endsWith(ext)) stem = stem.dropLast(ext.length)
    }
    return stem.split(QUERY_SPLIT)
        .filter { it.length >= 4 && it !in FILENAME_STOPWORDS }
        .toSet()
}

internal fun isCompareQuery(query: String): Boolean {
    val lower = query.lowercase()
    if (COMPARE_PHRASES.any { lower.contains(it) }) return true
    val tokens = lower.split(QUERY_SPLIT).filter { it.isNotEmpty() }
    return tokens.any { it in COMPARE_TOKENS }
}

internal fun isWhichFileQuery(query: String): Boolean {
    val lower = query.lowercase()
    return WHICH_FILE_PHRASES.any { lower.contains(it) }
}

internal fun isThisDocumentQuery(query: String): Boolean {
    val lower = query.lowercase()
    return THIS_DOC_PHRASES.any { lower.contains(it) }
}

internal fun matchNamedDocs(query: String, docs: List<Pair<String, String>>): Set<String> {
    val qTokens = query.lowercase().split(QUERY_SPLIT)
        .filter { it.length >= 4 && it !in FILENAME_STOPWORDS }
        .toSet()
    if (qTokens.isEmpty()) return emptySet()
    val matched = mutableSetOf<String>()
    for ((uri, name) in docs) {
        val fTokens = filenameTokens(name)
        val hit = fTokens.any { ft ->
            qTokens.any { qt -> qt == ft || (qt.length >= 4 && ft.length >= 4 && (qt.contains(ft) || ft.contains(qt))) }
        }
        if (hit) matched += uri
    }
    return matched
}

internal fun queryHasDevanagari(query: String): Boolean =
    query.any { it in '\u0900'..'\u097F' }

/**
 * True if the query contains any Indic script character. The single contiguous
 * range U+0900–U+0D7F covers every script the app offers: Devanagari
 * (Hindi/Marathi), Bengali, Gurmukhi (Punjabi), Gujarati, Oriya, Tamil, Telugu,
 * Kannada and Malayalam. Used to decide when to add the English cross-lingual
 * hints, so regional-script users get the same bridge Hindi users already had.
 */
internal fun queryHasIndicScript(query: String): Boolean =
    query.any { it in '\u0900'..'\u0D7F' }

internal fun expandRetrievalQuery(query: String, docNames: List<String>): String {
    val extra = LinkedHashSet<String>()
    docNames.forEach { extra.addAll(filenameTokens(it)) }
    // Any Indic-script query → English hints (bilingual/English Indian docs).
    if (queryHasIndicScript(query)) extra.addAll(HINDI_QUERY_ENGLISH_HINTS)
    // Hinglish / romanized-Indic bridge → English + Devanagari equivalents.
    query.lowercase().split(QUERY_SPLIT).filter { it.isNotEmpty() }.forEach { t ->
        ROMANIZED_INDIC_HINTS[t]?.let { extra.addAll(it) }
    }
    // Drop only short ASCII tokens (articles / "nda"). Native-script terms are
    // meaningful below 4 chars (e.g. "धारा"), so they are never length-filtered.
    extra.removeAll { it.length < 4 && it.all { c -> c.code < 128 } }
    if (extra.isEmpty()) return query
    return query.trim() + " " + extra.joinToString(" ")
}

internal fun routeQuery(query: String, docs: List<Pair<String, String>>): QueryRoute {
    val named = matchNamedDocs(query, docs)
    return QueryRoute(
        namedDocUris = named,
        // A compare query only splits retrieval into equal per-file slots when
        // there are actually ≥2 documents to compare (G4). With a single file,
        // a stray "vs"/"compare"/"both" token (e.g. "Godrej vs the rules")
        // otherwise forced compare mode on one doc and skewed retrieval.
        equalSlots = isCompareQuery(query) && docs.size >= 2,
        whichFile = isWhichFileQuery(query),
        thisDocument = isThisDocumentQuery(query),
        expandedQuery = expandRetrievalQuery(query, docs.map { it.second }),
    )
}
