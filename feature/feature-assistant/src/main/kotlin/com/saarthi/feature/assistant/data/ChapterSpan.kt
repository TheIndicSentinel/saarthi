package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity

/**
 * Wave 1 P3 — chapter-span retrieval API.
 *
 * "Chapter VII highlights" resolves to one canonical body span:
 * normalized chapter ID → title-line header → next chapter header (not BM25
 * on "VII" tokens or a fixed 5-chunk window from the first cross-reference).
 */

private val CHAPTER_SPAN_CUE_PATTERN = Regex(
    "(?i)\\b(" +
        "highlights?|highlighted|key points?|main points?|" +
        "summar(?:y|ise|ize|ies)|overview|gist|essence|" +
        "what does|what is in|contents? of|tell me about|explain|describe|" +
        "cover|covers|said in|says in|talks about" +
        ")\\b",
)

private val CHAPTER_LINE_ID_RX = Regex(
    "(?i)(?:^|\\s)(?:CHAPTER|Chapter|अध्याय)\\s+([IVXLCDM]+|\\d+)(?:\\b|\\s)",
)

/** Chapter-targeted span query (highlights / what chapter X says), not count/list. */
internal fun isChapterSpanQuery(query: String): Boolean {
    if (isStructureCountQuery(query) || isStructureListQuery(query)) return false
    val chapterRef = extractChapterSpanRef(query) ?: return false
    if (CHAPTER_SPAN_CUE_PATTERN.containsMatchIn(query)) return true
    val trimmed = query.trim()
    return Regex(
        "(?i)\\bchapter\\s+${Regex.escape(chapterRef)}\\s*[?.!]?\$",
    ).containsMatchIn(trimmed)
}

/** First chapter ref in [query] (roman or digit). */
internal fun extractChapterSpanRef(query: String): String? =
    extractSectionRefs(query).firstOrNull { it.kind == "chapter" }?.token

internal fun romanToInt(roman: String): Int? {
    val upper = roman.trim().uppercase()
    if (upper.isEmpty() || !upper.all { it in "IVXLCDM" }) return null
    val values = mapOf(
        'I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000,
    )
    var sum = 0
    var prev = 0
    for (c in upper.reversed()) {
        val v = values[c] ?: return null
        sum += if (v < prev) -v else v
        prev = v
    }
    return sum
}

internal fun intToRoman(n: Int): String {
    if (n <= 0) return n.toString()
    val pairs = listOf(
        10 to "X", 9 to "IX", 8 to "VIII", 7 to "VII", 6 to "VI",
        5 to "V", 4 to "IV", 3 to "III", 2 to "II", 1 to "I",
    )
    var rem = n
    val sb = StringBuilder()
    for ((value, numeral) in pairs) {
        while (rem >= value) {
            sb.append(numeral)
            rem -= value
        }
    }
    return sb.toString()
}

internal fun chapterNumericId(token: String): Int? {
    val t = token.trim()
    if (t.isEmpty()) return null
    t.toIntOrNull()?.let { return it }
    return romanToInt(t)
}

/** Equivalent forms for matching (vi / VI / 6). */
internal fun chapterIdAliases(token: String): Set<String> {
    val trimmed = token.trim()
    if (trimmed.isEmpty()) return emptySet()
    val forms = linkedSetOf(trimmed.lowercase(), trimmed.uppercase())
    val num = chapterNumericId(trimmed)
    if (num != null && num in 1..99) {
        forms.add(num.toString())
        val roman = intToRoman(num)
        forms.add(roman.lowercase())
        forms.add(roman)
    }
    return forms
}

internal fun extractChapterNumberFromLine(line: String): Int? {
    val m = CHAPTER_LINE_ID_RX.find(line.trim()) ?: return null
    return chapterNumericId(m.groupValues[1])
}

/**
 * Tier when [line] is a chapter header for one of [aliases]; null if no match.
 * Uses [chapterHeaderMatchTier] so line-start CHAPTER beats mid-sentence cross-refs.
 */
internal fun chapterLineMatchTier(line: String, aliases: Set<String>): Int? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || aliases.isEmpty()) return null
    val lineNum = extractChapterNumberFromLine(trimmed) ?: return null
    val requestedNums = aliases.mapNotNull { chapterNumericId(it) }
    if (!requestedNums.contains(lineNum)) return null
    return chapterHeaderMatchTier(trimmed)
}

internal data class ChapterSpanWindow(
    val startChunkIndex: Int,
    val endChunkIndexExclusive: Int,
    val matchedTier: Int,
    val chapterNum: Int,
)

/** Best title-line chunk index for the requested chapter (lowest tier wins). */
internal fun findChapterTitleChunkIndex(
    chunkTexts: List<String>,
    aliases: Set<String>,
): Int {
    var bestIdx = -1
    var bestTier = Int.MAX_VALUE
    chunkTexts.forEachIndexed { idx, text ->
        for (line in text.lines()) {
            val tier = chapterLineMatchTier(line.trim(), aliases) ?: continue
            if (tier < bestTier) {
                bestTier = tier
                bestIdx = idx
            } else if (tier == bestTier && bestIdx < 0) {
                bestIdx = idx
            } else if (tier == bestTier && idx < bestIdx) {
                bestIdx = idx
            }
        }
    }
    return bestIdx
}

