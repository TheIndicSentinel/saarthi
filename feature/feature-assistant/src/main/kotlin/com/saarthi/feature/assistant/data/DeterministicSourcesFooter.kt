package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.CitationDisplayLabels

/**
 * A1 — system-built Sources footer from retrieval metadata, not model text.
 * A3 — localized Sources header and page/overview labels.
 * A6 — multi-file compare: at least one source line per contributing document.
 */
internal const val DETERMINISTIC_SOURCES_MAX = 3

internal fun citationChunkDocKey(chunk: RetrievedChunk): String =
    chunk.docUri.ifEmpty { chunk.docName }

/**
 * Multi-file Sources fairness when the user compares documents or retrieval
 * spans multiple files with real hits (A6 point 1).
 */
internal fun shouldFairMultiFileSources(
    equalSlots: Boolean,
    chunks: List<RetrievedChunk>,
): Boolean {
    if (equalSlots) return true
    val contributing = chunks
        .filter { it.score > 0.0 }
        .map { citationChunkDocKey(it) }
        .distinct()
    return contributing.size >= 2
}

private val SOURCES_TAIL_MARKERS_BASE = listOf("\nSources:", "\nस्रोत:", "\nस्त्रोत:", "\nSOURCES:")
private val CITATION_INDEX_IN_TAIL = Regex("""\[\d{1,2}\]""")
private val INLINE_PAGE_REF = Regex("""(?i)(?:\(|\s|,|;)p\.?\s*\d+""")
private val INLINE_PAGE_WORD = Regex("""(?i)\bpage\s+\d+""")
private val FILE_DISAMBIG_PREFIX = Regex("""(?i)^File\s+\d+:""")
private val STATUTE_TITLE_IN_LINE = Regex(
    "(?i)\\b(act|agreement|policy|law|rules|regulation|code|ordinance|notification|amendment)\\b",
)

/**
 * Removes a trailing model-written Sources block when it looks like citations
 * (numbered refs, page dots, hash filenames, prose-style act titles) — not user
 * prose mentioning "sources" in the answer body.
 */
private val INLINE_HASH_PAGE_CITE = Regex(
    """\(\s*[0-9A-Fa-f]{12,}[^)]*(?:p\.?\s*\d+|page\s+\d+)[^)]*\)""",
)
private val INLINE_HASH_ONLY_CITE = Regex(
    """\(\s*[0-9A-Fa-f]{20,}[^)]*\)""",
)
private val INLINE_PAGE_OF_NOISE = Regex(
    """(?i)\b(?:p\.?|page)\s*\d{1,4}\s+of\s+\d{2,4}\b""",
)

/**
 * Removes inline model citation attempts from the answer body (hash parentheticals)
 * before the deterministic footer is applied. Does not touch Sources blocks — those
 * are handled by [stripModelSourcesBlock].
 */
internal fun stripInlineModelCitationAttempts(text: String): String {
    var result = text.trimEnd()
    result = INLINE_HASH_PAGE_CITE.replace(result, "")
    result = INLINE_HASH_ONLY_CITE.replace(result, "")
    result = INLINE_PAGE_OF_NOISE.replace(result, "")
    return result.trimEnd()
}

internal fun stripModelSourcesBlock(text: String, labels: CitationDisplayLabels): String {
    var result = stripInlineModelCitationAttempts(text)
    val markers = SOURCES_TAIL_MARKERS_BASE + "\n${labels.sourcesHeader}" +
        listOf(" Sources:", " ${labels.sourcesHeader}")
    var changed = true
    while (changed) {
        changed = false
        for (marker in markers) {
            val idx = result.lastIndexOf(marker, ignoreCase = true)
            if (idx < 0) continue
            val tail = result.substring(idx)
            if (!looksLikeAutomatedSourcesTail(tail)) continue
            result = result.substring(0, idx).trimEnd()
            changed = true
            break
        }
        val sameLineIdx = result.lastIndexOf(" Sources:", ignoreCase = true)
        if (sameLineIdx > 0) {
            val tail = result.substring(sameLineIdx).trimStart()
            if (looksLikeAutomatedSourcesTail(tail)) {
                result = result.substring(0, sameLineIdx).trimEnd()
                changed = true
            }
        }
    }
    return result
}

