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
    "pdf", "txt", "log", "docx", "doc", "csv", "xlsx", "xls", "pptx", "ppt",
    "attachment", "attached",
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
private val COMPARE_PHRASES = listOf(
    "each of",
    // Devanagari (Hindi/Marathi)
    "दोनों", "तुलना", "दोन्ही",
    // Tamil / Telugu / Bengali / Kannada / Gujarati / Punjabi / Odia
    "இரண்டும்", "ஒப்பிடு", "ஒப்பிட்டு",
    "రెండూ", "పోల్చు", "పోలిక",
    "দুটো", "তুলনা", "উভয়",
    "ಎರಡೂ", "ಹೋಲಿಕೆ", "ಹೋಲಿಸು",
    "બંને", "સરખામણી",
    "ਦੋਵੇਂ", "ਤੁਲਨਾ",
    "ଦୁଇଟି", "ତୁଳନା", "ଉଭୟ",
)
private val WHICH_FILE_PHRASES = listOf(
    "which file", "which document", "which pdf", "which one",
    // Devanagari
    "कौन सी फ़ाइल", "कौन सी फाइल", "कौन सा", "किस फ़ाइल", "किस फाइल",
    // Tamil / Telugu / Bengali / Kannada / Gujarati / Punjabi / Odia
    "எந்த கோப்பு", "எந்த ஆவணம்",
    "ఏ ఫైల్", "ఏ పత్రం",
    "কোন ফাইল", "কোন নথি",
    "ಯಾವ ಫೈಲ್", "ಯಾವ ದಾಖಲೆ",
    "કઈ ફાઇલ", "કયો દસ્તાવેજ",
    "ਕਿਹੜੀ ਫਾਈਲ", "ਕਿਹੜਾ ਦਸਤਾਵੇਜ਼",
    "କେଉଁ ଫାଇଲ", "କେଉଁ ଦଲିଲ",
)
private val THIS_DOC_PHRASES = listOf(
    "this document", "this pdf", "the pdf", "this file",
    // Devanagari
    "इस दस्तावेज़", "इस दस्तावेज", "इस फाइल", "इस फ़ाइल", "ये वाली",
    // Tamil / Telugu / Bengali / Kannada / Gujarati / Punjabi / Odia
    "இந்த ஆவணம்", "இந்த கோப்பு",
    "ఈ పత్రం", "ఈ ఫైల్",
    "এই নথি", "এই ফাইল",
    "ಈ ದಾಖಲೆ", "ಈ ಫೈಲ್",
    "આ દસ્તાવેજ", "આ ફાઇલ",
    "ਇਹ ਦਸਤਾਵੇਜ਼", "ਇਹ ਫਾਈਲ",
    "ଏହି ଦଲିଲ", "ଏହି ଫାଇଲ",
)

