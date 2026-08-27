package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.CitationDisplayLabels
import com.saarthi.core.inference.prompt.SystemPromptProvider
import com.saarthi.feature.assistant.domain.AttachedFile

/** Minimum char budget to attempt a normal multi-chunk RAG block. */
internal const val MIN_GROUNDED_RAG_CHAR_BUDGET = 400

/** Wave 1 — when excerpts cannot fit, ask the user to retry instead of "provide excerpts". */
internal fun groundedDeliveryRetryInstruction(userMessage: String): String =
    "Reply with exactly this sentence and nothing else: " +
        "\"I've loaded your document but couldn't fit excerpts in this turn — please send your question again.\"\n\n" +
        "User: $userMessage\nSaarthi:"


/** Below this, only ultra-compact grounded assembly is attempted. */
internal const val TIGHT_GROUNDED_RAG_CHAR_BUDGET = 200

internal data class RagPromptAssemblyResult(
    val block: String,
    /** True when [forceGroundedDelivery] was set but no excerpt bytes could be placed. */
    val groundedDeliveryFailed: Boolean = false,
)

/**
 * Wave 1 — build the RAG excerpt block with a fallback ladder when the caller
 * requires grounded delivery ([forceGroundedDelivery]) for a non-empty retrieval.
 */
internal fun assembleRagPromptBlock(
    retrieved: List<RetrievedChunk>,
    unreadableThisTurn: List<AttachedFile>,
    tier: SystemPromptProvider.ModelTier,
    charBudget: Int,
    sessionDocs: List<SessionRagDocument> = emptyList(),
    newThisTurnNames: List<String> = emptyList(),
    answerShape: RagAnswerShape = RagAnswerShape.NARROW_QA,
    tabularAmount: Boolean = false,
    unattachedExternal: UnattachedExternalDecision = UnattachedExternalDecision(active = false),
    citationLabels: CitationDisplayLabels,
    forceGroundedDelivery: Boolean = false,
    turnMode: RagTurnMode = RagTurnMode.DOCUMENT_GROUNDED,
    ragQuery: String = "",
    attachmentsThisTurn: Boolean = false,
): RagPromptAssemblyResult {
    if (retrieved.isEmpty() && unreadableThisTurn.isEmpty()) {
        return RagPromptAssemblyResult("")
    }
    if (!forceGroundedDelivery && charBudget < TIGHT_GROUNDED_RAG_CHAR_BUDGET) {
        return RagPromptAssemblyResult("")
    }

    val compact = tier == SystemPromptProvider.ModelTier.COMPACT
    val strongMatch = shouldUseStrongMatchPromptRules(
        retrieved = retrieved,
        query = ragQuery,
        turnMode = turnMode,
        attachmentsThisTurn = attachmentsThisTurn,
    )

    val attempts = listOf(
        AssemblyAttempt(fullRules = true, includeShape = true, includeManifest = true, chunkStrategy = ChunkPickStrategy.INTERLEAVE),
        AssemblyAttempt(fullRules = true, includeShape = false, includeManifest = true, chunkStrategy = ChunkPickStrategy.INTERLEAVE),
        AssemblyAttempt(fullRules = false, includeShape = false, includeManifest = false, chunkStrategy = ChunkPickStrategy.SMALLEST_CONTENT_FIRST),
        AssemblyAttempt(fullRules = false, includeShape = false, includeManifest = false, chunkStrategy = ChunkPickStrategy.SINGLE_SMALLEST),
    )

    for (attempt in attempts) {
        val block = tryAssemble(
            retrieved = retrieved,
            unreadableThisTurn = unreadableThisTurn,
            charBudget = charBudget,
            compact = compact,
            strongMatch = strongMatch,
            sessionDocs = sessionDocs,
            newThisTurnNames = newThisTurnNames,
            answerShape = answerShape,
            tabularAmount = tabularAmount,
            unattachedExternal = unattachedExternal,
            citationLabels = citationLabels,
            attempt = attempt,
            turnMode = turnMode,
        )
        if (block.isNotEmpty()) return RagPromptAssemblyResult(block)
    }

    if (forceGroundedDelivery && retrieved.isNotEmpty()) {
        return RagPromptAssemblyResult(block = "", groundedDeliveryFailed = true)
    }
    return RagPromptAssemblyResult("")
}

private enum class ChunkPickStrategy {
    INTERLEAVE,
    SMALLEST_CONTENT_FIRST,
    SINGLE_SMALLEST,
}