/** True when a line inside a trailing Sources block looks like a model citation attempt. */
private fun looksLikeModelSourcesTailLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    if (parseDisplaySourceLine(trimmed) != null) return true
    if (looksLikeContentStamp(trimmed)) return true
    if (CITATION_INDEX_IN_TAIL.containsMatchIn(trimmed)) return true
    if (FILE_DISAMBIG_PREFIX.containsMatchIn(trimmed)) return true
    if (INLINE_PAGE_REF.containsMatchIn(trimmed) || INLINE_PAGE_WORD.containsMatchIn(trimmed)) return true
    if (trimmed.contains('·')) {
        val dotIdx = trimmed.indexOf('·')
        if (dotIdx in 1 until trimmed.lastIndex) {
            val title = trimmed.substring(0, dotIdx).trim()
            val location = trimmed.substring(dotIdx + 1).trim()
            if (title.isNotBlank() && location.isNotBlank() && !looksLikeBodyProseLine(title)) {
                if (
                    INLINE_PAGE_WORD.containsMatchIn(location) ||
                    location.equals("overview", ignoreCase = true) ||
                    location.contains("पृष्ठ")
                ) {
                    return true
                }
            }
        }
    }
    val titlePart = trimmed.substringBefore('(').substringBefore('·').trim()
    if (titlePart.isNotBlank() && looksLikeInternalCitationLabel(titlePart)) return true
    if (
        trimmed.length in 8..140 &&
        STATUTE_TITLE_IN_LINE.containsMatchIn(trimmed) &&
        !looksLikeBodyProseLine(trimmed)
    ) {
        return true
    }
    return false
}

private fun looksLikeAutomatedSourcesTail(tail: String): Boolean {
    val lower = tail.lowercase()
    if (!lower.contains("sources:") && !tail.contains("स्रोत")) return false
    val afterLabel = tail.substringAfter(':', "").trim()
    if (afterLabel.isEmpty()) return true
    val lines = afterLabel.lines().map { it.trim() }.filter { it.isNotBlank() }
    if (lines.any { looksLikeModelSourcesTailLine(it) }) return true
    return CITATION_INDEX_IN_TAIL.containsMatchIn(afterLabel) ||
        afterLabel.contains("· p.") ||
        afterLabel.contains("· page") ||
        afterLabel.contains("· pages") ||
        afterLabel.contains("· पृष्ठ") ||
        afterLabel.contains("· पृष्ठा") ||
        afterLabel.lines().any { line -> looksLikeContentStamp(line) }
}

/** User-facing page label from [extractPageRange] (`p.17` → localized `page 17`). */
internal fun formatPageRangeForUser(pageRange: String, labels: CitationDisplayLabels): String = when {
    pageRange.startsWith("pp.") -> "${labels.pagesPlural} ${pageRange.removePrefix("pp.")}"
    pageRange.startsWith("p.") -> "${labels.pageSingle} ${pageRange.removePrefix("p.")}"
    else -> pageRange
}

/** Phase C — last-resort location before "unknown" when page/heading absent. */
internal fun formatChunkPartLocation(chunkIndex: Int): String = "part ${chunkIndex + 1}"

internal fun formatCitationLocation(chunk: RetrievedChunk, labels: CitationDisplayLabels): String = when {
    chunk.chunkIndex < 0 -> labels.overview
    else -> {
        val page = extractPageRange(chunk.text)?.let { formatPageRangeForUser(it, labels) }
        val office = if (page == null) extractOfficeStructureMarker(chunk.text) else null
        val section = if (page == null && office == null) extractCitationSectionHeading(chunk.text) else null
        when {
            page != null -> page
            office != null -> office
            section != null -> section
            chunk.chunkIndex >= 0 -> formatChunkPartLocation(chunk.chunkIndex)
            else -> labels.locationUnknown
        }
    }
}

