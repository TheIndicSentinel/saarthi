package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity

/**
 * Wave 2 — index-time chapter registry + chunk metadata for structure lookups.
 *
 * One canonical chapter list per document (Roman/digit/bilingual IDs at index),
 * persisted as a registry chunk and mirrored on each body row's metadata columns.
 */

internal const val STRUCTURE_REGISTRY_CHUNK_INDEX = -3

internal object ChunkRole {
    const val OUTLINE = "outline"
    const val REGISTRY = "registry"
    /** Phase 2.4 — future prompt/registry hints; never BM25/FTS/citable. */
    const val PROMPT_HINT = "prompt_hint"
    const val TOC = "toc"
    const val HEADING = "heading"
    const val BODY = "body"
    const val TABLE = "table"
}

internal data class ChapterRegistryEntry(
    val chapterNum: Int,
    val romanId: String,
    val title: String,
    val startChunkIndex: Int,
    val source: String = "body",
)

internal data class DocumentChapterRegistry(
    val chapters: List<ChapterRegistryEntry> = emptyList(),
) {
    fun sortedByDocumentOrder(): List<ChapterRegistryEntry> =
        chapters.sortedBy { it.startChunkIndex }
}

private val PAGE_MARKER_RX = Regex("--- Page (\\d+) ---")
private val SECTION_START_RX = Regex("(?im)^\\s*(?:Section|SECTION|धारा)\\s+(\\d{1,3})\\b")
private val TABLE_SIGNAL_RX = Regex(
    "(?im)(^\\s*THE SCHEDULE\\b|monetary\\s+penalty|fee\\s+structure|charges?\\s+table)",
)

internal data class ChunkMetadataAssign(
    val chapterId: String?,
    val sectionNum: String?,
    val headingPath: String?,
    val pageNum: Int?,
    val role: String,
)

internal fun extractPageNum(text: String): Int? =
    PAGE_MARKER_RX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

internal fun extractTitleFromChapterLine(line: String): String {
    val trimmed = line.trim()
    val m = Regex(
        "(?i)(?:CHAPTER|Chapter|अध्याय)\\s+[IVXLCDM\\d]+\\s*(.*)",
    ).find(trimmed)
    val rest = m?.groupValues?.getOrNull(1)?.trim().orEmpty()
    return when {
        rest.isNotEmpty() -> rest.take(120)
        trimmed.length <= 120 -> trimmed
        else -> trimmed.take(120)
    }
}

/** When the chapter line has no inline title, use the next non-empty body line. */
internal fun extractChapterTitle(
    line: String,
    allLines: List<String>,
    lineIndex: Int,
): String {
    val fromLine = extractTitleFromChapterLine(line)
    if (!fromLine.matches(Regex("(?i)^CHAPTER\\s+[IVXLCDM\\d]+$"))) {
        return fromLine
    }
    val next = allLines.getOrNull(lineIndex + 1)?.trim().orEmpty()
    return if (next.length in 3..120) next.take(120) else fromLine
}

/** Body scan — line-start chapter headers; skips TOC-like blocks. */
internal fun buildDocumentChapterRegistry(
    chunkTexts: List<String>,
    outlineText: String? = null,
): DocumentChapterRegistry {
    val entries = mutableListOf<ChapterRegistryEntry>()
    val seenNums = mutableSetOf<Int>()
    for ((chunkIdx, text) in chunkTexts.withIndex()) {
        if (isTocLikeChunkText(text)) continue
        val lines = text.lines()
        for ((lineIdx, raw) in lines.withIndex()) {
            val trimmed = raw.trim()
            val tier = chapterHeaderMatchTier(trimmed) ?: continue
            if (tier > 1) continue
            val num = extractChapterNumberFromLine(trimmed) ?: continue
            if (num in seenNums) continue
            seenNums.add(num)
            entries.add(
                ChapterRegistryEntry(
                    chapterNum = num,
                    romanId = intToRoman(num),
                    title = extractChapterTitle(trimmed, lines, lineIdx),
                    startChunkIndex = chunkIdx,
                    source = "body",
                ),
            )
        }
    }
    if (outlineText != null) {
        for (raw in outlineText.lines()) {
            val line = raw.trim().removePrefix("-").trim()
            if (line.isEmpty()) continue
            val tier = chapterHeaderMatchTier(line)
            val num = extractChapterNumberFromLine(line)
            if (tier != null && tier <= 1 && num != null && num !in seenNums) {
                seenNums.add(num)
                entries.add(
                    ChapterRegistryEntry(
                        chapterNum = num,
                        romanId = intToRoman(num),
                        title = extractTitleFromChapterLine(line),
                        startChunkIndex = -1,
                        source = "outline",
                    ),
                )
            }
        }
    }
    return DocumentChapterRegistry(entries.sortedBy { it.startChunkIndex })
}

