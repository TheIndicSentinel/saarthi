package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity

/** Phase 4.1 — persisted at index; mirrors [documentRoleLabel] for retrieval routing. */
internal const val DOCUMENT_ROLE_CHUNK_INDEX = -5

internal fun encodeIndexedDocumentRole(role: DocumentRoleLabel): String = role.name.lowercase()

internal fun decodeIndexedDocumentRole(text: String): DocumentRoleLabel? =
    DocumentRoleLabel.entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }

/**
 * Role stamped at index time, or recomputed from outline/opening when older rows
 * lack a stamp (schema bump / re-attach refreshes the stamp).
 */
internal fun resolveDocumentRoleFromChunks(chunks: List<RagChunkEntity>): DocumentRoleLabel? {
    val stamped = chunks.firstOrNull { it.chunkIndex == DOCUMENT_ROLE_CHUNK_INDEX }?.text
    if (stamped != null) {
        return decodeIndexedDocumentRole(stamped)
    }
    val body = chunks.filter { it.chunkIndex >= 0 }.sortedBy { it.chunkIndex }
    if (body.isEmpty()) return null
    val outline = chunks.firstOrNull { it.chunkIndex == OUTLINE_CHUNK_INDEX }?.text
    val opening = body.firstOrNull()?.text
    val charCount = body.sumOf { it.text.length }
    val contentHint = outline ?: openingPageContentSample(opening)
    return documentRoleLabel(body.first().docName, contentHint, charCount)
}

internal fun documentRolesByUri(entities: List<RagChunkEntity>): Map<String, DocumentRoleLabel?> =
    entities
        .groupBy { it.docUri }
        .mapValues { (_, chunks) -> resolveDocumentRoleFromChunks(chunks) }

internal fun isCommentaryDocumentRole(role: DocumentRoleLabel?): Boolean = role != null

internal fun primarySourceDocUris(docRoles: Map<String, DocumentRoleLabel?>): Set<String> =
    docRoles.filterValues { it == null }.keys.filter { it.isNotEmpty() }.toSet()

internal fun commentaryDocUris(docRoles: Map<String, DocumentRoleLabel?>): Set<String> =
    docRoles.filterValues { it != null }.keys.filter { it.isNotEmpty() }.toSet()

internal fun wantsMetaStructuralCommentaryBias(metaReason: String?, whichFile: Boolean): Boolean =
    metaReason != null || whichFile

internal fun wantsSubstancePrimaryBias(
    query: String,
    route: QueryRoute,
    isFollowUp: Boolean,
): Boolean {
    if (route.equalSlots) return false
    if (isFollowUp) return false
    if (effectiveMetaRouteReason(query, isFollowUp = false) != null) return false
    return isChapterSpanQuery(query) ||
        extractSectionRefs(query).isNotEmpty() ||
        requiresTabularContract(query) ||
        isExplicitLookupQuery(query) ||
        isSectionPenaltyComboQuery(query)
}

/**
 * When a session mixes commentary (guide/summary) with a primary source, substance
 * queries should not dredge the commentary file's BM25 pool.
 */
internal fun filterSubstanceContentChunks(
    contentChunks: List<RagChunkEntity>,
    docRoles: Map<String, DocumentRoleLabel?>,
    query: String,
    route: QueryRoute,
    isFollowUp: Boolean,
): List<RagChunkEntity> {
    if (!wantsSubstancePrimaryBias(query, route, isFollowUp)) return contentChunks
    val primary = primarySourceDocUris(docRoles)
    val commentary = commentaryDocUris(docRoles)
    if (primary.isEmpty() || commentary.isEmpty()) return contentChunks
    return contentChunks.filter { it.docUri in primary }
}

internal fun orderDocUrisForStructuralSample(
    docUris: List<String>,
    docRoles: Map<String, DocumentRoleLabel?>,
    metaReason: String?,
    whichFile: Boolean,
    query: String,
    route: QueryRoute,
): List<String> {
    val commentaryBias = wantsMetaStructuralCommentaryBias(metaReason, whichFile)
    val primaryBias = wantsSubstancePrimaryBias(query, route, isFollowUp = false) && !commentaryBias
    return docUris.sortedWith(
        compareBy { uri ->
            val role = docRoles[uri]
            when {
                commentaryBias && isCommentaryDocumentRole(role) -> 0
                commentaryBias -> 1
                primaryBias && role == null -> 0
                primaryBias -> 1
                else -> 2
            }
        },
    )
}
