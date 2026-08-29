package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.CitationDisplayLabels
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import java.util.Locale

/**
 * Point 7 — citation contract. Pure helpers so header names, page labels,
 * and prompt rules stay unit-testable without constructing ChatRepositoryImpl.
 */

private val PAGE_MARKER_REGEX = Regex("---\\s*Page\\s+(\\d+)\\s*---", RegexOption.IGNORE_CASE)

/**
 * Human-readable document name for citations and the session manifest.
 *
 * Strips file extensions (including double extensions like ".log.txt"),
 * replaces underscores/hyphens/dashes with spaces, removes a leading
 * year-or-timestamp digit prefix, and caps at 28 chars on a word boundary.
 */
internal fun shortDocName(rawName: String): String {
    val knownExts = listOf(
        ".pdf", ".docx", ".doc", ".txt", ".log",
        ".csv", ".xlsx", ".xls", ".pptx", ".ppt",
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp",
    )
    var stem = rawName
    var stripped = true
    while (stripped) {
        val low = stem.lowercase()
        stripped = false
        for (ext in knownExts) {
            if (low.endsWith(ext)) {
                stem = stem.dropLast(ext.length)
                stripped = true
                break
            }
        }
    }
    val cleaned = stem
        .replace('_', ' ').replace('-', ' ')
        .replace('–', ' ').replace('—', ' ')
        .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
        .replace(Regex("^[\\d ]+(?=\\p{L})"), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
    if (cleaned.isBlank()) return stem.take(28)
    if (cleaned.length <= 28) return cleaned
    val truncated = cleaned.take(28).trimEnd()
    val lastSpace = truncated.lastIndexOf(' ')
    return if (lastSpace > 10) truncated.substring(0, lastSpace) else truncated
}

/** True when the stored filename looks like a content-uri hash, not a human title. */
internal fun looksLikeContentStamp(rawName: String): Boolean {
    val stem = rawName.substringBeforeLast('.').ifBlank { rawName }
    val alnum = stem.replace(Regex("[^0-9A-Za-z]"), "")
    if (alnum.length >= 20 && alnum.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
        return true
    }
    return stem.length >= 28 && stem.none { it.isLetter() || it in '\u0900'..'\u0D7F' }
}

/** Internal outline chunk prefix — never show as a user-facing document title. */
internal fun isOutlineBoilerplateLine(line: String): Boolean {
    val norm = line.trim().lowercase()
    return norm.startsWith("document outline") || norm.contains("auto-detected heading")
}

/** Short ALL-CAPS TOC bullets (PRELIMINARY, SCHEDULE) are not document titles. */
internal fun isOutlineTocBulletLine(line: String): Boolean {
    val t = line.trim().removePrefix("- ").trim()
    if (t.length !in 4..32) return false
    if (chapterHeaderMatchTier(t) != null) return true
    return t == t.uppercase(Locale.ENGLISH) &&
        t.any { it.isUpperCase() } &&
        !STATUTE_TITLE_PHRASE_PATTERN.containsMatchIn(t)
}

/** Labels that must not appear in Sources, manifest, or excerpt headers. */
internal fun looksLikeInternalCitationLabel(label: String): Boolean {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return true
    return isOutlineBoilerplateLine(trimmed) ||
        looksLikeContentStamp(trimmed) ||
        looksLikeBodyProseLine(trimmed) ||
        isChapterOnlyCitationLabel(trimmed) ||
        looksLikeMidSentenceCrossRefCitationLabel(trimmed) ||
        looksLikeTruncatedTitleFragment(trimmed)
}

/** Chapter-only labels are section context, not document titles (Wave 3 P12). */
internal fun isChapterOnlyCitationLabel(label: String): Boolean {
    val t = label.trim()
    if (chapterHeaderMatchTier(t)?.let { it <= 1 } != true) return false
    return t.length <= 48 && !STATUTE_TITLE_PHRASE_PATTERN.containsMatchIn(t)
}

private val STATUTE_TITLE_PHRASE_PATTERN = Regex(
    "(?i)\\b(act|agreement|policy|law|rules|regulation|code|ordinance|notification|amendment)\\b",
)

/** Phase 2.3 — mid-sentence cross-refs are not document titles (Code 2016, sub-sections…). */
internal fun looksLikeMidSentenceCrossRefCitationLabel(label: String): Boolean {
    val t = label.trim()
    if (t.isEmpty()) return true
    val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (t.first().isLowerCase() && (words.size >= 2 || t.length >= 24)) return true
    if (Regex("(?i)\\bsub[- ]?sections?\\s+\\d+").containsMatchIn(t)) return true
    if (Regex("(?i)\\bsections?\\s+\\d+\\s+and\\s+\\d+").containsMatchIn(t)) return true
    if (Regex("(?i)\\bof\\s+this\\s+act\\b").containsMatchIn(t)) return true
    if (Regex("(?i)\\bunder\\s+(?:chapter|section|sec\\.?)\\b").containsMatchIn(t)) return true
    if (
        Regex("(?i)\\b(?:code|act)\\s*,?\\s*\\d{4}\\b").containsMatchIn(t) &&
        !t.startsWith("THE ", ignoreCase = true) &&
        t.length <= 40
    ) {
        return true
    }
    if (Regex("(?i)^[\\p{L}\\p{N}\\s]{0,28}(?:code|act)\\s+\\d{4}$").matches(t)) return true
    return false
}

/** Phase 2.3 — truncated heading fragments must not become citation titles. */
internal fun looksLikeTruncatedTitleFragment(label: String): Boolean {
    val t = label.trim()
    if (t.isEmpty()) return true
    if (t.endsWith('…') || t.endsWith("...")) return true
    if (t.endsWith('-') || t.endsWith('–') || t.endsWith('—')) return true
    if (Regex("(?i)\\s(and|or|of|the|a|an|under|sub)\\s*$").containsMatchIn(t)) return true
    val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size >= 2 && words.last().length <= 2 && !words.last().all { it.isDigit() }) return true
    return false
}

