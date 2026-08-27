package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity

/**
 * Wave 1 P4 — TOC vs body for substance retrieval.
 *
 * Outline / table-of-contents chunks list many chapter titles in one block.
 * Substance queries (special provisions, penalties, chapter highlights) must
 * anchor on the **body** chapter header, not the first TOC mention.
 * Structure count/list queries still use TOC density (see [usesTocForRetrieval]).
 */

internal const val TOC_MIN_DISTINCT_CHAPTER_MARKERS = 3

private val TOC_CHAPTER_LINE_RX = Regex(
    "(?im)^\\s*(?:CHAPTER|Chapter|अध्याय)\\s+([IVXLC\\d]+)",
)
private val TOC_INLINE_CHAPTER_RX = Regex("(?i)\\bCHAPTER\\s+([IVXLC\\d]+)\\b")

/** Structure count/list/overview-of-structure — TOC and outline are the right source. */
internal fun usesTocForRetrieval(query: String): Boolean =
    isStructureCountQuery(query) || isStructureListQuery(query)

internal fun distinctChapterMarkersInText(text: String): List<String> {
    val found = linkedSetOf<String>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        TOC_CHAPTER_LINE_RX.find(trimmed)?.let { found.add(it.groupValues[1].uppercase()) }
        for (inline in TOC_INLINE_CHAPTER_RX.findAll(trimmed)) {
            found.add(inline.groupValues[1].uppercase())
        }
    }
    return found.toList()
}

/**
 * Chunk is TOC-like: outline row (index &lt; 0) or many chapter markers with
 * little substantive prose (e.g. "CHAPTER I … CHAPTER IV SPECIAL PROVISIONS" in one block).
 */
internal fun isTocLikeChunkText(text: String): Boolean {
    val markers = distinctChapterMarkersInText(text)
    if (markers.size >= TOC_MIN_DISTINCT_CHAPTER_MARKERS) return true
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size < 2) return false
    val markerLines = lines.count { line ->
        TOC_CHAPTER_LINE_RX.containsMatchIn(line) ||
            (line.length in 3..80 && line.startsWith("-") && line.length <= 80)
    }
    return markerLines >= TOC_MIN_DISTINCT_CHAPTER_MARKERS &&
        markerLines.toDouble() / lines.size >= 0.45
}

internal fun isTocLikeChunk(chunk: RagChunkEntity): Boolean =
    chunk.chunkIndex < 0 || isTocLikeChunkText(chunk.text)

/**
 * Tier for [heading] in [text]; lower = stronger body header (line-equals beats cross-ref).
 * Null when no match.
 */
internal fun headingMatchTierInText(text: String, heading: String): Int? {
    val needle = heading.trim()
    if (needle.isEmpty()) return null
    var best: Int? = null
    for (raw in text.lines()) {
        val line = raw.trim()
        when {
            line.equals(needle, ignoreCase = true) -> best = minOf(best ?: 0, 0)
            line.startsWith(needle, ignoreCase = true) -> best = minOf(best ?: 1, 1)
            Regex("(?i)^\\s*(?:CHAPTER|Chapter)\\s+").containsMatchIn(line) &&
                headingTokens(line).containsAll(
                    headingTokens(needle).filter { it.length >= 4 },
                ) -> best = minOf(best ?: 2, 2)
        }
    }
    if (text.contains(needle, ignoreCase = true)) {
        best = minOf(best ?: 3, 3)
    }
    val sigTokens = headingTokens(needle).filter { it.length >= 4 }
    if (sigTokens.isNotEmpty() && sigTokens.all { it in headingTokens(text) }) {
        best = minOf(best ?: 4, 4)
    }
    return best
}

