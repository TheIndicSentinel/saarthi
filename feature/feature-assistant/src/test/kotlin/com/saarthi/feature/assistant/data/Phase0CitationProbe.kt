package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels

/**
 * Phase 0 — citation measurement helpers for replay matrix (test-only).
 * Simulates post-gen Sources footer without an on-device LLM.
 */
internal data class Phase0CitationProbeResult(
    val shouldCitePreGen: Boolean,
    val footerPresent: Boolean,
    val overlapDrop: Boolean,
    val sourceLineCount: Int,
    val markedLocationLines: Int,
    val unknownLocationLines: Int,
)

internal fun inferPhase0SearchPath(
    query: String,
    retrieved: List<RetrievedChunk>,
    isFollowUp: Boolean = false,
): Pair<RagSearchPath, String?> {
    if (retrieved.isEmpty()) return RagSearchPath.empty to null
    val metaReason = effectiveMetaRouteReason(query, isFollowUp)
    if (metaReason != null && !isFollowUp && !bypassMetaForSubstanceQuery(query)) {
        val organic = retrieved.filter { it.chunkIndex >= 0 && it.score > 0.5 }
        if (organic.isEmpty()) return RagSearchPath.meta to metaReason
    }
    if (retrieved.any { it.isStructuralAnchor() }) return RagSearchPath.structural to metaReason
    return RagSearchPath.bm25 to metaReason
}

internal fun phase0CitationProbeLogLine(
    caseId: String,
    path: RagSearchPath,
    scope: String,
    shouldCite: Boolean,
    strongMatch: Boolean,
    probe: Phase0CitationProbeResult,
): String = buildString {
    append("phase0 case=$caseId path=${path.name} scope=$scope")
    append(" shouldCite=$shouldCite strongMatch=$strongMatch")
    append(" footer=${probe.footerPresent} overlapDrop=${probe.overlapDrop}")
    append(" srcLines=${probe.sourceLineCount}")
    append(" locMarked=${probe.markedLocationLines} locUnknown=${probe.unknownLocationLines}")
}

internal fun probePhase0Citation(
    metrics: GoldenPromptMetrics,
    query: String,
    attachmentsThisTurn: Boolean,
    syntheticAnswerBody: String,
): Phase0CitationProbeResult {
    val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()
    val unknownLabel = labels.locationUnknown
    val shouldCite = shouldAttachDeterministicSources(
        turnMode = metrics.turnMode,
        ragBlockChars = metrics.ragChars,
        retrieved = metrics.retrieved,
        query = query,
        attachmentsThisTurn = attachmentsThisTurn,
    )
  val citable = if (shouldCite) {
        citableRetrievalChunks(metrics.retrieved, query)
    } else {
        emptyList()
    }
    val outline = metrics.retrieved
        .filter { it.chunkIndex < 0 }
        .associate { it.docName to it.text }
    val overlapActive = shouldFilterSourcesByClaimOverlap(query, metrics.turnMode)
    val pairingBody = answerBodyForClaimOverlap(syntheticAnswerBody, metrics.turnMode)
    val overlapChunks = if (overlapActive) {
        filterChunksByClaimOverlap(citable, pairingBody, query = query)
    } else {
        citable
    }
    val overlapDrop = overlapActive && citable.isNotEmpty() && overlapChunks.isEmpty()
    val out = applyDeterministicSourcesFooter(
        modelText = syntheticAnswerBody,
        chunks = citable,
        outlineByDocName = outline,
        labels = labels,
        claimOverlapQuery = query,
        claimOverlapTurnMode = metrics.turnMode,
    )
    val footerPresent = out.contains(labels.sourcesHeader, ignoreCase = true)
    val footerTail = if (footerPresent) {
        out.substringAfter(labels.sourcesHeader, "").trim()
    } else {
        ""
    }
    val lines = footerTail.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val unknownLocationLines = lines.count { it.contains(unknownLabel, ignoreCase = true) }
    val markedLocationLines = lines.size - unknownLocationLines
    return Phase0CitationProbeResult(
        shouldCitePreGen = shouldCite,
        footerPresent = footerPresent,
        overlapDrop = overlapDrop,
        sourceLineCount = lines.size,
        markedLocationLines = markedLocationLines,
        unknownLocationLines = unknownLocationLines,
    )
}

internal fun syntheticAnswerFromRetrieval(retrieved: List<RetrievedChunk>, maxChunks: Int = 3): String =
    retrieved
        .filter { it.chunkIndex >= 0 }
        .take(maxChunks)
        .joinToString(" ") { it.text.take(120) }
        .ifBlank { "Summary based on the guide excerpts." }