/**
 * Wave 3 P12 — operative clause prose must never become a citation document title.
 */
internal fun looksLikeBodyProseLine(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return true
    if (chapterHeaderMatchTier(t) != null) return false
    if (structureMarkerScore(t, "section") != null) return false
    val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size >= 3 && t.first().isLowerCase()) return true
    if (words.size >= 4 &&
        !t.startsWith("THE ", ignoreCase = true) &&
        Regex("(?i)\\b(the|a|an)\\s+\\p{L}").containsMatchIn(t.take(40))
    ) {
        return true
    }
    if (Regex("(?i)\\b(may|shall|must|provided that|subject to|whereas|hereinafter|after giving)\\b")
        .containsMatchIn(t)
    ) {
        return true
    }
    return false
}

/** Opening page only — mid-document chunks must not drive document title extraction. */
internal fun openingPageContentSample(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val marker = PAGE_MARKER_REGEX.find(text)
    if (marker == null) return text.take(1_500)
    val afterFirst = text.substring(marker.range.last + 1)
    val second = PAGE_MARKER_REGEX.find(afterFirst)
    return if (second != null) {
        text.substring(0, marker.range.first + 1 + second.range.last + 1).take(2_000)
    } else {
        afterFirst.take(1_500)
    }
}

internal const val FALLBACK_ATTACHED_DOC_LABEL = "Attached document"

/** Tier 3.11 — reject OCR garbage and year-like page noise from citation locations. */
internal const val MAX_SANE_PAGE_NUMBER = 3_000

/**
 * B3-1 — commentary vs primary document role for Sources/manifest labeling (B3-2).
 * [PRIMARY] is represented by null (no prefix on citations).
 */
internal enum class DocumentRoleLabel {
    SUMMARY,
    GUIDE,
    SAMPLE,
    CIRCULAR,
}

/** Max extracted chars for a "short" doc — aligns with whole-file RAG path. */
internal const val DOCUMENT_ROLE_SHORT_CHARS = 3_000

/** Long statutes / agreements above this need a strong filename or title phrase. */
internal const val DOCUMENT_ROLE_LONG_DOC_CHARS = 12_000