/** Best body chunk for [heading]; skips TOC unless [substanceOnly] is false. */
internal fun locateHeadingInBodyChunks(
    sorted: List<RagChunkEntity>,
    heading: String,
    substanceOnly: Boolean = true,
): Int {
    var bestIdx = -1
    var bestTier = Int.MAX_VALUE
    sorted.forEachIndexed { idx, chunk ->
        if (substanceOnly && isTocLikeChunk(chunk)) return@forEachIndexed
        val tier = headingMatchTierInText(chunk.text, heading) ?: return@forEachIndexed
        if (tier < bestTier) {
            bestTier = tier
            bestIdx = idx
        } else if (tier == bestTier && (bestIdx < 0 || idx < bestIdx)) {
            bestIdx = idx
        }
    }
    return bestIdx
}

internal fun locateSectionInBodyChunks(
    sorted: List<RagChunkEntity>,
    ref: SectionRef,
    substanceOnly: Boolean = true,
): Int {
    if (sorted.isEmpty()) return -1
    val texts = sorted.map { it.text }
    when (ref.kind) {
        "section" -> {
            val num = ref.token
            var bestIdx = -1
            var bestTier = Int.MAX_VALUE
            sorted.forEachIndexed { idx, chunk ->
                if (substanceOnly && isTocLikeChunk(chunk)) return@forEachIndexed
                val tier = sectionHeaderMatchTier(chunk.text, num) ?: return@forEachIndexed
                if (tier < bestTier) {
                    bestTier = tier
                    bestIdx = idx
                } else if (tier == bestTier && (bestIdx < 0 || idx < bestIdx)) {
                    bestIdx = idx
                }
            }
            return bestIdx
        }
        "chapter" -> {
            val aliases = chapterIdAliases(ref.token)
            return findChapterTitleChunkIndexInBody(sorted, aliases, substanceOnly)
        }
        "schedule" -> {
            sorted.forEachIndexed { idx, chunk ->
                if (substanceOnly && isTocLikeChunk(chunk)) return@forEachIndexed
                val text = chunk.text
                if (Regex("(?i)\\bschedule\\b").containsMatchIn(text) ||
                    text.contains("अनुसूची") || text.contains("SCHEDULE")
                ) {
                    return idx
                }
            }
            return -1
        }
        else -> return -1
    }
}

/** Chapter title-line in body chunks (skips TOC when [substanceOnly]). */
internal fun findChapterTitleChunkIndexInBody(
    sorted: List<RagChunkEntity>,
    aliases: Set<String>,
    substanceOnly: Boolean = true,
): Int {
    var bestIdx = -1
    var bestTier = Int.MAX_VALUE
    sorted.forEachIndexed { idx, chunk ->
        if (substanceOnly && isTocLikeChunk(chunk)) return@forEachIndexed
        for (line in chunk.text.lines()) {
            val tier = chapterLineMatchTier(line.trim(), aliases) ?: continue
            if (tier < bestTier) {
                bestTier = tier
                bestIdx = idx
            } else if (tier == bestTier && (bestIdx < 0 || idx < bestIdx)) {
                bestIdx = idx
            }
        }
    }
    return bestIdx
}

/**
 * Body window for an outline heading: title-line in body → next outline heading in body.
 * TOC chunks are skipped when [substanceOnly].
 */
internal fun headingAnchorWindowInBody(
    sorted: List<RagChunkEntity>,
    heading: String,
    headingsInOrder: List<String>,
    maxChunks: Int,
    substanceOnly: Boolean = true,
): HeadingWindow? {
    if (sorted.isEmpty() || maxChunks <= 0) return null
    val start = locateHeadingInBodyChunks(sorted, heading, substanceOnly)
    if (start >= 0) {
        return HeadingWindow(start, minOf(start + maxChunks, sorted.size))
    }
    val hi = headingsInOrder.indexOfFirst { it.equals(heading, ignoreCase = true) }
    val nextHeading = headingsInOrder.getOrNull(hi + 1) ?: return null
    val nextStart = locateHeadingInBodyChunks(sorted, nextHeading, substanceOnly)
    if (nextStart <= 0) return null
    val from = (nextStart - maxChunks).coerceAtLeast(0)
    return HeadingWindow(from, nextStart)
}
