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

/**
 * BM25-aligned query token split — includes `\p{M}` so Indic dependent vowel
 * signs stay attached to base letters (same fix as [Bm25Retriever.tokenise]).
 */
private val QUERY_SPLIT = Regex("[^\\p{L}\\p{N}\\p{M}]+")

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
    val matched = LinkedHashSet<String>()
    matched.addAll(matchNamedDocsByFilenameTokens(query, docs))
    matched.addAll(matchNamedDocsByRoleCues(query, docs))
    return matched
}

private fun matchNamedDocsByFilenameTokens(query: String, docs: List<Pair<String, String>>): Set<String> {
    val baseTokens = query.lowercase().split(QUERY_SPLIT).filter { it.isNotEmpty() }
    val qTokens = buildSet {
        for (t in baseTokens) {
            if (t.length >= 4 && t !in FILENAME_STOPWORDS) add(t)
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

private data class NamedDocRoleCue(
    val queryPhrases: List<String>,
    val filenameTokens: Set<String>,
)

/** Tier 2.7 — role words in the question ("in the guide", "gazette copy"). */
private val NAMED_DOC_ROLE_CUES = listOf(
    NamedDocRoleCue(
        queryPhrases = listOf(
            "in the guide", "from the guide", "the guide", "guide pdf", "handbook",
            "मार्गदर्शिका", "गाइड",
        ),
        filenameTokens = setOf(
            "guide", "handbook", "primer", "playbook", "consulting", "advisory", "ey",
            "मार्गदर्शिका", "गाइड",
        ),
    ),
    NamedDocRoleCue(
        queryPhrases = listOf(
            "in the summary", "from the summary", "one page summary", "brief summary",
            "सारांश",
        ),
        filenameTokens = setOf("summary", "synopsis", "overview", "brief", "onepage", "सारांश", "संक्षेप"),
    ),
    NamedDocRoleCue(
        queryPhrases = listOf("gazette", "official gazette", "gazette copy"),
        filenameTokens = setOf("gazette", "egazette"),
    ),
    NamedDocRoleCue(
        queryPhrases = listOf("sample doc", "demo doc", "sample file"),
        filenameTokens = setOf("sample", "demo", "specimen", "नमूना"),
    ),
    NamedDocRoleCue(
        queryPhrases = listOf("circular", "office circular", "परिपत्र"),
        filenameTokens = setOf("circular", "paripatra", "paripatr", "परिपत्र"),
    ),
)

internal fun matchNamedDocsByRoleCues(query: String, docs: List<Pair<String, String>>): Set<String> {
    val lower = query.lowercase()
    val cues = NAMED_DOC_ROLE_CUES.filter { cue ->
        cue.queryPhrases.any { phrase -> lower.contains(phrase) }
    }
    if (cues.isEmpty()) return emptySet()
    val filenameTokenSets = cues.flatMap { it.filenameTokens }.toSet()
    val matched = mutableSetOf<String>()
    for ((uri, name) in docs) {
        val fTokens = filenameTokens(name)
        val hit = fTokens.any { ft ->
            filenameTokenSets.any { hint ->
                ft == hint || (ft.length >= 4 && hint.length >= 4 && (ft.contains(hint) || hint.contains(ft)))
            }
        }
        if (hit) matched += uri
    }
    return matched
}

/** Tier 2.8 — equal-slot compare only for explicit multi-doc compare, not in-doc section contrast. */
internal fun shouldUseEqualSlotsCompare(query: String, docCount: Int): Boolean {
    if (docCount < 2) return false
    if (!isCompareQuery(query)) return false
    if (isInDocumentSectionContrast(query)) return false
    return true
}

internal fun isInDocumentSectionContrast(query: String): Boolean {
    val lower = query.lowercase()
    if (!Regex("(?i)\\b(vs|versus|compare)\\b").containsMatchIn(lower)) return false
    return Regex("(?i)\\b(section|sections|sec\\.?|chapter|chapters|clause|paragraph|धारा|अध्याय)\\b")
        .containsMatchIn(lower)
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
    val rewrite = queryRewriteLexiconExpansion(query)
    if (rewrite.isNotEmpty()) {
        rewrite.split(QUERY_SPLIT).filter { it.isNotEmpty() }.forEach { extra.add(it) }
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
        equalSlots = shouldUseEqualSlotsCompare(query, docs.size),
        whichFile = isWhichFileQuery(query),
        thisDocument = isThisDocumentQuery(query),
        expandedQuery = expandRetrievalQuery(query, docs.map { it.second }),
    )
}

/** Implicit attach-turn question so blank send still hits the meta/overview path. */
internal const val ATTACH_OVERVIEW_QUERY = "give an overview"

/** Attach quick-action: brief overview (P2 chip + routing). */
internal const val ATTACH_BRIEF_OVERVIEW_QUERY = "give an overview in short"

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
    if (q.isEmpty() ||
        q.equals(ATTACH_OVERVIEW_QUERY, ignoreCase = true) ||
        q.equals(ATTACH_BRIEF_OVERVIEW_QUERY, ignoreCase = true)
    ) {
        return setOf(attachmentUris.last())
    }
    return attachmentUris.toSet()
}

// ── T1-1: active-document retrieval scope ───────────────────────────────────

/** How narrowly retrieval is restricted before BM25 / meta routing. */
internal enum class RetrievalScope {
    /** Full session corpus — compare, which-file, or explicit all-files query. */
    SESSION,
    /** Default follow-up scope: the user's current working document. */
    ACTIVE_DOC,
    /** All files attached on this turn (substantive attach-turn question). */
    THIS_TURN,
    /** Filename tokens in the query matched one or more session files. */
    NAMED,
    /** Attach-turn overview / blank send — newest file in the attach batch. */
    ATTACH_OVERVIEW,
}

internal data class RetrievalScopeDecision(
    val scope: RetrievalScope,
    val restrictUris: Set<String>,
)

private val ALL_SESSION_DOCS_PHRASES = listOf(
    "all files", "all documents", "all pdfs", "all attachments", "every file",
    "each file", "all my files", "all the files", "every document",
    "सभी फाइल", "सभी फ़ाइल", "सभी दस्तावेज", "सभी दस्तावेज़",
    "அனைத்து கோப்பு", "అన్ని ఫైల్", "সব ফাইল", "બધી ફાઇલ",
)

/** User explicitly wants evidence from every indexed file in the chat. */
internal fun isAllSessionDocsQuery(query: String): Boolean {
    val lower = query.lowercase()
    return ALL_SESSION_DOCS_PHRASES.any { lower.contains(it) }
}

/**
 * T1-1 — resolve retrieval corpus for this turn. Returns a hard [restrictUris]
 * set when scope is narrow; empty set means search the full session corpus.
 */
internal fun resolveRetrievalScope(
    query: String,
    sessionDocs: List<Pair<String, String>>,
    attachmentUris: List<String>,
    activeDocUri: String?,
    route: QueryRoute,
    recencyDocUri: String? = null,
): RetrievalScopeDecision {
    val sessionUriSet = sessionDocs.map { it.first }.toSet()
    if (sessionUriSet.isEmpty()) {
        return RetrievalScopeDecision(RetrievalScope.SESSION, emptySet())
    }

    if (route.equalSlots) {
        return RetrievalScopeDecision(RetrievalScope.SESSION, emptySet())
    }
    if (route.whichFile) {
        return RetrievalScopeDecision(RetrievalScope.SESSION, emptySet())
    }
    if (isAllSessionDocsQuery(query)) {
        return RetrievalScopeDecision(RetrievalScope.SESSION, emptySet())
    }

    if (attachmentUris.isNotEmpty()) {
        val attachRestrict = restrictUrisForAttachTurn(query, attachmentUris)
        val scope = if (attachRestrict.size == 1 && attachRestrict.single() == attachmentUris.last()) {
            RetrievalScope.ATTACH_OVERVIEW
        } else {
            RetrievalScope.THIS_TURN
        }
        return RetrievalScopeDecision(scope, attachRestrict)
    }

    if (route.namedDocUris.isNotEmpty()) {
        val named = route.namedDocUris.filter { it in sessionUriSet }.toSet()
        if (named.isNotEmpty()) {
            return RetrievalScopeDecision(RetrievalScope.NAMED, named)
        }
    }

    if (route.thisDocument) {
        val target = recencyDocUri?.takeIf { it in sessionUriSet }
            ?: activeDocUri?.takeIf { it in sessionUriSet }
        if (target != null) {
            return RetrievalScopeDecision(RetrievalScope.ACTIVE_DOC, setOf(target))
        }
    }

    val active = activeDocUri?.takeIf { it in sessionUriSet }
        ?: sessionDocs.singleOrNull()?.first.takeIf { sessionUriSet.size == 1 }

    if (active != null) {
        return RetrievalScopeDecision(RetrievalScope.ACTIVE_DOC, setOf(active))
    }

    return RetrievalScopeDecision(RetrievalScope.SESSION, emptySet())
}

/**
 * Mild recency boost only when the question does not name a file and is not a
 * cross-session substance ask — otherwise BM25 should win, not last-indexed order.
 */
internal fun shouldApplyRecencySessionBoost(
    query: String,
    route: QueryRoute,
): Boolean {
    if (route.namedDocUris.isNotEmpty()) return false
    if (route.equalSlots || route.whichFile) return false
    if (route.thisDocument) return true
    if (isAllSessionDocsQuery(query)) return false
    if (isTabularAmountQuery(query)) return false
    if (activeTopicCategories(query).isNotEmpty()) return false
    if (extractSectionRefs(query).isNotEmpty()) return false
    if (isStructureCountQuery(query) || isStructureListQuery(query)) return false
    if (isIndexedSessionTopicalWithoutDocCues(query)) return false
    return true
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
    // T1-2 — structure count/list must stay LIST even when "list" triggers metaOverview.
    if (isStructureListQuery(query)) return RagAnswerShape.LIST
    // Wave 1 P3 — chapter highlights/summary need span-shaped answers, not narrow QA.
    if (isChapterSpanQuery(query)) {
        val lower = query.lowercase()
        return if (Regex("(?i)highlights?|key points?|summary|summar").containsMatchIn(lower)) {
            RagAnswerShape.LIST
        } else {
            RagAnswerShape.OVERVIEW
        }
    }
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
    if (isTabularAmountQuery(query)) return RagAnswerShape.LIST
    return RagAnswerShape.NARROW_QA
}

// ── B1 / T1-4: substance & tabular amount queries ───────────────────────────

private val TABULAR_AMOUNT_TOKENS = setOf(
    "penalty", "penalties", "fine", "fines", "punishment", "damages",
    "monetary", "rupee", "rupees", "crore", "lakhs", "lakh",
    "jurmana", "jurmaana",
    "fee", "fees", "tariff", "charge", "charges", "cost", "costs",
    "rate", "rates", "amount", "amounts", "pricing", "price",
)

/** T1-4 — penalties, fees, tariffs, schedule rows with amounts. */
internal fun isTabularAmountQuery(query: String): Boolean {
    val lower = query.lowercase().trim()
    if (lower.isEmpty()) return false
    val tokens = lower.split(QUERY_SPLIT).filter { it.isNotEmpty() }
    if (tokens.any { it in TABULAR_AMOUNT_TOKENS }) return true
    if (lower.contains("penalt")) return true
    if (query.contains("दंड") || query.contains("जुर्माना")) return true
    if (query.contains("शुल्क")) return true
    if (Regex("(?i)\\bschedule\\b").containsMatchIn(query) &&
        (lower.contains("penalt") || lower.contains("fee") || lower.contains("fine") ||
            query.contains("दंड") || query.contains("शुल्क"))
    ) {
        return true
    }
    if (query.contains("अनुसूची") || query.contains("अनुसुची")) {
        if (lower.contains("penalt") || lower.contains("fine") || lower.contains("fee") ||
            query.contains("दंड") || query.contains("शुल्क")
        ) {
            return true
        }
    }
    return false
}

// T1-2 — document structure units (chapters, sections, headings, …).
private val STRUCTURE_UNIT_TOKENS = setOf(
    "chapter", "chapters", "section", "sections", "part", "parts",
    "heading", "headings", "article", "articles", "annex", "annexure",
    "annexures", "appendix", "appendices",
)

private val STRUCTURE_COUNT_PATTERN = Regex(
    "(?i)((?:how many|number of|total|count)\\s+.{0,32}" +
        "\\b(chapters?|sections?|parts?|headings?|articles?|annexes?|annexures?|appendices?)\\b" +
        "|(?:give|tell)\\s+.{0,24}(?:total|number)\\s+.{0,24}\\b(chapters?|sections?|parts?)\\b)",
)

/** T1-2 — asks how many chapters/sections/etc. the document has. */
internal fun isStructureCountQuery(query: String): Boolean {
    val lower = query.lowercase().trim()
    if (lower.isEmpty()) return false
    if (STRUCTURE_COUNT_PATTERN.containsMatchIn(lower)) return true
    if (query.contains("कुल") && (query.contains("अध्याय") || query.contains("धारा"))) return true
    if (query.contains("कितने") && (query.contains("अध्याय") || query.contains("धारा"))) return true
    return false
}

/**
 * T1-2 — list/enumerate document structure (chapters, sections, headings).
 * Includes count-shaped asks so retrieval always pulls outline + markers.
 */
internal fun isStructureListQuery(query: String): Boolean {
    if (isStructureCountQuery(query)) return true
    val lower = query.lowercase().trim()
    if (lower.isEmpty()) return false
    val hasUnit = STRUCTURE_UNIT_TOKENS.any { lower.contains(it) } ||
        query.contains("अध्याय") || query.contains("धारा") || query.contains("खंड")
    if (!hasUnit) return false
    if (isListRequest(query)) return true
    if (lower.contains("enumerate")) return true
    if (lower.contains("name all") || lower.contains("names of")) return true
    return false
}

/** Which structure marker family the query targets (default chapter). */
internal fun structureMarkerKind(query: String): String {
    val lower = query.lowercase()
    return when {
        lower.contains("section") || query.contains("धारा") -> "section"
        lower.contains("part") -> "part"
        lower.contains("heading") -> "heading"
        lower.contains("article") -> "article"
        lower.contains("annex") || lower.contains("appendix") -> "annex"
        else -> "chapter"
    }
}

internal fun bypassMetaForStructureQuery(query: String): Boolean = isStructureListQuery(query)

/**
 * B1 — legal-substance questions must not take the meta/structural path
 * (outline-only). Includes penalties, Schedule, fines, and structure queries.
 */
/** Phase A2 — meta path only for true overview/structure; not absence/mechanism asks. */
internal fun blocksMetaRouteForSubstanceClause(query: String): Boolean {
    val lower = query.lowercase().trim()
    if (lower.isEmpty()) return false
    if (Regex("(?i)\\b(not|except|without|excluding)\\b").containsMatchIn(lower)) return true
    if (extractQueryFocusEntities(query).isNotEmpty()) return true
    if (isCompareQuery(query)) return true
    if (Regex("(?i)\\bexamples?\\b").containsMatchIn(lower)) return true
    val hasSummarize = Regex("(?i)\\b(summar(?:y|ize|ise)|synopsis)\\b").containsMatchIn(lower)
    val hasSubstanceCue = Regex(
        "(?i)\\b(how|what|why|role|affect|difference|compare|interact|feedback|evidence|factor)\\b",
    ).containsMatchIn(lower)
    if (hasSummarize && hasSubstanceCue) return true
    if (
        Regex("(?i)\\bconclusions?\\b").containsMatchIn(lower) &&
        !isBareConclusionsStructureQuery(query)
    ) {
        return true
    }
    if (
        Regex("(?i)\\b(topics?|subjects?)\\b").containsMatchIn(lower) &&
        Regex("(?i)\\b(about|regarding|related|climate|change|discuss|cover)\\b").containsMatchIn(lower)
    ) {
        return true
    }
    return false
}

internal fun isBareConclusionsStructureQuery(query: String): Boolean {
    val lower = query.lowercase().trim()
    if (lower in setOf("conclusions", "conclusion")) return true
    if (Regex("(?i)^(what are the )?conclusions?\\??$").matches(lower)) return true
    return Regex("(?i)^(list|show) (the )?conclusions?").containsMatchIn(lower)
}

internal fun bypassMetaForSubstanceQuery(query: String): Boolean {
    if (bypassMetaForStructureQuery(query)) return true
    if (isTabularAmountQuery(query)) return true
    if (blocksMetaRouteForSubstanceClause(query)) return true
    val lower = query.lowercase().trim()
    if (lower.isEmpty()) return false
    if (lower.contains("schedule")) return true
    if (query.contains("अनुसूची") || query.contains("अनुसुची")) return true
    return false
}

/** Penalty / Schedule / fine queries — BM25 path + tabular anchoring (B1/T1-4). */
internal fun isPenaltyScheduleQuery(query: String): Boolean = isTabularAmountQuery(query)

/** Meta route unless follow-up or B1 substance bypass applies. */
internal fun effectiveMetaRouteReason(query: String, isFollowUp: Boolean): String? {
    if (isFollowUp || bypassMetaForSubstanceQuery(query)) return null
    return RagDocumentRepository.metaRouteReason(query)
}

/**
 * B2-1 — query names a numbered section and asks about penalty / fine / amount.
 * Retrieval should anchor the section block and Schedule/amount rows from the
 * same document when possible.
 */
internal fun isSectionPenaltyComboQuery(query: String): Boolean {
    if (extractSectionRefs(query).none { it.kind == "section" }) return false
    return isPenaltyScheduleQuery(query)
}

/**
 * T5.2 — prefer Schedule/tabular retrieval from the scoped or active document
 * when the session has multiple files (e.g. Act + guide).
 */
internal fun resolveTabularPreferDocUri(
    query: String,
    restrictUris: Set<String>,
    activeDocUri: String?,
): String? {
    if (!isTabularAmountQuery(query)) return null
    if (restrictUris.size == 1) return restrictUris.single()
    val active = activeDocUri?.takeIf { restrictUris.isEmpty() || it in restrictUris }
    return active
}

/** T5.1 — BM25 expansion tokens for structure count/list queries. */
internal fun structureMarkerBm25Expansion(query: String): String =
    when (structureMarkerKind(query)) {
        "section" -> " section sections धारा"
        "part" -> " part parts"
        "heading" -> " heading headings"
        "annex" -> " annex appendix annexure"
        else -> " CHAPTER Chapter chapter अध्याय"
    }

/** Dynamic top-K: fewer chunks for narrow QA, more for overview/compare (P0 #2). */
internal fun topKForAnswerShape(shape: RagAnswerShape, equalSlots: Boolean): Int = when {
    equalSlots -> RagDocumentRepository.DEFAULT_TOP_K
    shape == RagAnswerShape.OVERVIEW_SHORT -> 5
    shape == RagAnswerShape.OVERVIEW -> 6
    shape == RagAnswerShape.LIST -> 6
    else -> 4
}

/** Wave 1 P2 — chapter/topic/tabular queries need a wider retrieval window. */
internal const val SPAN_PRESERVING_TOP_K = 12

/** Contiguous body chunks to keep from an anchored section window. */
internal const val SPAN_ANCHOR_WINDOW = 10

internal const val ANCHORED_SPAN_COLLAPSE_MAX = 12

/**
 * Queries that need a contiguous section/chapter/table span in the prompt,
 * not 3 deduped fragments from a stacked anchor pass.
 */
internal fun isSpanPreservingQuery(query: String): Boolean {
    if (isChapterSpanQuery(query)) return true
    if (extractSectionRefs(query).isNotEmpty()) return true
    if (isTabularAmountQuery(query)) return true
    if (activeTopicCategories(query).isNotEmpty()) return true
    if (isStructureListQuery(query)) return true
    if (isStructureCountQuery(query)) return false
    return hasDocumentQueryCues(query)
}

internal fun anchorWindowMax(query: String): Int =
    if (isSpanPreservingQuery(query)) SPAN_ANCHOR_WINDOW else 5

internal fun effectiveRetrievalTopK(
    query: String,
    shape: RagAnswerShape,
    equalSlots: Boolean,
): Int {
    val base = topKForAnswerShape(shape, equalSlots)
    return if (isSpanPreservingQuery(query)) maxOf(base, SPAN_PRESERVING_TOP_K) else base
}

/** Nudge P0 answer shape when Settings → Reply length is Short/Long (P2 #11). */
internal fun isReplyLengthShapeLockedQuery(query: String): Boolean =
    isStructureCountQuery(query) ||
        isStructureListQuery(query) ||
        isChapterSpanQuery(query)

internal fun applyReplyLengthToAnswerShape(
    shape: RagAnswerShape,
    length: com.saarthi.core.i18n.ReplyLength,
    query: String = "",
): RagAnswerShape = when (length) {
    com.saarthi.core.i18n.ReplyLength.SHORT -> when (shape) {
        RagAnswerShape.OVERVIEW -> RagAnswerShape.OVERVIEW_SHORT
        RagAnswerShape.LIST -> if (isReplyLengthShapeLockedQuery(query)) shape else RagAnswerShape.NARROW_QA
        else -> shape
    }
    com.saarthi.core.i18n.ReplyLength.LONG,
    com.saarthi.core.i18n.ReplyLength.MEDIUM,
    -> shape
}

// ── T1-5: unattached external regime (GDPR/ISO compare without that file) ───

internal data class UnattachedExternalDecision(
    val active: Boolean,
    val regimes: List<String> = emptyList(),
)

internal data class ExternalRegimeMarker(
    val queryToken: String,
    val label: String,
    val filenameHints: Set<String>,
)

private val EXTERNAL_REGIME_MARKERS = listOf(
    ExternalRegimeMarker("gdpr", "GDPR", setOf("gdpr", "european", "union")),
    ExternalRegimeMarker("general data protection", "GDPR", setOf("gdpr", "european")),
    ExternalRegimeMarker("iso 27001", "ISO 27001", setOf("iso", "27001")),
    ExternalRegimeMarker("iso 27002", "ISO 27002", setOf("iso", "27002")),
    ExternalRegimeMarker("hipaa", "HIPAA", setOf("hipaa")),
    ExternalRegimeMarker("ccpa", "CCPA", setOf("ccpa", "california")),
    ExternalRegimeMarker("pci dss", "PCI DSS", setOf("pci", "dss")),
    ExternalRegimeMarker("sox", "SOX", setOf("sox", "sarbanes")),
)

internal fun regimePresentInFilename(docName: String, marker: ExternalRegimeMarker): Boolean {
    val tokens = filenameTokens(docName)
    return marker.filenameHints.any { hint -> tokens.any { t -> t.contains(hint) || hint.contains(t) } }
}

/**
 * T1-5 — query names an external standard/regime not represented in attached filenames.
 */
internal fun detectUnattachedExternalQuery(
    query: String,
    sessionDocNames: List<String>,
): UnattachedExternalDecision {
    val lower = query.lowercase().trim()
    if (lower.isEmpty() || sessionDocNames.isEmpty()) {
        return UnattachedExternalDecision(active = false)
    }
    val matched = EXTERNAL_REGIME_MARKERS.filter { marker ->
        lower.contains(marker.queryToken) &&
            sessionDocNames.none { regimePresentInFilename(it, marker) }
    }
    if (matched.isEmpty()) return UnattachedExternalDecision(active = false)
    return UnattachedExternalDecision(
        active = true,
        regimes = matched.map { it.label }.distinct(),
    )
}

// ── Wave 1: turn mode (document vs general chat lifecycle) ─────────────────

/** Whether this turn should retrieve and ground in session documents. */
internal enum class RagTurnMode {
    /** Normal assistant chat — no retrieval (with or without indexed docs). */
    PLAIN_CHAT,
    /** Docs exist but this message is general / opt-out — skip retrieval. */
    GENERAL_KNOWLEDGE,
    /** User is asking about attached document(s). */
    DOCUMENT_GROUNDED,
    /** Document + general knowledge in one message — retrieve doc part; label both slices. */
    MIXED,
}

private val SMALL_TALK_EXACT = setOf(
    "hi", "hello", "hey", "hii", "hola", "thanks", "thank you", "ok", "okay",
    "namaste", "नमस्ते", "नमस्कार", "हैलो", "हाय",
)

private val FOLLOW_UP_TOKENS_MODE = setOf(
    "also", "more", "another", "continue", "elaborate", "clarify",
    "what", "how", "why", "and", "plus", "further", "अधिक", "और", "विस्तार",
)

private val DOCUMENT_SCOPE_PHRASES = I18N_DOCUMENT_SCOPE_PHRASES

private val DOCUMENT_QUERY_CUE_PATTERN = Regex(
    "(?i)\\b(" +
        "chapter|chapters|section|sections|schedule|annex|appendix|article|" +
        "document|documents|file|files|pdf|attached|attachment|overview|excerpt|" +
        "content|contents|clause|paragraph|heading|highlights?|fiduciary|" +
        "obligation|obligations|penalt|appeal|provision|provisions|board|" +
        "duties|rights|tabular|amount|fee|fine|jurmana|breach|notification|" +
        "applicable|applicability|children|consent|processing|principal|" +
        "salary|balance|statement|address|timeline|personal|धारा|अध्याय|अनुसूची|दस्तावेज|फाइल|फ़ाइल" +
        ")\\b",
)

private val NON_TOPICAL_CHAT_PATTERN = Regex(
    "(?i)\\b(joke|jokes|favorite color|favourite colour|your favorite)\\b",
)

private val TOPICAL_QUESTION_LEADS = setOf(
    "what", "how", "why", "when", "where", "who", "which",
    "is", "are", "was", "were", "does", "do", "did", "can", "should", "could",
    "list", "explain", "tell", "describe", "give", "name", "show",
) + I18N_TOPICAL_QUESTION_LEADS

private val GENERAL_KNOWLEDGE_TOPIC_PATTERN = Regex(
    "(?i)\\b(" +
        "black holes?|photosynthesis|gravity|solar system|atom|molecule|evolution|" +
        "dinosaurs|volcano|earthquake|pythagoras|newton|einstein|quantum|" +
        "school kid|school child|5 year old|five year old|beginner" +
        ")\\b",
)

/** Explicit user request to ignore attached documents for this turn. */
internal fun isDocumentOptOutQuery(query: String): Boolean {
    val lower = query.lowercase().trim()
    if (lower.isEmpty()) return false
    return hasI18nDocumentOptOutCue(query) ||
        Regex("(?i)\\bin general\\b").containsMatchIn(lower) &&
        Regex("(?i)\\b(explain|describe|tell|what is|what are)\\b").containsMatchIn(lower)
}

internal fun hasDocumentQueryCues(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return false
    if (DOCUMENT_QUERY_CUE_PATTERN.containsMatchIn(q)) return true
    if (DOCUMENT_SCOPE_PHRASES.any { q.contains(it, ignoreCase = true) }) return true
    if (RagDocumentRepository.metaRouteReason(q) != null) return true
    if (isStructureCountQuery(q) || isStructureListQuery(q)) return true
    if (isTabularAmountQuery(q)) return true
    if (extractSectionRefs(q).isNotEmpty()) return true
    return THIS_DOC_PHRASES.any { q.contains(it, ignoreCase = true) } ||
        WHICH_FILE_PHRASES.any { q.contains(it, ignoreCase = true) } ||
        COMPARE_PHRASES.any { q.contains(it, ignoreCase = true) } ||
        COMPARE_TOKENS.any { q.lowercase().split(QUERY_SPLIT).contains(it) }
}

internal fun isSmallTalkQuery(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return false
    val tokens = trimmed.lowercase().split(QUERY_SPLIT).filter { it.isNotEmpty() }
    if (tokens.size <= 2 && tokens.all { it in SMALL_TALK_EXACT }) return true
    return tokens.size == 1 && tokens[0] in SMALL_TALK_EXACT
}

internal fun hasGeneralKnowledgeTopicCues(query: String): Boolean =
    GENERAL_KNOWLEDGE_TOPIC_PATTERN.containsMatchIn(query)

internal fun isLikelyGeneralKnowledgeWithoutDocCues(query: String): Boolean {
    if (hasDocumentQueryCues(query)) return false
    return hasGeneralKnowledgeTopicCues(query)
}

private val PLAIN_CHAT_IDENTITY_PATTERN = Regex(
    "(?i)\\b(" +
        "who are you|what are you|tell me about yourself|about yourself|about saarthi|" +
        "what can you do|how do you work|what do you do" +
        ")\\b",
)

private val MIXED_QUERY_BRIDGE_PATTERN = Regex(
    "(?i)\\b(" +
        "from the document and|from the file and|from this (document|file|pdf) and|" +
        "in the (act|document|file|pdf) and|in this (act|document|file|pdf) and|" +
        "document and (also|in general)|file and (also|in general)|" +
        "act and (also|in general)|pdf and (also|in general)" +
        ")\\b",
)

/** Identity / capability questions that stay plain chat even when docs are attached. */
internal fun isAssistantIdentityQuery(query: String): Boolean =
    PLAIN_CHAT_IDENTITY_PATTERN.containsMatchIn(query.trim())

/**
 * Wave 3 — query asks for both document-grounded and general-knowledge slices.
 * Opt-out wins over mixed; unattached external regimes (T1-5) with doc cues → mixed.
 */
internal fun isMixedDocumentQuery(
    query: String,
    sessionDocNames: List<String> = emptyList(),
): Boolean {
    if (isDocumentOptOutQuery(query)) return false
    val lower = query.lowercase().trim()
    if (lower.isEmpty()) return false

    val docCues = hasDocumentQueryCues(query)
    val gkTopic = hasGeneralKnowledgeTopicCues(query)
    val compareBridge = isCompareQuery(query) || MIXED_QUERY_BRIDGE_PATTERN.containsMatchIn(lower)
    val unattachedExternal = if (sessionDocNames.isNotEmpty()) {
        detectUnattachedExternalQuery(query, sessionDocNames).active
    } else {
        false
    }

    if (docCues && (gkTopic || unattachedExternal || compareBridge)) return true
    if (MIXED_QUERY_BRIDGE_PATTERN.containsMatchIn(lower) && (docCues || gkTopic)) return true
    return false
}

internal fun shouldRetrieveForRagTurnMode(mode: RagTurnMode): Boolean =
    mode == RagTurnMode.DOCUMENT_GROUNDED || mode == RagTurnMode.MIXED

internal fun shouldPinDocsForRagTurnMode(mode: RagTurnMode): Boolean =
    shouldRetrieveForRagTurnMode(mode)

internal fun requiresForceGroundedDelivery(mode: RagTurnMode, retrievedNonEmpty: Boolean): Boolean =
    mode == RagTurnMode.DOCUMENT_GROUNDED && retrievedNonEmpty

internal fun isFollowUpTopicCarry(query: String, priorQuery: String): Boolean {
    if (!isFollowUpContinuationQuery(query)) return false
    if (hasDocumentQueryCues(priorQuery)) return true
    return isIndexedSessionTopicalQuestion(priorQuery)
}

/**
 * Phase 1.2 — indexed session + topical question without GK cues → retrieve,
 * but still downstream-gated (no strong-match / Sources on weak hits).
 */
internal fun isIndexedSessionTopicalQuestion(query: String): Boolean {
    if (NON_TOPICAL_CHAT_PATTERN.containsMatchIn(query)) return false
    if (isAssistantIdentityQuery(query) || isSmallTalkQuery(query)) return false
    if (hasGeneralKnowledgeTopicCues(query) || isDocumentOptOutQuery(query)) return false
    if (hasAmbiguousTopicalSubjectCues(query)) return true
    val tokens = query.lowercase().split(QUERY_SPLIT).filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.size > 24) return false
    if (tokens.any { it in TOPICAL_QUESTION_LEADS }) return true
    return query.contains('?')
}

internal fun isFollowUpContinuationQuery(query: String): Boolean {
    val lower = query.lowercase().trim()
    if (Regex("(?i)\\b(explain more|explain further|tell me more|go on|continue)\\b").containsMatchIn(lower)) {
        return true
    }
    val tokens = lower.split(QUERY_SPLIT).filter { it.isNotEmpty() }
    // Short continuations only — avoid hijacking standalone off-topic questions ("what's your favorite…").
    if (tokens.size > 4) return false
    return tokens.take(3).any { it in FOLLOW_UP_TOKENS_MODE }
}

/**
 * Wave 1 — classify whether this turn should retrieve from session documents.
 * Files in the chat are **available**, not **forced**, except when the user
 * attaches this turn or names document structure/content.
 */
internal fun classifyRagTurnMode(
    query: String,
    sessionDocCount: Int,
    attachmentsThisTurn: Boolean,
    sessionDocNames: List<String> = emptyList(),
    priorQuery: String? = null,
): RagTurnMode {
    if (sessionDocCount == 0 && !attachmentsThisTurn) {
        return RagTurnMode.PLAIN_CHAT
    }

    if (isDocumentOptOutQuery(query)) {
        return RagTurnMode.GENERAL_KNOWLEDGE
    }

    if (isMixedDocumentQuery(query, sessionDocNames)) {
        return RagTurnMode.MIXED
    }

    if (isSmallTalkQuery(query) && !hasDocumentQueryCues(query)) {
        return RagTurnMode.GENERAL_KNOWLEDGE
    }

    if (isAssistantIdentityQuery(query) && !hasDocumentQueryCues(query)) {
        return RagTurnMode.PLAIN_CHAT
    }

    if (attachmentsThisTurn && query.trim().isNotEmpty() && !isDocumentOptOutQuery(query)) {
        return RagTurnMode.DOCUMENT_GROUNDED
    }

    if (hasDocumentQueryCues(query)) {
        return RagTurnMode.DOCUMENT_GROUNDED
    }

    if (!priorQuery.isNullOrBlank() && isFollowUpScopeUpgrade(query, priorQuery)) {
        return RagTurnMode.DOCUMENT_GROUNDED
    }

    if (isLikelyGeneralKnowledgeWithoutDocCues(query)) {
        return RagTurnMode.GENERAL_KNOWLEDGE
    }

    if (isFollowUpContinuationQuery(query)) {
        return RagTurnMode.DOCUMENT_GROUNDED
    }

    if (!priorQuery.isNullOrBlank() && isFollowUpTopicCarry(query, priorQuery)) {
        return RagTurnMode.DOCUMENT_GROUNDED
    }

    if (attachmentsThisTurn) {
        return RagTurnMode.DOCUMENT_GROUNDED
    }

    if (sessionDocCount > 0 && isIndexedSessionTopicalQuestion(query)) {
        return RagTurnMode.DOCUMENT_GROUNDED
    }

    // Wave 3 — indexed docs are available, not forced; ambiguous → plain chat.
    return RagTurnMode.PLAIN_CHAT
}
