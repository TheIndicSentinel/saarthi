package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity

/**
 * Wave 4 P18 + Phase 3.3 — tabular contract: amount/fee/penalty queries pull
 * table-like chunks (TABLE role or amount-heavy markers), not only Schedule/§33.
 */

internal const val TABULAR_CONTRACT_SCORE = ANCHORED_CHUNK_SCORE

internal fun requiresTabularContract(query: String): Boolean = isTabularAmountQuery(query)

internal fun isTableRoleChunk(entity: RagChunkEntity): Boolean =
    entity.chunkRole == ChunkRole.TABLE

internal fun isTabularLikeChunk(entity: RagChunkEntity): Boolean {
    if (entity.chunkIndex < 0) return false
    if (isTabularWeakFragment(entity.text)) return false
    if (isTableRoleChunk(entity)) return true
    if (tabularChunkTier(entity.text, entity.mimeType) == null) return false
    return tabularAmountLineCount(entity.text) >= 1
}

/** Strongest section-33 / penalties chapter signal in chunk text; lower = better. */
internal fun section33MatchTier(text: String): Int? {
    val tiers = mutableListOf<Int>()
    if (Regex("(?im)^\\s*33\\.\\s*Penalties").containsMatchIn(text)) tiers.add(0)
    if (Regex("(?im)PENALTIES\\s+AND\\s+ADJUDICATION").containsMatchIn(text)) tiers.add(1)
    if (Regex("(?m)^\\s*CHAPTER\\s+VIII\\b").containsMatchIn(text)) tiers.add(2)
    val headerTier = sectionHeaderMatchTier(text, "33")
    if (headerTier != null && headerTier <= 2) tiers.add(headerTier + 3)
    return tiers.minOrNull()
}

internal fun isScheduleContractChunk(text: String): Boolean =
    Regex("(?m)^\\s*THE SCHEDULE\\b").containsMatchIn(text)

internal fun isSection33ContractChunk(text: String): Boolean =
    section33MatchTier(text) != null

/**
 * Required tabular siblings: schedule/header chunk + amount-heavy body chunk
 * per scoped document (legal Schedule/§33 or generic TABLE/amount rows).
 */
internal fun tabularContractChunkEntities(
    contentChunks: List<RagChunkEntity>,
    preferDocUri: String? = null,
): List<RagChunkEntity> {
    val pool = contentChunks.filter { isBm25SearchableChunk(it) && isTabularLikeChunk(it) }
    if (pool.isEmpty()) return emptyList()

    fun pickContractPair(chunks: List<RagChunkEntity>): List<RagChunkEntity> {
        if (chunks.isEmpty()) return emptyList()
        val schedule = chunks
            .filter { isScheduleContractChunk(it.text) || isTableRoleChunk(it) }
            .minWithOrNull(tabularChunkRankComparator())
        val section33 = chunks.mapNotNull { chunk ->
            section33MatchTier(chunk.text)?.let { chunk to it }
        }.minWithOrNull(
            compareBy<Pair<RagChunkEntity, Int>> { it.second }
                .thenBy { it.first.chunkIndex },
        )?.first
        val amountHeavy = chunks
            .filter { tabularAmountLineCount(it.text) >= 1 }
            .maxWithOrNull(tabularChunkRankComparator())
        return listOfNotNull(schedule, section33 ?: amountHeavy)
            .distinctBy { it.docUri to it.chunkIndex }
            .take(2)
    }

    val prefer = preferDocUri?.takeIf { it.isNotEmpty() }
    if (prefer != null) {
        val fromPrefer = pickContractPair(pool.filter { it.docUri == prefer })
        if (fromPrefer.size >= 2) return fromPrefer
        val fromRest = pickContractPair(pool.filter { it.docUri != prefer })
        return (fromPrefer + fromRest).distinctBy { it.docUri to it.chunkIndex }
    }

    return pool.groupBy { it.docUri }
        .flatMap { (_, docChunks) -> pickContractPair(docChunks) }
        .distinctBy { it.docUri to it.chunkIndex }
}

private fun tabularChunkRankComparator(): Comparator<RagChunkEntity> =
    compareBy<RagChunkEntity> { tabularChunkTier(it.text, it.mimeType) ?: Int.MAX_VALUE }
        .thenByDescending { tabularAmountLineCount(it.text) }
        .thenBy { it.chunkIndex }
