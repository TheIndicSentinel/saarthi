package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity

/**
 * Wave 4 P18 — tabular contract: penalty/fee/schedule queries always pull
 * [THE SCHEDULE] and section-33 penalty factors as required siblings (Kisan
 * MSP full-table injection pattern for legal tabular asks).
 */

internal const val TABULAR_CONTRACT_SCORE = ANCHORED_CHUNK_SCORE

internal fun requiresTabularContract(query: String): Boolean = isTabularAmountQuery(query)

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
 * Required tabular siblings for penalty/schedule queries: best THE SCHEDULE
 * chunk and best section-33 / penalties chapter chunk per scoped document.
 */
internal fun tabularContractChunkEntities(
    contentChunks: List<RagChunkEntity>,
    preferDocUri: String? = null,
): List<RagChunkEntity> {
    val pool = contentChunks.filter { it.chunkIndex >= 0 && !isTabularWeakFragment(it.text) }
    if (pool.isEmpty()) return emptyList()

    fun pickContractPair(chunks: List<RagChunkEntity>): List<RagChunkEntity> {
        if (chunks.isEmpty()) return emptyList()
        val schedule = chunks
            .filter { isScheduleContractChunk(it.text) }
            .minWith(
                compareBy<RagChunkEntity> { tabularChunkTier(it.text, it.mimeType) ?: Int.MAX_VALUE }
                    .thenByDescending { tabularAmountLineCount(it.text) }
                    .thenBy { it.chunkIndex },
            )
        val section33 = chunks.mapNotNull { chunk ->
            section33MatchTier(chunk.text)?.let { chunk to it }
        }.minWith(
            compareBy<Pair<RagChunkEntity, Int>> { it.second }
                .thenBy { it.first.chunkIndex },
        )?.first
        return listOfNotNull(schedule, section33).distinctBy { it.docUri to it.chunkIndex }
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