internal fun computeChunkMetadata(
    chunkTexts: List<String>,
    registry: DocumentChapterRegistry,
): List<ChunkMetadataAssign> {
    val ordered = registry.sortedByDocumentOrder()
    return chunkTexts.mapIndexed { idx, text ->
        val pageNum = extractPageNum(text)
        val role = when {
            isTocLikeChunkText(text) -> ChunkRole.TOC
            TABLE_SIGNAL_RX.containsMatchIn(text) -> ChunkRole.TABLE
            ordered.any { it.startChunkIndex == idx } -> ChunkRole.HEADING
            else -> ChunkRole.BODY
        }
        val chapter = ordered.lastOrNull { it.startChunkIndex in 0..idx }
        val sectionNum = SECTION_START_RX.find(text)?.groupValues?.getOrNull(1)
        val headingPath = chapter?.let { ch ->
            "CHAPTER ${ch.romanId} ${ch.title}".trim()
        }
        ChunkMetadataAssign(
            chapterId = chapter?.romanId,
            sectionNum = sectionNum,
            headingPath = headingPath,
            pageNum = pageNum,
            role = role,
        )
    }
}

internal fun encodeChapterRegistry(registry: DocumentChapterRegistry): String {
    if (registry.chapters.isEmpty()) return "Document chapter registry (indexed): 0 chapters"
    return buildString {
        append("Document chapter registry (indexed): ")
        append(registry.chapters.size)
        append(" chapters\n")
        registry.sortedByDocumentOrder().forEach { ch ->
            append(ch.romanId)
            append('|')
            append(ch.title.replace('|', ' ').replace('\n', ' '))
            append('|')
            append(ch.chapterNum)
            append('|')
            append(ch.startChunkIndex)
            append('\n')
        }
    }.trimEnd()
}

internal fun parseChapterRegistry(text: String): DocumentChapterRegistry {
    val entries = mutableListOf<ChapterRegistryEntry>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("Document chapter")) continue
        val parts = trimmed.split('|')
        if (parts.size < 4) continue
        val romanId = parts[0].trim()
        val title = parts[1].trim()
        val num = parts[2].trim().toIntOrNull() ?: chapterNumericId(romanId) ?: continue
        val start = parts[3].trim().toIntOrNull() ?: -1
        entries.add(
            ChapterRegistryEntry(
                chapterNum = num,
                romanId = romanId,
                title = title,
                startChunkIndex = start,
                source = "registry",
            ),
        )
    }
    return DocumentChapterRegistry(entries)
}

internal fun chapterRegistriesFromEntities(
    entities: List<RagChunkEntity>,
): Map<String, DocumentChapterRegistry> =
    entities
        .filter { it.chunkIndex == STRUCTURE_REGISTRY_CHUNK_INDEX }
        .associate { it.docUri to parseChapterRegistry(it.text) }

internal fun registryChapterCount(registries: Map<String, DocumentChapterRegistry>): Int =
    registries.values.flatMap { it.chapters }.distinctBy { it.chapterNum to it.title }.size

internal fun registryChaptersInOrder(
    registries: Map<String, DocumentChapterRegistry>,
): List<ChapterRegistryEntry> =
    registries.values.flatMap { it.chapters }.sortedBy { it.startChunkIndex }

internal fun buildRegistryCountHint(
    chapters: List<ChapterRegistryEntry>,
    kind: String = "chapter",
): String {
    val unit = when (kind) {
        "section" -> "sections"
        "part" -> "parts"
        "annex" -> "annexes"
        else -> "chapters"
    }
    val ordered = chapters.sortedBy { it.startChunkIndex }
    val titles = ordered.map { "${it.romanId}: ${it.title}" }
    val preview = titles.take(12).joinToString("; ")
    val ellipsis = if (titles.size > 12) "; …" else ""
    return buildString {
        append("Document $unit registry (indexed): ")
        append(ordered.size)
        append(" $unit — ")
        append(preview)
        append(ellipsis)
        append(". Use this indexed registry count; do not guess from a partial excerpt.")
    }
}

internal fun buildStructureListHint(
    query: String,
    chapterRegistries: Map<String, DocumentChapterRegistry>,
): String? {
    if (!isStructureListQuery(query)) return null
    if (structureMarkerKind(query) != "chapter") return null
    val chapters = registryChaptersInOrder(chapterRegistries)
    if (chapters.isEmpty()) return null
    return buildString {
        append("List these chapter titles from the indexed registry (")
        append(chapters.size)
        append("):\n")
        chapters.forEach { ch ->
            append("- Chapter ")
            append(ch.romanId)
            if (ch.title.isNotEmpty()) {
                append(": ")
                append(ch.title)
            }
            append('\n')
        }
        append("Use exact titles above; do not invent marker soup or partial-scan filler.")
    }
}