private val ROLE_FILENAME_TOKENS: Map<DocumentRoleLabel, Set<String>> = mapOf(
    DocumentRoleLabel.SUMMARY to setOf(
        "summary", "summaries", "synopsis", "saaransh", "saransh", "overview",
        "brief", "onepage",
        "सारांश", "संक्षेप", "सारांशम्", "সারাংশ", "సారాంశం", "சுருக்கம்",
    ),
    DocumentRoleLabel.GUIDE to setOf(
        "guide", "handbook", "primer", "companion", "playbook",
        "consulting", "advisory", "ey",
        "मार्गदर्शिका", "गाइड", "গাইড", "గైడ్",
    ),
    DocumentRoleLabel.SAMPLE to setOf(
        "sample", "demo", "specimen", "नमूना", "नमुना",
    ),
    DocumentRoleLabel.CIRCULAR to setOf(
        "circular", "paripatra", "paripatr", "gazette", "परिपत्र", "পরিপত্র", "పరిపత్ర",
    ),
)

private val ROLE_CONTENT_PATTERNS: Map<DocumentRoleLabel, List<Regex>> = mapOf(
    DocumentRoleLabel.SUMMARY to listOf(
        Regex("(?i)plain[- ]language\\s+summary"),
        Regex("(?i)executive\\s+summary"),
        Regex("(?i)(?:this\\s+)?(?:document|note)\\s+(?:is\\s+)?(?:a\\s+)?summary"),
        Regex("(?i)summary\\s+of\\s+the\\s+(?:act|law|agreement|policy)"),
        Regex("सारांश|संक्षेप|सारांशम्"),
    ),
    DocumentRoleLabel.GUIDE to listOf(
        Regex("(?i)(?:practitioner|implementation|compliance)\\s+guide"),
        Regex("(?i)guide\\s+to\\s+the\\s+"),
        Regex("(?i)handbook\\s+for\\s+"),
        Regex("मार्गदर्शिका|गाइड"),
    ),
    DocumentRoleLabel.SAMPLE to listOf(
        Regex("(?i)sample\\s+document"),
        Regex("(?i)demo\\s+document"),
        Regex("(?i)\\(sample\\s+document\\)"),
        Regex("नमूना|नमुना"),
    ),
    DocumentRoleLabel.CIRCULAR to listOf(
        Regex("(?i)office\\s+circular"),
        Regex("(?i)circular\\s+no\\.?\\s*\\d"),
        Regex("कार्यालय\\s+परिपत्र|परिपत्र"),
    ),
)

private fun roleFilenameTokens(rawName: String): Set<String> =
    shortDocName(rawName).lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 }
        .toSet()

private fun scoreRoleFromFilename(rawName: String): Map<DocumentRoleLabel, Int> {
    val tokens = roleFilenameTokens(rawName)
    val stem = shortDocName(rawName).lowercase().replace('_', ' ')
    val base = DocumentRoleLabel.entries.associateWith { role ->
        val hits = ROLE_FILENAME_TOKENS[role].orEmpty().count { hint ->
            tokens.any { t -> t == hint || t.contains(hint) || hint.contains(t) }
        }
        if (hits > 0) 3 + (hits - 1) else 0
    }
    var guideScore = base[DocumentRoleLabel.GUIDE]!!
    if (Regex("(?i)(?:^|\\s)ey(?:\\s|$)").containsMatchIn(stem)) guideScore = maxOf(guideScore, 3)
    if (stem.contains("consulting") || stem.contains("advisory")) guideScore = maxOf(guideScore, 3)
    return base + (DocumentRoleLabel.GUIDE to guideScore)
}

private fun scoreRoleFromContent(contentHint: String?): Map<DocumentRoleLabel, Int> {
    if (contentHint.isNullOrBlank()) {
        return DocumentRoleLabel.entries.associateWith { 0 }
    }
    val sample = PAGE_MARKER_REGEX.replace(contentHint.take(4_000), "")
    return DocumentRoleLabel.entries.associateWith { role ->
        val hits = ROLE_CONTENT_PATTERNS[role].orEmpty().count { it.containsMatchIn(sample) }
        if (hits > 0) 4 + (hits - 1) else 0
    }
}

/**
 * B3-1 — detect summary / guide / sample / circular commentary files so B3-2 can
 * prefix Sources (e.g. "Summary: …") and avoid treating them as primary law.
 *
 * Uses filename tokens, opening-body phrases, and length — not one specific PDF.
 * Returns null when the file looks like a primary full document.
 */
