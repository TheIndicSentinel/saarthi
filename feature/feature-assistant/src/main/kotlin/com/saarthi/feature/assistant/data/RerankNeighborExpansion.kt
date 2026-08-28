package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import com.saarthi.core.rag.Bm25Retriever

/**
 * Wave 4 P17 — after feature rerank, expand top hits with parent heading +
 * next chunk in the same document. Expansion siblings use [RERANK_EXPANSION_SCORE]
 * so [collapseRedundantChunkRuns] keeps the full operative span.
 */

internal const val RERANK_EXPANSION_SCORE = ANCHORED_CHUNK_SCORE

/** Top reranked hits that receive parent + neighbor expansion (was top-2 BM25 only). */
internal const val RERANK_EXPANSION_TOP_HITS = 3

/** Previous content chunk in the same document only. */
internal fun prevSameDocNeighborId(
    hitId: Long,
    hitDocUri: String,
    orderedIdsByDoc: Map<String, List<Long>>,
): Long? {
    if (hitDocUri.isEmpty()) return null
    val ordered = orderedIdsByDoc[hitDocUri] ?: return null
    val pos = ordered.indexOf(hitId)
    if (pos <= 0) return null
    return ordered[pos - 1]
}

internal fun isParentHeadingChunk(entity: RagChunkEntity): Boolean {
    when (entity.chunkRole) {
        ChunkRole.HEADING -> return true
        ChunkRole.REGISTRY, ChunkRole.OUTLINE -> return true
    }
    val firstLine = entity.text.lineSequence().firstOrNull()?.trim().orEmpty()
    if (firstLine.isEmpty()) return false
    return isLikelyHeadingLine(firstLine, nextLineBlank = false) ||
        PARENT_HEADING_LINE_RX.containsMatchIn(firstLine)
}

internal fun hitNeedsParentHeading(entity: RagChunkEntity): Boolean {
    val firstLine = entity.text.lineSequence().firstOrNull()?.trim().orEmpty()
    if (firstLine.isEmpty()) return true
    return !PARENT_HEADING_LINE_RX.containsMatchIn(firstLine) &&
        !isLikelyHeadingLine(firstLine, nextLineBlank = false)
}

/**
 * Parent heading chunk for [hit]: immediate predecessor when it looks like a
 * heading, otherwise walk back one more chunk in the same document.
 */
internal fun parentHeadingChunkForHit(
    hit: RagChunkEntity,
    docChunks: List<RagChunkEntity>,
    orderedIdsByDoc: Map<String, List<Long>>,
): RagChunkEntity? {
    if (!hitNeedsParentHeading(hit)) return null
    var walkId = hit.id
    repeat(2) {
        val prevId = prevSameDocNeighborId(walkId, hit.docUri, orderedIdsByDoc) ?: return null
        val prev = docChunks.firstOrNull { it.id == prevId } ?: return null
        if (isParentHeadingChunk(prev)) return prev
        walkId = prevId
    }
    val immediatePrevId = prevSameDocNeighborId(hit.id, hit.docUri, orderedIdsByDoc) ?: return null
    return docChunks.firstOrNull { it.id == immediatePrevId }
}

/**
 * Expand reranked top hits with parent heading + next chunk. Returns expansion
 * siblings (entity + score), not the hits themselves.
 */
internal fun expandRerankedNeighborHits(
    ranked: List<Bm25Retriever.Scored>,
    pool: List<RagChunkEntity>,
    docChunksByUri: Map<String, List<RagChunkEntity>>,
    orderedIdsByDoc: Map<String, List<Long>>,
    topHitCount: Int = RERANK_EXPANSION_TOP_HITS,
    expansionScoreMultiplier: Double = 0.5,
): List<Pair<RagChunkEntity, Double>> {
    if (ranked.isEmpty() || pool.isEmpty()) return emptyList()
    val expanded = mutableListOf<Pair<RagChunkEntity, Double>>()
    val usedIds = mutableSetOf<Long>()
    for ((rank, scored) in ranked.withIndex()) {
        if (rank >= topHitCount) break
        val hit = pool.getOrNull(scored.index) ?: continue
        val expansionOrganic = scored.score * expansionScoreMultiplier
        val docChunks = docChunksByUri[hit.docUri].orEmpty()
        val parent = parentHeadingChunkForHit(hit, docChunks, orderedIdsByDoc)
        if (parent != null && usedIds.add(parent.id)) {
            expanded.add(parent to expansionOrganic)
        }
        val nextId = nextSameDocNeighborId(hit.id, hit.docUri, orderedIdsByDoc)
        if (nextId != null) {
            val next = docChunks.firstOrNull { it.id == nextId }
            if (next != null && usedIds.add(next.id)) {
                expanded.add(next to expansionOrganic)
            }
        }
    }
    return expanded
}

private val PARENT_HEADING_LINE_RX = Regex(
    "(?im)^\\s*(CHAPTER|Chapter|Section|THE SCHEDULE|अध्याय)\\s+",
)
