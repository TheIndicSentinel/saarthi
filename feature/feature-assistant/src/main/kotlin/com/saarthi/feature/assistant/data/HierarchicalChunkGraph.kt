package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import com.saarthi.core.rag.Bm25Retriever

/**
 * Wave 6 P24 — hierarchical chunk graph: index-time parent links within a legal
 * section and retrieval expansion to fetch the complete section span.
 */

internal data class IndexedChunkText(
    val text: String,
    val parentChunkIndex: Int? = null,
)

/** First chunk index of the legal section this chunk belongs to. */
internal fun sectionRootChunkIndex(entity: RagChunkEntity): Int =
    entity.parentChunkIndex ?: entity.chunkIndex

internal fun chunkDocumentForIndexing(text: String, mimeType: String = ""): List<IndexedChunkText> {
    if (mimeType.contains("pdf", ignoreCase = true) || mimeType.contains("doc", ignoreCase = true)) {
        if (isLegalGazetteStyleDocument(text)) {
            return chunkLegalGazetteDocumentWithParents(text)
        }
    }
    if (isLegalGazetteStyleDocument(text)) {
        return chunkLegalGazetteDocumentWithParents(text)
    }
    return assignDefaultProseParentLinks(chunkDocumentText(text, 600, 80))
}

internal fun assignDefaultProseParentLinks(chunks: List<String>): List<IndexedChunkText> {
    if (chunks.size <= 1) return chunks.map { IndexedChunkText(it) }
    return chunks.mapIndexed { index, chunk ->
        IndexedChunkText(text = chunk, parentChunkIndex = if (index == 0) null else 0)
    }
}

internal fun chunkLegalGazetteDocumentWithParents(text: String): List<IndexedChunkText> {
    val sections = splitLegalGazetteSections(text)
    if (sections.isEmpty()) return emptyList()
    if (sections.size <= 1) {
        val size = if (isTableHeavyLegalSection(text)) LEGAL_TABLE_CHUNK_SIZE else LEGAL_PROSE_CHUNK_SIZE
        val overlap = if (isTableHeavyLegalSection(text)) LEGAL_TABLE_OVERLAP else LEGAL_PROSE_OVERLAP
        return assignDefaultProseParentLinks(chunkDocumentText(text, size, overlap))
    }
    val result = ArrayList<IndexedChunkText>()
    var globalIdx = 0
    for (section in sections) {
        val size = if (isTableHeavyLegalSection(section)) LEGAL_TABLE_CHUNK_SIZE else LEGAL_PROSE_CHUNK_SIZE
        val overlap = if (isTableHeavyLegalSection(section)) LEGAL_TABLE_OVERLAP else LEGAL_PROSE_OVERLAP
        val sectionChunks = chunkDocumentText(section, size, overlap)
        val rootIdx = globalIdx
        sectionChunks.forEachIndexed { i, chunk ->
            result.add(
                IndexedChunkText(
                    text = chunk,
                    parentChunkIndex = if (i == 0) null else rootIdx,
                ),
            )
            globalIdx++
        }
    }
    return result
}

internal fun buildSectionGroupsByDoc(
    chunks: List<RagChunkEntity>,
): Map<String, Map<Int, List<RagChunkEntity>>> =
    chunks
        .filter { it.chunkIndex >= 0 }
        .groupBy { it.docUri }
        .mapValues { (_, docChunks) ->
            docChunks.groupBy { sectionRootChunkIndex(it) }
        }

/**
 * When a hit lands in a multi-chunk legal section, pull every sibling chunk in
 * that section so the prompt gets the complete operative span (not one fragment).
 */
internal fun expandHierarchicalSectionHits(
    ranked: List<Bm25Retriever.Scored>,
    pool: List<RagChunkEntity>,
    sectionGroupsByDoc: Map<String, Map<Int, List<RagChunkEntity>>>,
    topHitCount: Int = RERANK_EXPANSION_TOP_HITS,
    expansionScore: Double = RERANK_EXPANSION_SCORE,
): List<Pair<RagChunkEntity, Double>> {
    if (ranked.isEmpty() || pool.isEmpty()) return emptyList()
    val expanded = mutableListOf<Pair<RagChunkEntity, Double>>()
    val usedIds = mutableSetOf<Long>()
    for ((rank, scored) in ranked.withIndex()) {
        if (rank >= topHitCount) break
        val hit = pool.getOrNull(scored.index) ?: continue
        val root = sectionRootChunkIndex(hit)
        val siblings = sectionGroupsByDoc[hit.docUri]?.get(root).orEmpty()
        if (siblings.size <= 1) continue
        for (sibling in siblings) {
            if (sibling.id != hit.id && usedIds.add(sibling.id)) {
                expanded.add(sibling to expansionScore)
            }
        }
    }
    return expanded
}