internal fun documentRoleLabel(
    rawName: String,
    contentHint: String? = null,
    contentCharCount: Int? = null,
): DocumentRoleLabel? {
    val fromName = scoreRoleFromFilename(rawName)
    val fromContent = scoreRoleFromContent(contentHint)
    val longDoc = contentCharCount != null && contentCharCount > DOCUMENT_ROLE_LONG_DOC_CHARS
    val shortDoc = contentCharCount != null && contentCharCount <= DOCUMENT_ROLE_SHORT_CHARS

    val combined = DocumentRoleLabel.entries.associateWith { role ->
        var score = fromName[role]!! + fromContent[role]!!
        if (longDoc && fromName[role]!! == 0) {
            // Body phrase alone on a long file is often a clause ("access a summary…"), not doc type.
            score = fromContent[role]!! / 2
        }
        if (shortDoc && score > 0) score += 1
        score
    }

    val best = combined.maxByOrNull { it.value }
    if (best == null || best.value < 3) return null

    // Sample wins when tied — demo attachments must not read as statute.
    val topScore = best.value
    val winners = combined.filter { it.value == topScore }.keys
    return when {
        DocumentRoleLabel.SAMPLE in winners -> DocumentRoleLabel.SAMPLE
        DocumentRoleLabel.SUMMARY in winners -> DocumentRoleLabel.SUMMARY
        DocumentRoleLabel.GUIDE in winners -> DocumentRoleLabel.GUIDE
        DocumentRoleLabel.CIRCULAR in winners -> DocumentRoleLabel.CIRCULAR
        else -> winners.firstOrNull()
    }
}

private val DOCUMENT_TITLE_THE_LINE = Regex(
    """^THE\s+(.{10,120})$""",
    setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
)

/** Title-case ALL-CAPS act/circular headings for readable citations. */
internal fun normalizeDisplayTitle(title: String): String {
    if (title.none { it.isLowerCase() } && title.any { it.isUpperCase() }) {
        return title.lowercase(Locale.ENGLISH).split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ENGLISH) else ch.toString()
                }
            }
    }
    return title
}

/**
 * Scans only the opening — cheap and safe at index + citation time.
 */
internal fun extractDocumentTitle(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val sample = PAGE_MARKER_REGEX.replace(text.take(5000), "")
    for (raw in sample.lineSequence()) {
        val line = raw.trim()
        if (line.length < 10) continue
        if (line.startsWith("-")) continue
        if (isOutlineBoilerplateLine(line)) continue
        if (looksLikeBodyProseLine(line)) continue
        if (looksLikeMidSentenceCrossRefCitationLabel(line)) continue
        if (looksLikeTruncatedTitleFragment(line)) continue
        if (chapterHeaderMatchTier(line) != null) continue
        if (line.startsWith("THE ", ignoreCase = true)) {
            return line.removePrefix("THE ").removePrefix("The ").trim()
        }
    }
    DOCUMENT_TITLE_THE_LINE.find(sample)?.let { match ->
        val line = match.value.trim()
        if (
            !looksLikeBodyProseLine(line) &&
            !looksLikeMidSentenceCrossRefCitationLabel(line) &&
            !looksLikeTruncatedTitleFragment(line)
        ) {
            return line.removePrefix("THE ").removePrefix("The ").trim()
        }
    }
    for (raw in sample.lineSequence()) {
        val line = raw.trim()
        if (!isLikelyStatuteTitleLine(line)) continue
        return line.removePrefix("THE ").removePrefix("The ").trim()
    }
    return null
}

internal fun isLikelyStatuteTitleLine(line: String): Boolean {
    if (line.length !in 10..120) return false
    if (line.startsWith("-")) return false
    if (!line.any { it.isLetter() }) return false
    if (isOutlineBoilerplateLine(line)) return false
    if (looksLikeBodyProseLine(line)) return false
    if (looksLikeMidSentenceCrossRefCitationLabel(line)) return false
    if (looksLikeTruncatedTitleFragment(line)) return false
    if (chapterHeaderMatchTier(line) != null) return false
    if (line == line.uppercase(Locale.ENGLISH) && line.any { it.isUpperCase() }) return true
    if (STATUTE_TITLE_PHRASE_PATTERN.containsMatchIn(line)) return true
    return false
}