internal fun formatUserCitationLine(
    chunk: RetrievedChunk,
    outlineByDocName: Map<String, String>,
    labels: CitationDisplayLabels,
): String {
    val name = safeCitationDocTitle(
        chunk.docName,
        outlineByDocName[chunk.docName],
        chunk.text,
        chunk.text.length,
        labels,
    )
    val location = formatCitationLocation(chunk, labels)
    return "$name · $location"
}

private fun citationLineTitle(line: String): String = line.substringBefore('·').trim()

private fun citationLineLocation(line: String): String =
    line.substringAfter('·', missingDelimiterValue = "").trim()

/** Tier 3.11 — one unknown-location line per document title in Sources footer. */
internal fun capRedundantUnknownLocationLines(
    lines: List<String>,
    labels: CitationDisplayLabels,
): List<String> {
    if (lines.isEmpty()) return lines
    val unknown = labels.locationUnknown
    val seenUnknownTitles = mutableSetOf<String>()
    return lines.filter { line ->
        val location = citationLineLocation(line)
        if (location != unknown) true
        else {
            val title = citationLineTitle(line)
            if (title in seenUnknownTitles) false
            else {
                seenUnknownTitles += title
                true
            }
        }
    }
}

/**
 * A6 point 2 — when two files share the same short title, prefix "File 1:" / "फ़ाइल 1:" etc.
 */
internal fun applyTitleCollisionDisambiguation(
    entries: List<Pair<RetrievedChunk, String>>,
    labels: CitationDisplayLabels,
    docOrder: List<String>,
): List<String> {
    if (entries.size < 2) return entries.map { it.second }
    val titleGroups = entries.groupBy { (_, line) -> citationLineTitle(line) }
    val collidingTitles = titleGroups.filter { (_, group) ->
        group.size >= 2 && group.map { citationChunkDocKey(it.first) }.distinct().size >= 2
    }.keys
    if (collidingTitles.isEmpty()) return entries.map { it.second }
    val docFileNumber = docOrder.withIndex().associate { it.value to it.index + 1 }
    return entries.map { (chunk, line) ->
        val title = citationLineTitle(line)
        if (title !in collidingTitles) {
            line
        } else {
            val location = citationLineLocation(line)
            val fileNum = docFileNumber[citationChunkDocKey(chunk)] ?: 1
            "${labels.fileDisambigLabel(fileNum)}: $title · $location"
        }
    }
}

