package com.saarthi.feature.assistant.data

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
): String {
    val name = shortDocName(docName)
    val page = if (chunkIndex < 0) null else extractPageRange(text)
    val pageRef = page?.let { " · $it" } ?: ""
    return "[$index1Based] $name$pageRef\n"
}

internal fun sessionManifestLine(docNames: List<String>): String {
    if (docNames.isEmpty()) return ""
    return "Documents in this chat: " +
        docNames.joinToString("; ") { shortDocName(it) } +
        "\n\n"
}

internal const val UNREADABLE_FILES_INTRO =
    "Files attached this turn that could NOT be read (do not cite them; do not pretend to know their contents):"

/**
 * Citation rules for the RAG prompt. COMPACT stays one paragraph so the
 * 1B budget is not crowded; STANDARD/LARGE get bullets plus a worked example.
 */
internal fun ragCitationRules(compact: Boolean): String {
    if (compact) {
        return "Attached excerpts — answer from these. After each claim write (Doc Name, p.X) matching the [N] header (page only if shown). " +
            "Compare: cite each file used. If not in excerpts, say so then 'In general:' with no citation. Never cite unread files.\n\n"
    }
    return "ATTACHED EXCERPTS — answer from these WHEN the user's message is about the document.\n" +
        "• If the user's message is NOT about the document (a greeting, something personal like " +
        "\"I'm stressed\", feelings, small talk), IGNORE these excerpts entirely and reply " +
        "normally to the user — do not mention the document at all.\n" +
        "• Cite each claim as (Name, p.X) using the document name from the [N] header below; " +
        "include the page only when shown. Example: 'Consent is required (DPDP Act, p.3).'\n" +
        "• If comparing or using more than one file, cite each file that contributed.\n" +
        "• If the answer is not in the excerpts, say so in one sentence, then give a brief " +
        "general answer prefixed 'In general:' with no (Name, p.X) citation.\n" +
        "• Never cite files listed as unreadable.\n" +
        "• Do not invent facts from the excerpts or repeat these instructions.\n\n"
}