/** First real heading line from an outline chunk (skips internal boilerplate). */
internal fun outlineHeadingFromText(outlineText: String?): String? {
    if (outlineText.isNullOrBlank()) return null
    return outlineText.lineSequence()
        .map { it.trim().removePrefix("- ").trim() }
        .firstOrNull { line ->
            line.isNotBlank() &&
                !PAGE_MARKER_REGEX.containsMatchIn(line) &&
                !isOutlineBoilerplateLine(line) &&
                chapterHeaderMatchTier(line) == null &&
                !looksLikeBodyProseLine(line) &&
                !looksLikeMidSentenceCrossRefCitationLabel(line) &&
                !looksLikeTruncatedTitleFragment(line) &&
                !isOutlineTocBulletLine(line)
        }
        ?.removePrefix("THE ")
        ?.removePrefix("The ")
        ?.trim()
}

/**
 * User-visible document title for citations, manifest, and attach notices.
 * Hash filenames → title from body, outline heading, or a safe fallback.
 */
internal fun displayDocName(
    rawName: String,
    outlineText: String? = null,
    contentHint: String? = null,
): String {
    if (!looksLikeContentStamp(rawName)) return shortDocName(rawName)
    val fromOutlineTitle = extractDocumentTitle(outlineText)
    if (fromOutlineTitle != null) return shortDocName(normalizeDisplayTitle(fromOutlineTitle))
    val fromOpening = extractDocumentTitle(openingPageContentSample(contentHint))
    if (fromOpening != null) return shortDocName(normalizeDisplayTitle(fromOpening))
    val fromOutlineHeading = outlineHeadingFromText(outlineText)
    if (fromOutlineHeading != null) return shortDocName(normalizeDisplayTitle(fromOutlineHeading))
    return FALLBACK_ATTACHED_DOC_LABEL
}

/** B3-2 — localized role prefix for commentary documents (Summary:, Guide:, …). */
internal fun CitationDisplayLabels.rolePrefixFor(role: DocumentRoleLabel): String = when (role) {
    DocumentRoleLabel.SUMMARY -> summaryRolePrefix
    DocumentRoleLabel.GUIDE -> guideRolePrefix
    DocumentRoleLabel.SAMPLE -> sampleRolePrefix
    DocumentRoleLabel.CIRCULAR -> circularRolePrefix
}

/**
 * B3-2 — user-visible citation title with optional role prefix from [documentRoleLabel].
 * Primary documents keep the plain [displayDocName] title.
 */
internal fun displayCitationDocName(
    rawName: String,
    outlineText: String? = null,
    contentHint: String? = null,
    contentCharCount: Int? = null,
    labels: CitationDisplayLabels? = null,
): String {
    val titleSource = outlineText ?: openingPageContentSample(contentHint)
    val base = displayDocName(rawName, outlineText, titleSource)
    if (labels == null) return base
    val role = documentRoleLabel(rawName, contentHint, contentCharCount)
    if (role == null) return base
    return "${labels.rolePrefixFor(role)} $base"
}

/**
 * Tier 3.10 — never surface mid-sentence chunk text as a citation document title.
 */
internal fun safeCitationDocTitle(
    rawName: String,
    outlineText: String? = null,
    contentHint: String? = null,
    contentCharCount: Int? = null,
    labels: CitationDisplayLabels? = null,
): String {
    val title = displayCitationDocName(rawName, outlineText, contentHint, contentCharCount, labels)
    val proseCheck = title.substringAfter(": ", missingDelimiterValue = title).trim()
    if (!looksLikeInternalCitationLabel(proseCheck) && !looksLikeBodyProseLine(proseCheck)) {
        return title
    }
    val stem = shortDocName(rawName)
    val fallback = if (looksLikeContentStamp(stem)) FALLBACK_ATTACHED_DOC_LABEL else stem
    if (labels == null) return fallback
    val role = documentRoleLabel(rawName, contentHint, contentCharCount)
    return if (role != null) "${labels.rolePrefixFor(role)} $fallback" else fallback
}

internal fun extractPageRange(text: String): String? {
    val pages = PAGE_MARKER_REGEX.findAll(text)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .filter { page -> page in 1..MAX_SANE_PAGE_NUMBER }
        .toList()
    if (pages.isEmpty()) return null
    val lo = pages.min()
    val hi = pages.max()
    return if (lo == hi) "p.$lo" else "pp.$lo-$hi"
}

/**
 * Wave 3 P12 — section/chapter heading for citation location when page markers are absent.
 */
