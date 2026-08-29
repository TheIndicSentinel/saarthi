package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity

/**
 * Phase B — question-shaped retrieval: in-doc compare, examples/activities,
 * set enumeration spans, absence inventory, and safer filename named-doc match.
 */

/** B1 — concept contrast inside one document (not multi-file equal slots). */
internal fun isInDocConceptComparisonQuery(query: String): Boolean {
    if (isInDocumentSectionContrast(query)) return true
    return extractInDocComparisonSides(query).size >= 2
}

internal fun extractInDocComparisonSides(query: String): List<String> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val patterns = listOf(
        Regex("(?i)difference[s]? between (.+?) and (.+?)(?:[?.!]|$)"),
        Regex("(?i)some differences between (.+?) and (.+?)(?:[?.!]|$)"),
        Regex("(?i)compare (.+?) (?:with|and|to|versus|vs) (.+?)(?:[?.!]|$)"),
        Regex("(?i)how (?:is|are) (.+?) different from (.+?)(?:[?.!]|$)"),
    )
    for (pattern in patterns) {
        val match = pattern.find(trimmed)
        if (match != null) {
            return listOf(match.groupValues[1].trim(), match.groupValues[2].trim())
                .filter { it.length >= 3 }
        }
    }
    return emptyList()
}

internal fun inDocComparisonQueryExpansion(query: String): String {
    val sides = extractInDocComparisonSides(query)
    if (sides.isEmpty()) return ""
    val terms = LinkedHashSet<String>()
    for (side in sides) {
        terms.addAll(headingTokens(side))
        terms.addAll(focusEntityTokens(side))
    }
    terms.addAll(expandEntityAliasTokens(terms))
    return terms.filter { it.length >= 3 }.joinToString(" ")
}

/**
 * B1 — at least one body chunk per compared concept when both sides are named.
 */
internal fun pickInDocComparisonChunkEntities(
    contentChunks: List<RagChunkEntity>,
    query: String,
    maxPerSide: Int = 2,
): List<RagChunkEntity> {
    val sides = extractInDocComparisonSides(query)
    if (sides.size < 2) return emptyList()
    val picked = LinkedHashSet<RagChunkEntity>()
    for (side in sides) {
        val baseTerms = headingTokens(side)
            .filter { it !in HEADING_MATCH_GENERIC_STOPWORDS && it.length >= 3 }
            .toSet()
        if (baseTerms.isEmpty()) continue
        val searchTerms = expandEntityAliasTokens(baseTerms)
        val ranked = contentChunks
            .map { chunk ->
                val corpus = chunk.text.lowercase()
                val score = searchTerms.count { term ->
                    term.length >= 3 && corpus.contains(term)
                }
                chunk to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(maxPerSide)
        picked.addAll(ranked.map { it.first })
    }
    return picked.toList()
}

/** B4 — negative/absence asks need outline inventory, not meta tail sampling. */
internal fun isAbsenceInventoryQuery(query: String): Boolean {
    val lower = query.lowercase().trim()
    if (lower.isEmpty()) return false
    if (!Regex("(?i)\\b(not|except|without|excluding)\\b").containsMatchIn(lower)) return false
    return Regex("(?i)(discuss|mention|cover|describe|talk|include|present|listed)").containsMatchIn(lower)
}

internal fun absenceInventoryQueryExpansion(): String =
    "outline sections chapters headings topics subjects inventory table of contents"

internal fun absenceInventoryOutlineChunks(all: List<RagChunkEntity>): List<RagChunkEntity> =
    all.filter {
        it.chunkIndex == OUTLINE_CHUNK_INDEX ||
            it.chunkIndex == STRUCTURE_REGISTRY_CHUNK_INDEX
    }

/** B3 — main/major/key component or factor lists need span-shaped retrieval. */
internal fun isSetEnumerationQuery(query: String): Boolean {
    val lower = query.lowercase().trim()
    if (lower.isEmpty()) return false
    if (Regex("(?i)\\bwhat are the (main|major|key|primary)").containsMatchIn(lower)) return true
    if (
        Regex("(?i)\\b(main|major|key|primary|principal)\\b").containsMatchIn(lower) &&
        Regex("(?i)\\b(components?|factors?|parts?|elements?|types?|influences?)\\b")
            .containsMatchIn(lower)
    ) {
        return true
    }
    return false
}

/** B5 — filename token match: exact only (no `earth` inside `dynamicearth`). */
internal fun filenameTokenMatchesQuery(queryToken: String, fileToken: String): Boolean =
    queryToken == fileToken