internal fun filenameTokens(name: String): Set<String> {
    var stem = name.lowercase()
    for (ext in listOf(".pdf", ".docx", ".doc", ".txt", ".log", ".csv", ".xlsx", ".xls", ".pptx", ".ppt")) {
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
    val baseTokens = query.lowercase().split(QUERY_SPLIT).filter { it.isNotEmpty() }
    val qTokens = buildSet {
        for (t in baseTokens) {
            if (t.length >= 4 && t !in FILENAME_STOPWORDS) add(t)
            // Cross-script bridge: a romanized-Indic query term ("khata",
            // "jurmana") also matches an English-named file ("account…",
            // "penalty…"). Only the ASCII expansions are used here — a
            // Devanagari gloss can't match a Latin filename, and same-script
            // filename tokens are already covered by the raw query tokens.
            ROMANIZED_INDIC_HINTS[t]?.forEach { hint ->
                if (hint.length >= 4 && hint.all { c -> c.code < 128 }) add(hint)
            }
        }
    }
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

/** Implicit attach-turn question so blank send still hits the meta/overview path. */
internal const val ATTACH_OVERVIEW_QUERY = "give an overview"

internal fun attachTurnQuery(userText: String, hasAttachments: Boolean): String {
    val trimmed = userText.trim()
    if (trimmed.isNotEmpty()) return trimmed
    return if (hasAttachments) ATTACH_OVERVIEW_QUERY else ""
}

/**
 * Attach-turn hard filter. A blank send (or the overview quick-action) scopes
 * to the newest file in the batch so a repeated "overview" tap cannot mix
 * earlier files. Any other typed question still includes every this-turn URI
 * (G1).
 */
internal fun restrictUrisForAttachTurn(
    retrievalQuery: String,
    attachmentUris: List<String>,
): Set<String> {
    if (attachmentUris.isEmpty()) return emptySet()
    val q = retrievalQuery.trim()
    if (q.isEmpty() || q.equals(ATTACH_OVERVIEW_QUERY, ignoreCase = true)) {
        return setOf(attachmentUris.last())
    }
    return attachmentUris.toSet()
}

internal fun isDuplicateTurn(
    lastQuery: String?,
    lastUris: Set<String>,
    newQuery: String,
    newUris: Set<String>,
): Boolean {
    if (lastQuery == null || newQuery.isEmpty()) return false
    return lastQuery.equals(newQuery, ignoreCase = true) && lastUris == newUris
}

/** How the model should shape a grounded reply for this turn (P0 answer focus). */
internal enum class RagAnswerShape {
    OVERVIEW_SHORT,
    OVERVIEW,
    NARROW_QA,
    LIST,
}

private val BRIEF_EN_TOKENS = setOf(
    "short", "brief", "briefly", "tldr", "quick", "concise", "summary",
)

/** Brief / short overview or answer — English + Indic scripts the app offers. */
internal val BRIEF_REQUEST_PATTERN = Regex(
    "(" +
        "in short|quick summary|" +
        // Devanagari (Hindi/Marathi)
        "संक्षिप्त|सक्षिप्त|संक्षेप|छोटा|छोटे|" +
        // Tamil / Telugu / Bengali / Kannada / Gujarati / Punjabi / Odia
        "சுருக்க|குறுகிய|" +
        "సంక్షిప్త|చిన్న|" +
        "সংক্ষিপ্ত|ছোট|" +
        "ಸಂಕ್ಷಿಪ್ತ|ಚಿಕ್ಕ|" +
        "સંક્ષિપ્ત|ટૂંકું|" +
        "ਸੰਖੇਪ|ਛੋਟਾ|" +
        "ସଂକ୍ଷିପ୍ତ|ଛୋଟ" +
        ")",
)

internal fun isBriefRequest(query: String): Boolean {
    val lower = query.lowercase().trim()
    if (lower.contains("in short")) return true
    if (BRIEF_REQUEST_PATTERN.containsMatchIn(query)) return true
    val tokens = lower.split(QUERY_SPLIT).filter { it.isNotEmpty() }
    return tokens.any { it in BRIEF_EN_TOKENS }
}

internal fun isListRequest(query: String): Boolean {
    val lower = query.lowercase().trim()
    if (lower.contains("list all") || lower.contains("list the") ||
        lower.contains("list of") || lower.contains("enumerate")
    ) {
        return true
    }
    val tokens = lower.split(QUERY_SPLIT).filter { it.isNotEmpty() }
    if (tokens.any { it == "list" || it == "lists" }) return true
  // Devanagari list cues
    if (lower.contains("सूची") || lower.contains("सूचि") || lower.contains("सभी")) {
        return tokens.any { it == "list" || it == "lists" || it == "all" }
    }
    return false
}

/**
 * Classifies the user's message for grounded generation length/focus.
 * [metaOverview] is true when retrieval took the meta/overview path
 * ([RagDocumentRepository.metaRouteReason] non-null on [ragQuery]).
 */
internal fun detectRagAnswerShape(query: String, metaOverview: Boolean): RagAnswerShape {
    val trimmed = query.trim()
    val lower = trimmed.lowercase()
    val overviewish = metaOverview ||
        trimmed.equals(ATTACH_OVERVIEW_QUERY, ignoreCase = true) ||
        lower.contains("overview") ||
        isDevanagariMetaTrigger(query)
    if (overviewish) {
        return if (isBriefRequest(query)) RagAnswerShape.OVERVIEW_SHORT
        else RagAnswerShape.OVERVIEW
    }
    if (isListRequest(query)) return RagAnswerShape.LIST
    return RagAnswerShape.NARROW_QA
}

/** Dynamic top-K: fewer chunks for narrow QA, more for overview/compare (P0 #2). */
internal fun topKForAnswerShape(shape: RagAnswerShape, equalSlots: Boolean): Int = when {
    equalSlots -> RagDocumentRepository.DEFAULT_TOP_K
    shape == RagAnswerShape.OVERVIEW_SHORT -> 5
    shape == RagAnswerShape.OVERVIEW -> 6
    shape == RagAnswerShape.LIST -> 6
    else -> 4
}