internal fun extractCitationSectionHeading(text: String): String? {
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (PAGE_MARKER_REGEX.containsMatchIn(line)) continue
        if (looksLikeMidSentenceCrossRefCitationLabel(line)) continue
        if (chapterHeaderMatchTier(line)?.let { it <= 1 } == true) {
            return normalizeCitationSectionHeading(line)
        }
        if (structureMarkerScore(line, "section")?.let { it <= 1 } == true) {
            return normalizeCitationSectionHeading(line)
        }
        if (Regex("(?i)^THE\\s+SCHEDULE\\b").containsMatchIn(line)) {
            return normalizeCitationSectionHeading(line)
        }
        if (Regex("(?i)^SCHEDULE\\s+[IVXLC\\d]*\\b").containsMatchIn(line) && line.length <= 80) {
            return normalizeCitationSectionHeading(line)
        }
    }
    return null
}

internal fun normalizeCitationSectionHeading(line: String): String {
    val trimmed = line.trim().removePrefix("- ").trim()
    val chapter = Regex("(?i)^(CHAPTER|Chapter)\\s+([IVXLC]+|\\d+)(?:\\s*[-–:]*\\s*(.*))?$")
        .matchEntire(trimmed)
    if (chapter != null) {
        val label = chapter.groupValues[1].lowercase(Locale.ENGLISH)
            .replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        val id = chapter.groupValues[2]
        val romanId = if (id.all { it.isDigit() || it in "IVXLC" }) {
            id.uppercase(Locale.ENGLISH)
        } else {
            id
        }
        val title = chapter.groupValues[3].trim()
        return if (title.isNotEmpty()) {
            "$label $romanId — ${normalizeDisplayTitle(title)}"
        } else {
            "$label $romanId"
        }
    }
    val section = Regex("(?i)^(Section|SECTION)\\s+(\\d+)(?:\\s*[-–:]*\\s*(.*))?$")
        .matchEntire(trimmed)
    if (section != null) {
        val label = section.groupValues[1].lowercase(Locale.ENGLISH)
            .replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        val num = section.groupValues[2]
        val title = section.groupValues[3].trim()
        return if (title.isNotEmpty()) {
            "$label $num — ${normalizeDisplayTitle(title)}"
        } else {
            "$label $num"
        }
    }
    if (Regex("(?i)^THE\\s+SCHEDULE\\b").containsMatchIn(trimmed)) {
        return normalizeDisplayTitle(trimmed)
    }
    val normalized = normalizeDisplayTitle(trimmed)
    return if (normalized.length > 56) normalized.take(56).trimEnd() + "…" else normalized
}

/**
 * Excerpt header the model is told to mirror in inline cites.
 * Page is omitted when the chunk has no page markers, and never
 * invented for outline rows (chunkIndex < 0).
 */
internal fun formatExcerptHeader(
    index1Based: Int,
    docName: String,
    text: String,
    chunkIndex: Int,
    outlineText: String? = null,
    labels: CitationDisplayLabels? = null,
): String {
    val name = safeCitationDocTitle(
        docName,
        outlineText,
        text,
        text.length,
        labels,
    )
    val locationRef = when {
        chunkIndex < 0 -> ""
        else -> {
            val page = extractPageRange(text)
            val section = if (page == null) extractCitationSectionHeading(text) else null
            when {
                page != null -> " · $page"
                section != null -> " · $section"
                else -> ""
            }
        }
    }
    return "[$index1Based] $name$locationRef\n"
}

internal fun sessionManifestLine(docNames: List<String>): String {
    if (docNames.isEmpty()) return ""
    return "Documents in this chat: " +
        docNames.joinToString("; ") { it } +
        "\n\n"
}

internal const val UNREADABLE_FILES_INTRO =
    "Files attached this turn that could NOT be read (do not cite them; do not pretend to know their contents):"

/**
 * Minimum BM25 body score at which the retrieved excerpts are treated as a
 * confident match for the user's question. At/above this the citation rules
 * drop the "if this isn't about the document, ignore the excerpts" escape
 * hatch (G2) — that clause was making the model deflect ("please ask your
 * question") on questions whose top chunk scored 6–8. Structural padding is
 * 0.0 and weak single-term hits sit ~1–2, so 3.0 cleanly separates a real
 * lexical match from noise while staying below the 6–8 range seen for
 * genuine hits in production.
 */