internal fun buildDeterministicSourcesFooter(
    chunks: List<RetrievedChunk>,
    outlineByDocName: Map<String, String>,
    labels: CitationDisplayLabels,
    maxSources: Int = DETERMINISTIC_SOURCES_MAX,
    multiFileFairSources: Boolean = false,
    claimPairAnswerBody: String? = null,
    claimPairQuery: String? = null,
): String {
    if (chunks.isEmpty() || maxSources <= 0) return ""
    val ordered = interleaveExcerptsByDoc(chunks)
    val entries = ArrayList<Pair<RetrievedChunk, String>>(maxSources)
    val seenLines = LinkedHashSet<String>()
    val docOrder = ordered.map { citationChunkDocKey(it) }.distinct()
    val requireClaimPairing = !claimPairAnswerBody.isNullOrBlank()

    fun lineFor(chunk: RetrievedChunk): String? {
        val line = formatUserCitationLine(chunk, outlineByDocName, labels)
        val label = citationLineTitle(line)
        if (looksLikeInternalCitationLabel(label)) return null
        return line
    }

    fun tryAdd(chunk: RetrievedChunk): Boolean {
        if (
            requireClaimPairing &&
            !chunkSharesTokensWithAnswer(chunk, claimPairAnswerBody!!, query = claimPairQuery)
        ) {
            return false
        }
        val line = lineFor(chunk) ?: return false
        if (line in seenLines) return false
        seenLines += line
        entries += chunk to line
        return true
    }

    val distinctDocs = docOrder
    val multiFile = multiFileFairSources && distinctDocs.size >= 2

    if (multiFile) {
        for (docKey in distinctDocs) {
            if (entries.size >= maxSources) break
            val reserveChunk = ordered.firstOrNull { citationChunkDocKey(it) == docKey && it.score > 0.0 }
                ?: ordered.firstOrNull { citationChunkDocKey(it) == docKey }
            if (reserveChunk != null) tryAdd(reserveChunk)
        }
    }

    for (chunk in ordered) {
        if (entries.size >= maxSources) break
        tryAdd(chunk)
    }

    val lines = capRedundantUnknownLocationLines(
        applyTitleCollisionDisambiguation(entries, labels, docOrder),
        labels,
    )
    if (lines.isEmpty()) return ""
    return buildString {
        append(labels.sourcesHeader)
        for (line in lines) {
            append('\n')
            append(line)
        }
    }
}

internal fun applyDeterministicSourcesFooter(
    modelText: String,
    chunks: List<RetrievedChunk>,
    outlineByDocName: Map<String, String>,
    labels: CitationDisplayLabels,
    maxSources: Int = DETERMINISTIC_SOURCES_MAX,
    multiFileFairSources: Boolean = false,
    claimOverlapQuery: String? = null,
    claimOverlapTurnMode: RagTurnMode? = null,
): String {
    if (chunks.isEmpty()) return modelText
    val body = stripInlineCitationIndices(stripModelSourcesBlock(modelText, labels))
    if (
        shouldAuditPostGenGroundedness(claimOverlapQuery, claimOverlapTurnMode) &&
        hasAuditableLegalClaims(body)
    ) {
        val audit = auditPostGenGroundedness(body, buildRetrievalCorpus(chunks), claimOverlapQuery)
        if (!audit.isFullyGrounded) {
            logRag(
                "post-gen-groundedness fail amounts=${audit.ungroundedAmounts} " +
                    "sections=${audit.ungroundedSections} shall=${audit.ungroundedShall}",
            )
            val caveat = labels.groundednessCaveat
            return if (body.isBlank()) caveat else "$body\n\n$caveat"
        }
    }
    val claimPairingActive = shouldFilterSourcesByClaimOverlap(claimOverlapQuery, claimOverlapTurnMode)
    val pairingBody = answerBodyForClaimOverlap(body, claimOverlapTurnMode)
    val overlapChunks = if (claimPairingActive) {
        filterChunksByClaimOverlap(
            chunks,
            pairingBody,
            query = claimOverlapQuery,
            turnMode = claimOverlapTurnMode,
        )
    } else {
        chunks
    }
    val overlapDroppedAll = claimPairingActive && overlapChunks.isEmpty() && chunks.isNotEmpty()
    if (overlapDroppedAll) {
        logRag(
            ragCitationOverlapDropLogLine(
                queryLen = claimOverlapQuery?.length ?: 0,
                chunkCount = chunks.size,
            ),
        )
    }
  // Phase A5 — when claim overlap filters every chunk, fall back to retrieval-based Sources.
    val footerChunks = if (overlapDroppedAll) chunks else overlapChunks
    val footer = buildDeterministicSourcesFooter(
        footerChunks,
        outlineByDocName,
        labels,
        maxSources,
        multiFileFairSources,
        claimPairAnswerBody = pairingBody.takeIf { claimPairingActive && !overlapDroppedAll },
        claimPairQuery = claimOverlapQuery,
    )
    if (footer.isEmpty()) return body
    return if (body.isBlank()) footer else "$body\n\n$footer"
}
