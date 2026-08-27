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

    val active = activeDocUri?.takeIf { it in sessionUriSet }
        ?: sessionDocs.singleOrNull()?.first.takeIf { sessionUriSet.size == 1 }

    if (active != null) {
        return RetrievalScopeDecision(RetrievalScope.ACTIVE_DOC, setOf(active))
    }

    return RetrievalScopeDecision(RetrievalScope.SESSION, emptySet())
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
internal fun bypassMetaForSubstanceQuery(query: String): Boolean {
    if (bypassMetaForStructureQuery(query)) return true
    if (isTabularAmountQuery(query)) return true
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

/** Dynamic top-K: fewer chunks for narrow QA, more for overview/compare (P0 #2). */
internal fun topKForAnswerShape(shape: RagAnswerShape, equalSlots: Boolean): Int = when {
    equalSlots -> RagDocumentRepository.DEFAULT_TOP_K
    shape == RagAnswerShape.OVERVIEW_SHORT -> 5
    shape == RagAnswerShape.OVERVIEW -> 6
    shape == RagAnswerShape.LIST -> 6
    else -> 4
}

/** Nudge P0 answer shape when Settings → Reply length is Short/Long (P2 #11). */
internal fun applyReplyLengthToAnswerShape(
    shape: RagAnswerShape,
    length: com.saarthi.core.i18n.ReplyLength,
): RagAnswerShape = when (length) {
    com.saarthi.core.i18n.ReplyLength.SHORT -> when (shape) {
        RagAnswerShape.OVERVIEW -> RagAnswerShape.OVERVIEW_SHORT
        RagAnswerShape.LIST -> RagAnswerShape.NARROW_QA
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