internal const val STRONG_RAG_MATCH_SCORE = 3.0

/**
 * Per-turn answer-shape guardrails (P0). Injected into the RAG block after
 * citation rules so the model matches length to query type.
 */
internal fun ragAnswerShapeInstruction(
    shape: RagAnswerShape,
    compact: Boolean = false,
    tabularAmount: Boolean = false,
    unattachedExternal: UnattachedExternalDecision = UnattachedExternalDecision(active = false),
): String {
    val externalBlock = if (unattachedExternal.active) {
        val names = unattachedExternal.regimes.joinToString(", ")
        if (compact) {
            "Attached files do not include $names — answer only from excerpts; do not compare to $names.\n\n"
        } else {
            "BOUNDARY: Attached excerpts do not include $names. " +
                "Answer ONLY what the attached document(s) say. " +
                "Do NOT summarise or compare to $names.\n\n"
        }
    } else {
        ""
    }
    val tabularListNote = if (tabularAmount && shape == RagAnswerShape.LIST) {
        if (compact) {
            "List each fee/penalty row with amount exactly as in excerpts.\n\n"
        } else {
            "TABULAR: List each penalty/fee/charge row with its amount (₹, %, etc.) exactly as shown in excerpts.\n\n"
        }
    } else {
        ""
    }
    if (compact) {
        return externalBlock + tabularListNote + when (shape) {
            RagAnswerShape.OVERVIEW_SHORT -> "Short overview: 3–4 sentences max.\n\n"
            RagAnswerShape.OVERVIEW -> "Overview: up to 5 bullets max.\n\n"
            RagAnswerShape.NARROW_QA -> "Answer only this question; 1–3 sentences first.\n\n"
            RagAnswerShape.LIST -> "Concise list: up to 6 bullets.\n\n"
        }
    }
    return externalBlock + tabularListNote + when (shape) {
    RagAnswerShape.OVERVIEW_SHORT ->
        "ANSWER SHAPE: Short overview only — 3–4 sentences OR up to 4 bullet points total. " +
            "Name the document once, state its purpose, and 2–3 main themes. " +
            "Do not list every chapter or section.\n\n"
    RagAnswerShape.OVERVIEW ->
        "ANSWER SHAPE: Overview — up to 6 bullet points OR one short paragraph plus up to 3 bullets. " +
            "Cover purpose and main parts; skip minor clauses and schedules unless central.\n\n"
    RagAnswerShape.NARROW_QA ->
        "ANSWER SHAPE: Answer ONLY this specific question. Lead with 1–3 sentences with the direct answer. " +
            "Use bullets only for 3+ distinct items the user asked for (max 5 bullets). " +
            "Do not recap earlier questions or re-summarise the whole document.\n\n"
    RagAnswerShape.LIST ->
        "ANSWER SHAPE: Give a concise bullet list (max 8 one-line items). " +
            "Cite only for key numeric or legal claims.\n\n"
    }
}

/**
 * Citation rules for the RAG prompt. COMPACT stays one paragraph so the
 * 1B budget is not crowded; STANDARD/LARGE get bullets plus a worked example.
 *
 * [strongMatch] toggles the relevance gate:
 *  • true  — the retrieved excerpts are a confident match, so the rules tell
 *            the model to answer from them and OMIT the "ignore the excerpts"
 *            escape hatch that caused false refusals (G2). Only a pure
 *            greeting / small-talk carve-out remains.
 *  • false — the excerpts may not be relevant, so the rules explicitly allow
 *            an "In general:" general-knowledge answer AND forbid refusing or
 *            asking the user to rephrase (G5).
 */
/** Ultra-compact rules for grounded delivery when the char budget is tight (Wave 1). */
internal fun ragCitationRulesMinimal(
    labels: CitationDisplayLabels = SupportedLanguage.ENGLISH.citationDisplayLabels(),
): String =
    "Answer from the excerpt(s) below only. Put refs in a final '${labels.sourcesHeader}' block (max 3 lines).\n\n"

