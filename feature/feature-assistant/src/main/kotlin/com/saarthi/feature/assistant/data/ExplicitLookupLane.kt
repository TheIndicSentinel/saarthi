package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity

/**
 * Phase 3.1 — generic explicit-lookup anchors and lexical-first retrieval support.
 */
internal data class ExplicitLookupAnchors(
    val sectionRefs: List<SectionRef> = emptyList(),
    val amountSignatures: Set<String> = emptySet(),
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val dates: List<String> = emptyList(),
    val namedEntities: List<String> = emptyList(),
) {
    fun hasExtractedAnchors(): Boolean =
        sectionRefs.isNotEmpty() ||
            amountSignatures.isNotEmpty() ||
            emails.isNotEmpty() ||
            phones.isNotEmpty() ||
            dates.isNotEmpty() ||
            namedEntities.isNotEmpty()
}

private val EMAIL_RX = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
private val PHONE_RX = Regex("(?i)(?:\\+91[- ]?)?[6-9]\\d{9}")
private val DATE_RX = Regex(
    "(?i)\\b(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})",
)
private val CAPITALIZED_ENTITY_RX = Regex("\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3}")

internal fun extractExplicitLookupAnchors(query: String): ExplicitLookupAnchors {
    val sectionRefs = extractSectionRefs(query)
    val amountSignatures = extractMonetarySignatures(query)
    val emails = EMAIL_RX.findAll(query).map { it.value.lowercase() }.distinct().toList()
    val phones = PHONE_RX.findAll(query).map { it.value }.distinct().toList()
    val dates = DATE_RX.findAll(query).map { it.value }.distinct().toList()
    val namedEntities = CAPITALIZED_ENTITY_RX.findAll(query)
        .map { it.value.trim() }
        .filter { entity ->
            entity.length >= 4 &&
                !entity.equals("Section", ignoreCase = true) &&
                !entity.equals("Chapter", ignoreCase = true)
        }
        .distinct()
        .take(4)
        .toList()
    return ExplicitLookupAnchors(
        sectionRefs = sectionRefs,
        amountSignatures = amountSignatures,
        emails = emails,
        phones = phones,
        dates = dates,
        namedEntities = namedEntities,
    )
}

/** High-weight tokens appended to BM25 query for explicit lookups. */
internal fun explicitLookupLexicalExpansion(query: String): String {
    val anchors = extractExplicitLookupAnchors(query)
    if (!anchors.hasExtractedAnchors()) return ""
    val parts = mutableListOf<String>()
    anchors.sectionRefs.forEach { ref ->
        when (ref.kind) {
            "section" -> parts += "section ${ref.token} sec ${ref.token} §${ref.token}"
            "chapter" -> parts += "chapter ${ref.token} CHAPTER ${ref.token.uppercase()}"
            "schedule" -> parts += "schedule THE SCHEDULE"
        }
    }
    anchors.amountSignatures.forEach { sig ->
        when {
            sig.endsWith("crore") -> parts += "₹${sig.removeSuffix("crore")} crore"
            sig.endsWith("lakh") -> parts += "₹${sig.removeSuffix("lakh")} lakh"
            sig.startsWith("rs") -> parts += "₹${sig.removePrefix("rs")}"
        }
    }
    parts += anchors.emails
    parts += anchors.phones
    parts += anchors.dates
    parts += anchors.namedEntities
    return parts.joinToString(" ")
}

internal fun corpusTextFromChunks(chunks: List<RetrievedChunk>): String =
    chunks.joinToString("\n") { it.text }

internal fun corpusTextFromEntities(entities: List<RagChunkEntity>): String =
    entities.filter { it.chunkIndex >= 0 }.joinToString("\n") { it.text }

internal fun hasExplicitLookupAnchorSupport(
    anchors: ExplicitLookupAnchors,
    corpus: String,
): Boolean {
    if (!anchors.hasExtractedAnchors()) return true
    if (anchors.sectionRefs.isNotEmpty()) {
        val sectionHit = anchors.sectionRefs.any { ref ->
            when (ref.kind) {
                "section" -> isSectionNumberGrounded(ref.token, corpus)
                "chapter" -> corpusContainsChapterRef(ref.token, corpus)
                "schedule" -> Regex("(?im)^\\s*THE SCHEDULE\\b").containsMatchIn(corpus)
                else -> false
            }
        }
        if (!sectionHit) return false
    }
    if (anchors.amountSignatures.isNotEmpty()) {
        val amountHit = anchors.amountSignatures.any { corpusContainsMonetarySignature(it, corpus) }
        if (!amountHit) return false
    }
    if (anchors.emails.isNotEmpty() && anchors.emails.none { corpus.contains(it, ignoreCase = true) }) {
        return false
    }
    if (anchors.phones.isNotEmpty() && anchors.phones.none { corpus.contains(it) }) {
        return false
    }
    if (anchors.dates.isNotEmpty() && anchors.dates.none { corpus.contains(it) }) {
        return false
    }
    if (anchors.namedEntities.isNotEmpty()) {
        val entityHit = anchors.namedEntities.any { entity ->
            corpus.contains(entity, ignoreCase = true)
        }
        if (!entityHit) return false
    }
    return true
}

internal fun hasExplicitLookupAnchorSupportInRetrieval(
    query: String,
    retrieved: List<RetrievedChunk>,
): Boolean {
    val anchors = extractExplicitLookupAnchors(query)
    if (!anchors.hasExtractedAnchors()) return true
    return hasExplicitLookupAnchorSupport(anchors, corpusTextFromChunks(retrieved))
}

private fun corpusContainsChapterRef(token: String, corpus: String): Boolean {
    val aliases = chapterIdAliases(token.lowercase())
    return aliases.any { alias ->
        Regex("(?im)^\\s*CHAPTER\\s+$alias\\b").containsMatchIn(corpus) ||
            Regex("(?i)\\bCHAPTER\\s+$alias\\b").containsMatchIn(corpus)
    }
}
