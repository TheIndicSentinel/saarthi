package com.saarthi.feature.assistant.data

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

/** Labels that must not appear in Sources, manifest, or excerpt headers. */
internal fun looksLikeInternalCitationLabel(label: String): Boolean {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return true
    return isOutlineBoilerplateLine(trimmed) ||
        looksLikeContentStamp(trimmed)
}

internal const val FALLBACK_ATTACHED_DOC_LABEL = "Attached document"

private val DOCUMENT_TITLE_THE_LINE = Regex(
    """^THE\s+(.{10,120})$""",
    setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
)

/**
 * Pull a human title from document body (acts, circulars, notices).
 * Scans only the opening — cheap and safe at index + citation time.
 */
internal fun extractDocumentTitle(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val sample = PAGE_MARKER_REGEX.replace(text.take(5000), "")
    for (raw in sample.lineSequence()) {
        val line = raw.trim()
        if (line.length < 10) continue
        if (isOutlineBoilerplateLine(line)) continue
        if (line.startsWith("THE ", ignoreCase = true)) {
            return line.removePrefix("THE ").removePrefix("The ").trim()
        }
    }
    DOCUMENT_TITLE_THE_LINE.find(sample)?.let { match ->
        return match.value.trim()
            .removePrefix("THE ")
            .removePrefix("The ")
            .trim()
    }
    for (raw in sample.lineSequence()) {
        val line = raw.trim()
        if (line.length !in 10..80) continue
        if (!line.any { it.isLetter() }) continue
        if (isOutlineBoilerplateLine(line)) continue
        if (line == line.uppercase(Locale.ENGLISH) && line.any { it.isUpperCase() }) {
            return line
        }
    }
    // Plain title line (e.g. outline chunk prefixed with act name at index time).
    val firstContent = sample.lineSequence()
        .map { it.trim() }
        .firstOrNull { line ->
            line.length in 12..120 &&
                line.any { it.isLetter() } &&
                !isOutlineBoilerplateLine(line) &&
                !line.startsWith("-")
        }
    return firstContent?.removePrefix("THE ").removePrefix("The ").trim()
}

/** First real heading line from an outline chunk (skips internal boilerplate). */
internal fun outlineHeadingFromText(outlineText: String?): String? {
    if (outlineText.isNullOrBlank()) return null
    return outlineText.lineSequence()
        .map { it.trim().removePrefix("- ").trim() }
        .firstOrNull { line ->
            line.isNotBlank() &&
                !PAGE_MARKER_REGEX.containsMatchIn(line) &&
                !isOutlineBoilerplateLine(line)
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
    val fromTitle = extractDocumentTitle(contentHint) ?: extractDocumentTitle(outlineText)
    if (fromTitle != null) return shortDocName(fromTitle)
    val fromOutline = outlineHeadingFromText(outlineText)
    if (fromOutline != null) return shortDocName(fromOutline)
    val stem = shortDocName(rawName)
    return if (looksLikeInternalCitationLabel(stem)) FALLBACK_ATTACHED_DOC_LABEL else stem
}

internal fun extractPageRange(text: String): String? {
    val pages = PAGE_MARKER_REGEX.findAll(text)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .toList()
    if (pages.isEmpty()) return null
    val lo = pages.min()
    val hi = pages.max()
    return if (lo == hi) "p.$lo" else "pp.$lo-$hi"
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
): String {
    val name = displayDocName(docName, outlineText, text)
    val page = if (chunkIndex < 0) null else extractPageRange(text)
    val pageRef = page?.let { " · $it" } ?: ""
    return "[$index1Based] $name$pageRef\n"
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
internal fun ragAnswerShapeInstruction(shape: RagAnswerShape, compact: Boolean = false): String {
    if (compact) {
        return when (shape) {
            RagAnswerShape.OVERVIEW_SHORT -> "Short overview: 3–4 sentences max.\n\n"
            RagAnswerShape.OVERVIEW -> "Overview: up to 5 bullets max.\n\n"
            RagAnswerShape.NARROW_QA -> "Answer only this question; 1–3 sentences first.\n\n"
            RagAnswerShape.LIST -> "Concise list: up to 6 bullets.\n\n"
        }
    }
    return when (shape) {
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
internal fun ragCitationRules(compact: Boolean, strongMatch: Boolean = false): String {
    if (compact) {
        return if (strongMatch) {
            "Attached excerpts — the question matches them; answer from these. " +
                "Lead with 1–3 sentences; put (Name, p.X) refs in a final 'Sources:' line (max 3), not on every sentence. " +
                "Compare: list each file in Sources. If part is not in excerpts, say so then 'In general:' with no citation. Never cite unread files.\n\n"
        } else {
            "Attached excerpts — answer from these. " +
                "Lead with 1–3 sentences; put (Name, p.X) refs in a final 'Sources:' line (max 3), not on every sentence. " +
                "Compare: list each file in Sources. If not in excerpts, say so then 'In general:' with no citation — don't refuse or ask the user to rephrase. Never cite unread files.\n\n"
        }
    }
    if (strongMatch) {
        return "ATTACHED EXCERPTS — the user's question matches these documents; answer from them.\n" +
            "• Lead with the direct answer in 1–3 sentences before any list.\n" +
            "• Do NOT put (Name, p.X) on every bullet — end with one 'Sources:' line listing up to 3 document+page refs you used; use names from the [N] headers below.\n" +
            "• If comparing or using more than one file, list each contributing file in Sources.\n" +
            "• If part of the answer is not in the excerpts, say so in one sentence, then add a brief " +
            "general answer prefixed 'In general:' with no (Name, p.X) citation.\n" +
            "• If the message is purely a greeting or small talk, reply normally without the documents.\n" +
            "• Never cite files listed as unreadable.\n" +
            "• Do not invent facts from the excerpts or repeat these instructions.\n\n"
    }
    return "ATTACHED EXCERPTS — answer from these WHEN the user's message is about the document.\n" +
        "• If the user's message is NOT about the document (a greeting, something personal like " +
        "\"I'm stressed\", feelings, small talk), reply normally to the user and skip the excerpts — " +
        "do not mention the document.\n" +
        "• Lead with the direct answer in 1–3 sentences before any list.\n" +
        "• Do NOT put (Name, p.X) on every bullet — end with one 'Sources:' line listing up to 3 document+page refs; use names from the [N] headers.\n" +
        "• If comparing or using more than one file, list each contributing file in Sources.\n" +
        "• If the answer is not in the excerpts, say so in one sentence, then give a brief " +
        "general answer prefixed 'In general:' with no (Name, p.X) citation. Do NOT refuse or " +
        "ask the user to rephrase.\n" +
        "• Never cite files listed as unreadable.\n" +
        "• Do not invent facts from the excerpts or repeat these instructions.\n\n"
}