/** End exclusive: next chapter title-line or [maxChunks] cap from [fromChunkIdx]. */
internal fun findChapterSpanEndExclusive(
    sorted: List<RagChunkEntity>,
    fromChunkIdx: Int,
    requestedNum: Int,
    maxChunks: Int,
): Int {
    val hardCap = minOf(fromChunkIdx + maxChunks, sorted.size)
    for (idx in (fromChunkIdx + 1) until sorted.size) {
        if (idx >= hardCap) return hardCap
        val hasNextChapter = sorted[idx].text.lines().any { line ->
            val trimmed = line.trim()
            val num = extractChapterNumberFromLine(trimmed) ?: return@any false
            if (num == requestedNum) return@any false
            val tier = chapterHeaderMatchTier(trimmed)
            tier != null && tier <= 1
        }
        if (hasNextChapter) return idx
    }
    return hardCap
}

internal fun resolveChapterSpanWindow(
    sorted: List<RagChunkEntity>,
    chapterRef: String,
    maxChunks: Int,
): ChapterSpanWindow? {
    val aliases = chapterIdAliases(chapterRef)
    val requestedNum = chapterNumericId(chapterRef) ?: return null
    val startIdx = findChapterTitleChunkIndexInBody(sorted, aliases, substanceOnly = true)
    if (startIdx < 0) return null
    return buildChapterSpanWindow(sorted, startIdx, requestedNum, aliases, maxChunks)
}

/**
 * Retry pass when strict title-line match fails — accepts line-start headers
 * up to tier 2 (digit chapter lines) but still skips TOC blocks.
 */
internal fun resolveChapterSpanWindowRelaxed(
    sorted: List<RagChunkEntity>,
    chapterRef: String,
    maxChunks: Int,
): ChapterSpanWindow? {
    val aliases = chapterIdAliases(chapterRef)
    val requestedNum = chapterNumericId(chapterRef) ?: return null
  var bestIdx = -1
    var bestTier = Int.MAX_VALUE
    sorted.forEachIndexed { idx, chunk ->
        if (isTocLikeChunk(chunk)) return@forEachIndexed
        for (line in chunk.text.lines()) {
            val trimmed = line.trim()
            val tier = chapterLineMatchTier(trimmed, aliases) ?: continue
            if (tier > 2) continue
            if (tier < bestTier) {
                bestTier = tier
                bestIdx = idx
            } else if (tier == bestTier && (bestIdx < 0 || idx < bestIdx)) {
                bestIdx = idx
            }
        }
    }
    if (bestIdx < 0) return null
    return buildChapterSpanWindow(sorted, bestIdx, requestedNum, aliases, maxChunks, bestTier)
}

private fun buildChapterSpanWindow(
    sorted: List<RagChunkEntity>,
    startIdx: Int,
    requestedNum: Int,
    aliases: Set<String>,
    maxChunks: Int,
    matchedTier: Int = Int.MAX_VALUE,
): ChapterSpanWindow {
    var tier = matchedTier
    if (tier == Int.MAX_VALUE) {
        for (line in sorted[startIdx].text.lines()) {
            val t = chapterLineMatchTier(line.trim(), aliases)
            if (t != null) tier = minOf(tier, t)
        }
    }
    val endIdx = findChapterSpanEndExclusive(sorted, startIdx, requestedNum, maxChunks)
    return ChapterSpanWindow(
        startChunkIndex = startIdx,
        endChunkIndexExclusive = endIdx,
        matchedTier = tier,
        chapterNum = requestedNum,
    )
}

/**
 * Canonical chapter span for [query] across scoped [contentChunks].
 * Returns contiguous body chunks from title-line through next chapter (or cap).
 */
internal fun resolveChapterSpanChunks(
    contentChunks: List<RagChunkEntity>,
    query: String,
    maxChunks: Int,
): List<RagChunkEntity> {
    val chapterRef = extractChapterSpanRef(query) ?: return emptyList()
    var bestWindow: ChapterSpanWindow? = null
    var bestSorted: List<RagChunkEntity>? = null
    for ((_, docChunks) in contentChunks.groupBy { it.docUri }) {
        val sorted = docChunks.sortedBy { it.chunkIndex }
        val window = resolveChapterSpanWindow(sorted, chapterRef, maxChunks)
        if (window == null) continue
        if (bestWindow == null || window.matchedTier < bestWindow.matchedTier) {
            bestWindow = window
            bestSorted = sorted
        }
    }
    val window = bestWindow ?: return emptyList()
    val sorted = bestSorted!!
    val span = sorted.subList(window.startChunkIndex, window.endChunkIndexExclusive)
    logRag(
        "chapter-span id=${chapterRef.uppercase()} tier=${window.matchedTier} " +
            "chunks=${span.size} ch#${window.chapterNum}",
    )
    return span
}
