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

/** Conservative English terms for Hindi (Devanagari) questions about English files. */
internal val HINDI_QUERY_ENGLISH_HINTS = listOf(
    "document", "agreement", "amount", "penalty", "section", "clause",
    "account", "statement", "confidential", "term",
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

internal fun expandRetrievalQuery(query: String, docNames: List<String>): String {
    val extra = LinkedHashSet<String>()
    docNames.forEach { extra.addAll(filenameTokens(it)) }
    if (queryHasDevanagari(query)) extra.addAll(HINDI_QUERY_ENGLISH_HINTS)
    extra.removeAll { it.length < 4 }
    if (extra.isEmpty()) return query
    return query.trim() + " " + extra.joinToString(" ")
}

internal fun routeQuery(query: String, docs: List<Pair<String, String>>): QueryRoute {
    val named = matchNamedDocs(query, docs)
    return QueryRoute(
        namedDocUris = named,
        equalSlots = isCompareQuery(query),
        whichFile = isWhichFileQuery(query),
        thisDocument = isThisDocumentQuery(query),
        expandedQuery = expandRetrievalQuery(query, docs.map { it.second }),
    )
}