/** Wave 3 — mixed turn: label document-grounded vs general-knowledge slices in the reply. */
internal fun ragMixedModeRules(
    compact: Boolean,
    labels: CitationDisplayLabels = SupportedLanguage.ENGLISH.citationDisplayLabels(),
): String {
    val sourcesLabel = labels.sourcesHeader
    if (compact) {
        return "This question mixes the attached document and general knowledge. " +
            "Start with 'From document:' for excerpt-based facts only; then 'General:' for outside knowledge. " +
            "Put document refs only in a final '$sourcesLabel' block (max 3 lines). " +
            "Do not cite the document for the General section.\n\n"
    }
    return "MIXED QUESTION — answer in two labelled sections.\n" +
        "• Start with 'From document:' — facts from excerpts only; lead with 1–3 sentences.\n" +
        "• Then 'General:' — general knowledge for the rest; do not pretend it is from the file.\n" +
        "• Put document refs only in a final '$sourcesLabel' block (max 3 lines); never cite excerpts for the General section.\n" +
        "• Do not repeat these instructions.\n\n"
}

internal fun ragCitationRules(
    compact: Boolean,
    strongMatch: Boolean = false,
    labels: CitationDisplayLabels = SupportedLanguage.ENGLISH.citationDisplayLabels(),
    blockExternalRegimes: Boolean = false,
): String {
    val sourcesLabel = labels.sourcesHeader
    val sourcesExample = labels.citationRulesFooterExample()
    val excerptCompact = labels.excerptOnlyRuleCompact
    val excerptBullet = "• ${labels.excerptOnlyRule}\n"
    val externalBlock = if (blockExternalRegimes) {
        if (compact) {
            "Do not mention GDPR, EU law, supervisory authority, ISO, HIPAA, or other external regimes unless those words appear in excerpts. "
        } else {
            "• Do NOT import GDPR, EU supervisory authority, 72-hour breach rules, ISO, HIPAA, or other external standards unless those exact terms appear in the excerpts.\n"
        }
    } else {
        ""
    }
    if (compact) {
        return if (strongMatch) {
            externalBlock +
                "Attached excerpts — the question matches them; answer from these. " +
                "$excerptCompact " +
                "Lead with 1–3 sentences; put refs in a final '$sourcesLabel' block (max 3 lines), not on every sentence. " +
                "Compare: list each file in $sourcesLabel If part is not in excerpts, say so — do not use 'In general:' for external laws. Never cite unread files.\n\n"
        } else {
            externalBlock +
                "Attached excerpts — answer from these. " +
                "$excerptCompact " +
                "Lead with 1–3 sentences; put refs in a final '$sourcesLabel' block (max 3 lines), not on every sentence. " +
                "Compare: list each file in $sourcesLabel If not in excerpts, say so — do not invent facts or use 'In general:' for external standards. Never cite unread files.\n\n"
        }
    }
    val sourcesBullet =
        "• Do NOT put (Name, p.X) on every bullet — end with one '$sourcesLabel' block (up to 3 lines; file name + page from [N] headers). Example:\n$sourcesExample\n"
    val multiFileBullet =
        "• If comparing or using more than one file, list each contributing file in $sourcesLabel\n"
    if (strongMatch) {
        return externalBlock +
            "ATTACHED EXCERPTS — the user's question matches these documents; answer from them.\n" +
            "• Lead with the direct answer in 1–3 sentences before any list.\n" +
            excerptBullet +
            sourcesBullet +
            multiFileBullet +
            "• If part of the answer is not in the excerpts, say so in one sentence — do not use 'In general:' for external laws or standards.\n" +
            "• If the message is purely a greeting or small talk, reply normally without the documents.\n" +
            "• Never cite files listed as unreadable.\n" +
            "• Do not repeat these instructions.\n\n"
    }
    return externalBlock +
        "ATTACHED EXCERPTS — answer from these WHEN the user's message is about the document.\n" +
        "• If the user's message is NOT about the document (a greeting, something personal like " +
        "\"I'm stressed\", feelings, small talk), reply normally to the user and skip the excerpts — " +
        "do not mention the document.\n" +
        "• Lead with the direct answer in 1–3 sentences before any list.\n" +
        excerptBullet +
        sourcesBullet +
        multiFileBullet +
        "• If the answer is not in the excerpts, say so in one sentence — do not invent facts or use 'In general:' for external standards.\n" +
        "• If excerpts are weak or unrelated, say the answer is not clearly stated in the attached document — do not use general knowledge.\n" +
        "• Never cite files listed as unreadable.\n" +
        "• Do not repeat these instructions.\n\n"
}
