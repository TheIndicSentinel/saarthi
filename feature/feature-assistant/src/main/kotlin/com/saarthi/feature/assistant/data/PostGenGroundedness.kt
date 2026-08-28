package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.CitationDisplayLabels

/**
 * Wave 6 P27 — post-generation groundedness for legal/tabular replies: amounts,
 * section numbers, and "shall" obligations must appear in retrieved excerpts.
 * Phase 3.4 — calibrated audit behind [POST_GEN_GROUNDEDNESS_AUDIT_ENABLED].
 */

/** Ship gate for post-gen audit — golden harness must be green before disabling. */
internal const val POST_GEN_GROUNDEDNESS_AUDIT_ENABLED = true

internal data class PostGenGroundednessAudit(
    val ungroundedAmounts: List<String> = emptyList(),
    val ungroundedSections: List<String> = emptyList(),
    val ungroundedShall: Boolean = false,
) {
    val isFullyGrounded: Boolean =
        ungroundedAmounts.isEmpty() && ungroundedSections.isEmpty() && !ungroundedShall
}

internal fun shouldAuditPostGenGroundedness(
    query: String?,
    turnMode: RagTurnMode?,
): Boolean {
    if (!POST_GEN_GROUNDEDNESS_AUDIT_ENABLED) return false
    if (query.isNullOrBlank()) return false
    if (turnMode == RagTurnMode.GENERAL_KNOWLEDGE || turnMode == RagTurnMode.PLAIN_CHAT) return false
    return shouldFilterSourcesByClaimOverlap(query, turnMode)
}

internal fun buildRetrievalCorpus(chunks: List<RetrievedChunk>): String =
    chunks.filter { it.chunkIndex >= 0 }.joinToString("\n") { it.text }

internal fun hasAuditableLegalClaims(text: String): Boolean =
    extractMonetarySignatures(text).isNotEmpty() ||
        extractSectionNumberClaims(text).isNotEmpty() ||
        SHALL_CLAIM_RX.containsMatchIn(text)

private val SHALL_CLAIM_RX = Regex("(?i)\\bshall\\b")

private val SECTION_CLAIM_RX = Regex(
    "(?i)(?:section|sec\\.?)\\s*(\\d{1,3})|§\\s*(\\d{1,3})",
)

internal fun extractSectionNumberClaims(text: String): Set<String> {
    val nums = LinkedHashSet<String>()
    SECTION_CLAIM_RX.findAll(text).forEach { match ->
        val num = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
        if (num != null) nums.add(num)
    }
    return nums
}

internal fun isMonetarySignatureGrounded(signature: String, corpus: String): Boolean =
    corpusContainsMonetarySignature(signature, corpus)

internal fun isSectionNumberGrounded(sectionNum: String, corpus: String): Boolean {
    val patterns = listOf(
        Regex("(?i)\\bsection\\s+$sectionNum\\b"),
        Regex("(?m)^\\s*$sectionNum\\.\\s"),
        Regex("(?i)§\\s*$sectionNum\\b"),
    )
    return patterns.any { it.containsMatchIn(corpus) }
}

internal fun hasUngroundedShallClaims(answer: String, corpus: String): Boolean {
    if (!SHALL_CLAIM_RX.containsMatchIn(answer)) return false
    if (!SHALL_CLAIM_RX.containsMatchIn(corpus)) return true
    val corpusTokens = significantTokensForClaimOverlap(corpus)
    return answer.split(Regex("(?<=[.!?])\\s+"))
        .filter { SHALL_CLAIM_RX.containsMatchIn(it) }
        .any { sentence ->
            val tokens = significantTokensForClaimOverlap(sentence)
            sharedSignificantTokenCount(tokens, corpusTokens) < CLAIM_OVERLAP_MIN_SHARED_TOKENS
        }
}

internal fun auditPostGenGroundedness(
    answerBody: String,
    corpus: String,
    query: String? = null,
): PostGenGroundednessAudit {
    if (answerBody.isBlank() || corpus.isBlank()) {
        return PostGenGroundednessAudit()
    }
    val amounts = extractMonetarySignatures(answerBody)
        .filterNot { isMonetarySignatureGrounded(it, corpus) }
    val sections = if (isChapterTypedQuery(query.orEmpty())) {
        emptySet()
    } else {
        extractSectionNumberClaims(answerBody)
            .filterNot { isSectionNumberGrounded(it, corpus) }
    }
    val shall = hasUngroundedShallClaims(answerBody, corpus)
    return PostGenGroundednessAudit(
        ungroundedAmounts = amounts.toList(),
        ungroundedSections = sections.toList(),
        ungroundedShall = shall,
    )
}

/**
 * When legal/tabular claims in the model answer are not supported by excerpts,
 * drop the Sources footer and append a localized verification caveat.
 */
internal fun applyPostGenGroundednessGuard(
    modelText: String,
    chunks: List<RetrievedChunk>,
    labels: CitationDisplayLabels,
    query: String?,
    turnMode: RagTurnMode?,
): String {
    if (!shouldAuditPostGenGroundedness(query, turnMode)) return modelText
    val body = modelText.trimEnd()
    if (!hasAuditableLegalClaims(body)) return modelText
    val audit = auditPostGenGroundedness(body, buildRetrievalCorpus(chunks), query)
    if (audit.isFullyGrounded) return modelText
    logRag(
        "post-gen-groundedness fail amounts=${audit.ungroundedAmounts} " +
            "sections=${audit.ungroundedSections} shall=${audit.ungroundedShall}",
    )
    val caveat = labels.groundednessCaveat
    return if (body.isBlank()) caveat else "$body\n\n$caveat"
}