private data class AssemblyAttempt(
    val fullRules: Boolean,
    val includeShape: Boolean,
    val includeManifest: Boolean,
    val chunkStrategy: ChunkPickStrategy,
)

private fun tryAssemble(
    retrieved: List<RetrievedChunk>,
    unreadableThisTurn: List<AttachedFile>,
    charBudget: Int,
    compact: Boolean,
    strongMatch: Boolean,
    sessionDocs: List<SessionRagDocument>,
    newThisTurnNames: List<String>,
    answerShape: RagAnswerShape,
    tabularAmount: Boolean,
    unattachedExternal: UnattachedExternalDecision,
    citationLabels: CitationDisplayLabels,
    attempt: AssemblyAttempt,
    turnMode: RagTurnMode,
): String {
    val shapeInstruction = if (attempt.includeShape) {
        ragAnswerShapeInstruction(
            answerShape,
            compact = compact,
            tabularAmount = tabularAmount,
            unattachedExternal = unattachedExternal,
        )
    } else {
        ""
    }

    val rulesHeader = when {
        turnMode == RagTurnMode.MIXED -> ragMixedModeRules(compact = compact, labels = citationLabels)
        attempt.fullRules -> ragCitationRules(
            compact = compact,
            strongMatch = strongMatch,
            labels = citationLabels,
            blockExternalRegimes = true,
        )
        else -> ragCitationRulesMinimal(citationLabels)
    }

    val unreadableBlock = if (unreadableThisTurn.isNotEmpty()) {
        buildString {
            appendLine(UNREADABLE_FILES_INTRO)
            unreadableThisTurn.forEach { f ->
                val why = f.error ?: "unsupported format — Saarthi cannot read binary files yet"
                appendLine("  - ${f.name}: $why")
            }
        }.trimEnd() + "\n\n"
    } else {
        ""
    }

    val outlineByDocName = retrieved
        .filter { it.chunkIndex < 0 }
        .associate { it.docName to it.text }

    val manifestLine = if (attempt.includeManifest) {
        sessionManifestLine(
            sessionDocs.map { doc ->
                val outline = outlineByDocName[doc.name]
                val bodyHint = retrieved.firstOrNull {
                    it.docName == doc.name && it.chunkIndex >= 0
                }?.text
                val charEst = retrieved.filter { it.docName == doc.name }.sumOf { it.text.length }
                displayCitationDocName(
                    doc.name,
                    outline,
                    bodyHint,
                    charEst.takeIf { it > 0 },
                    citationLabels,
                )
            },
        )
    } else {
        ""
    }
    val newFilesLine = if (attempt.includeManifest) newFilesThisTurnNotice(newThisTurnNames) else ""

    var remaining = charBudget - rulesHeader.length - shapeInstruction.length -
        unreadableBlock.length - manifestLine.length - newFilesLine.length

    val ordered = when (attempt.chunkStrategy) {
        ChunkPickStrategy.INTERLEAVE -> interleaveExcerptsByDoc(retrieved)
        ChunkPickStrategy.SMALLEST_CONTENT_FIRST -> {
            val body = retrieved.filter { it.chunkIndex >= 0 }.sortedBy { it.text.length }
            val outline = retrieved.filter { it.chunkIndex < 0 }.sortedBy { it.text.length }
            body + outline
        }
        ChunkPickStrategy.SINGLE_SMALLEST -> {
            val smallest = retrieved
                .filter { it.chunkIndex >= 0 }
                .minByOrNull { it.text.length }
                ?: retrieved.minByOrNull { it.text.length }
            listOfNotNull(smallest)
        }
    }

    val chunksBlock = buildString {
        for ((i, chunk) in ordered.withIndex()) {
            val text = chunk.text.trim()
            if (text.isEmpty()) continue
            val header = formatExcerptHeader(
                i + 1,
                chunk.docName,
                text,
                chunk.chunkIndex,
                outlineByDocName[chunk.docName],
                citationLabels,
            )
            val total = header.length + text.length + 2
            if (total > remaining) {
                if (attempt.chunkStrategy == ChunkPickStrategy.SINGLE_SMALLEST) continue
                break
            }
            append(header)
            append(text)
            append("\n\n")
            remaining -= total
            if (attempt.chunkStrategy == ChunkPickStrategy.SINGLE_SMALLEST) break
        }
    }

    if (chunksBlock.isEmpty() && unreadableBlock.isEmpty()) return ""
    return (rulesHeader + shapeInstruction + manifestLine + newFilesLine + chunksBlock + unreadableBlock).trimEnd()
}
