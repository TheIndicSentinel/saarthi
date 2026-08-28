package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity

/** Auto-extracted document outline row (negative chunkIndex sentinel). */
internal const val OUTLINE_CHUNK_INDEX = -1

/** Roles excluded from BM25, FTS, and citation surfaces by construction. */
private val SYSTEM_CHUNK_ROLES = setOf(
    ChunkRole.OUTLINE,
    ChunkRole.REGISTRY,
    ChunkRole.PROMPT_HINT,
)

internal fun isSystemChunkRole(role: String?): Boolean =
    role != null && role in SYSTEM_CHUNK_ROLES

/** Indexed rows that must never enter lexical rank pools (outline/registry/hint). */
internal fun isBm25SearchableChunk(entity: RagChunkEntity): Boolean =
    entity.chunkIndex >= 0 && !isSystemChunkRole(entity.chunkRole)

internal fun allowsOutlineChunkCitations(query: String): Boolean =
    isStructureCountQuery(query) ||
        isStructureListQuery(query) ||
        isDocumentMetaOverviewQuery(query)

internal fun isCitableRetrievalChunk(chunk: RetrievedChunk, query: String? = null): Boolean {
    when (chunk.chunkIndex) {
        RETRIEVAL_HINT_CHUNK_INDEX,
        STRUCTURE_REGISTRY_CHUNK_INDEX,
        -> return false
        OUTLINE_CHUNK_INDEX -> return query != null && allowsOutlineChunkCitations(query)
    }
    if (
        chunk.structuralAnchor == StructuralAnchorKind.STRUCTURE_HINT ||
        chunk.structuralAnchor == StructuralAnchorKind.RETRIEVAL_HINT
    ) {
        return false
    }
    return chunk.chunkIndex >= 0
}

internal fun citableRetrievalChunks(
    retrieved: List<RetrievedChunk>,
    query: String? = null,
): List<RetrievedChunk> = retrieved.filter { isCitableRetrievalChunk(it, query) }
