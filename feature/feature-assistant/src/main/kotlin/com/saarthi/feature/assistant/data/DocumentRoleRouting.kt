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
    if (wantsCommentaryDocumentQuery(query)) return false
    return isChapterSpanQuery(query) ||
        extractSectionRefs(query).isNotEmpty() ||
        requiresTabularContract(query) ||
        isExplicitLookupQuery(query) ||
        isSectionPenaltyComboQuery(query) ||
        isDeicticPrimaryActQuery(query)
}

/** Tier 2.6 — user explicitly asks about a guide/summary/consulting commentary file. */
internal fun wantsCommentaryDocumentQuery(query: String): Boolean {
    val lower = query.lowercase()
    if (COMMENTARY_DOC_QUERY_PHRASES.any { lower.contains(it) }) return true
    return COMMENTARY_DOC_QUERY_PATTERN.containsMatchIn(lower)
}

private val COMMENTARY_DOC_QUERY_PHRASES = listOf(
    "in the guide", "from the guide", "the guide says", "guide explains",
    "compliance journey", "implementation guide", "practitioner guide",
    "consulting guide", "ey guide", "ey india",
    "in the summary", "from the summary", "one page summary", "brief summary",
    "sample document", "demo document",
    "गाइड में", "मार्गदर्शिका", "सारांश में", "नमूना दस्तावेज",
)

private val COMMENTARY_DOC_QUERY_PATTERN = Regex(
    "(?i)\\b(guide|handbook|summary|synopsis|sample doc|demo doc|commentary)\\b",
)

/**
 * Tier 2.6 — generic “the act / this law” without naming a guide → prefer primary source
 * when a commentary file shares the session.
 */
internal fun isDeicticPrimaryActQuery(query: String): Boolean {
    val lower = query.lowercase()
    if (wantsCommentaryDocumentQuery(query)) return false
    if (DEICTIC_PRIMARY_ACT_PATTERN.containsMatchIn(lower)) return true
    return DEICTIC_PRIMARY_ACT_PHRASES.any { lower.contains(it) }
}

private val DEICTIC_PRIMARY_ACT_PHRASES = listOf(
    "the act", "this act", "this law", "this bill", "in the act", "under the act",
    "what does the act", "what the act says",
    "इस अधिनियम", "इस कानून", "अधिनियम में", "अधिनियम के",
)

private val DEICTIC_PRIMARY_ACT_PATTERN = Regex(
    "(?i)\\b(the|this)\\s+(act|law|bill|statute|code|rules|regulation)\\b",
)

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
    if (route.equalSlots) return contentChunks
    val primary = primarySourceDocUris(docRoles)
    val commentary = commentaryDocUris(docRoles)
    if (primary.isEmpty() || commentary.isEmpty()) return contentChunks
    if (wantsCommentaryDocumentQuery(query)) {
        val commentaryChunks = contentChunks.filter { it.docUri in commentary }
        return commentaryChunks.ifEmpty { contentChunks }
    }
    if (!wantsSubstancePrimaryBias(query, route, isFollowUp)) return contentChunks
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
