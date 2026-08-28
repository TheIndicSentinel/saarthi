package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import com.saarthi.core.rag.Bm25Retriever

/**
 * Wave 4 P16 — two-stage retrieve: BM25/FTS candidate pool (~20–30) then
 * lightweight feature rerank (heading, line-start, chapter metadata, tabular,
 * query-type) before score-gap trim.
 *
 * Wave 6 P28 — cross-encoder rerank is **intentionally off** (`CROSS_ENCODER_RERANK_ENABLED`).
 * Revisit only after golden harness plateaus and on-device RAM/latency allows a second model pass.
 */

/** Cross-encoder rerank deferred — production uses [featureRerankBm25Candidates] only. */
internal const val CROSS_ENCODER_RERANK_ENABLED = false

internal fun crossEncoderRerankEnabled(): Boolean = CROSS_ENCODER_RERANK_ENABLED

internal const val FEATURE_RERANK_CANDIDATE_MIN = 20
internal const val FEATURE_RERANK_CANDIDATE_MAX = 30

internal data class FeatureRerankContext(
    val queryChapterAliases: Set<String> = emptySet(),
    val tabularQuery: Boolean = false,
    val chapterSpanQuery: Boolean = false,
    val structureListQuery: Boolean = false,
    val structureCountQuery: Boolean = false,
)

/** Candidate pool size for stage-1 BM25 before feature rerank. */
internal fun featureRerankCandidatePoolSize(
    topK: Int,
    uniqueDocs: Int,
    poolSize: Int,
): Int {
    if (poolSize <= 0) return 0
    val bm25Head = (topK * uniqueDocs.coerceAtLeast(1)).coerceAtLeast(topK)
    return bm25Head
        .coerceAtLeast(FEATURE_RERANK_CANDIDATE_MIN)
        .coerceAtMost(FEATURE_RERANK_CANDIDATE_MAX)
        .coerceAtMost(poolSize)
        .coerceAtLeast(topK.coerceAtMost(poolSize))
}

internal fun buildFeatureRerankContext(query: String): FeatureRerankContext {
    val chapterAliases = extractSectionRefs(query)
        .filter { it.kind == "chapter" }
        .flatMap { chapterIdAliases(it.token) }
        .toSet()
    return FeatureRerankContext(
        queryChapterAliases = chapterAliases,
        tabularQuery = isTabularAmountQuery(query),
        chapterSpanQuery = isChapterSpanQuery(query),
        structureListQuery = isStructureListQuery(query),
        structureCountQuery = isStructureCountQuery(query),
    )
}

/**
 * Additive feature bonus on top of BM25 score. Bonuses are tuned so a strong
 * structural signal can reorder within the BM25 neighborhood without swamping
 * unrelated high-BM25 hits.
 */
internal fun featureRerankBonus(
    entity: RagChunkEntity,
    query: String,
    ctx: FeatureRerankContext,
): Double {
    var bonus = 0.0
    val text = entity.text
    val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()

    when (entity.chunkRole) {
        ChunkRole.HEADING -> bonus += 1.8
        ChunkRole.TABLE -> bonus += if (ctx.tabularQuery) 2.5 else 0.4
        ChunkRole.REGISTRY -> if (ctx.structureListQuery || ctx.structureCountQuery) bonus += 1.2
    }

    if (firstLine.isNotEmpty()) {
        val structureQuery = ctx.structureListQuery || ctx.structureCountQuery || ctx.chapterSpanQuery
        if (structureQuery && isLikelyHeadingLine(firstLine, nextLineBlank = false)) bonus += 1.2
        if (LINE_START_STRUCTURE_RX.containsMatchIn(firstLine)) bonus += 1.6
        val lineScore = chapterTitleLineScore(query, firstLine)
        if (lineScore >= 0.45) bonus += lineScore * 3.0
    }

    val headingPath = entity.headingPath?.trim().orEmpty()
    if (headingPath.isNotEmpty()) {
        val pathScore = chapterTitleLineScore(query, headingPath)
        if (pathScore >= 0.35) bonus += pathScore * 2.0
    }

    val chapterId = entity.chapterId
    if (ctx.queryChapterAliases.isNotEmpty() && chapterId != null) {
        val idForms = chapterIdAliases(chapterId)
        if (idForms.any { it in ctx.queryChapterAliases }) bonus += 2.8
    }

    if (ctx.tabularQuery) {
        val tier = tabularChunkTier(text, entity.mimeType)
        if (tier != null) {
            bonus += when (tier) {
                0 -> 4.0
                1 -> 2.5
                2 -> 1.5
                3 -> 1.0
                else -> 0.5
            }
        }
    }

    if (ctx.chapterSpanQuery && ctx.queryChapterAliases.isNotEmpty()) {
        text.lines().take(6).forEach { line ->
            val num = extractChapterNumberFromLine(line)
            if (num != null && chapterIdAliases(intToRoman(num)).any { it in ctx.queryChapterAliases }) {
                bonus += 2.0
            }
        }
    }

    if (ctx.structureListQuery && STRUCTURE_MARKER_RX.containsMatchIn(text)) {
        bonus += 0.8
    }

    return bonus
}

internal fun featureRerankBm25Candidates(
    ranked: List<Bm25Retriever.Scored>,
    pool: List<RagChunkEntity>,
    query: String,
    ctx: FeatureRerankContext = buildFeatureRerankContext(query),
): List<Bm25Retriever.Scored> {
    if (ranked.isEmpty() || pool.isEmpty()) return ranked
    val rescored = ranked.map { scored ->
        val entity = pool.getOrNull(scored.index) ?: return@map scored
        val bonus = featureRerankBonus(entity, query, ctx)
        Bm25Retriever.Scored(scored.index, scored.score + bonus)
    }
    return rescored.sortedByDescending { it.score }
}

private val LINE_START_STRUCTURE_RX = Regex(
    "(?im)^\\s*(CHAPTER|Chapter|Section|THE SCHEDULE|अध्याय)\\s+",
)

private val STRUCTURE_MARKER_RX = Regex(
    "(?im)\\b(CHAPTER|Chapter|Section|अध्याय)\\s+([IVXLCDM]+|\\d+)",
)
