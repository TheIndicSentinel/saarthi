package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity

/**
 * Wave 1 P5 — retrieval confidence for chapter-typed queries.
 *
 * When the user names Chapter VI/VII/6, verify the assembled excerpts contain
 * that chapter's body header. On mismatch, widen/re-resolve via chapter-span;
 * never ship Chapter V body for a Chapter VI ask.
 */

/** Synthetic retrieval hint (structure count hints use the same index). */
internal const val RETRIEVAL_HINT_CHUNK_INDEX = -2

/** Query names a specific chapter (not structure count/list). */
internal fun isChapterTypedQuery(query: String): Boolean {
    if (isStructureCountQuery(query) || isStructureListQuery(query)) return false
    return extractSectionRefs(query).any { it.kind == "chapter" }
}

internal fun chapterRefFromQuery(query: String): String? =
    extractChapterSpanRef(query)
        ?: extractSectionRefs(query).firstOrNull { it.kind == "chapter" }?.token

/** True when a body chunk has a title-line header for [requestedNum]. */
internal fun hasRequestedChapterTitleLine(
    retrieved: List<RetrievedChunk>,
    requestedNum: Int,
): Boolean {
    val aliases = chapterIdAliases(intToRoman(requestedNum))
    return retrieved.any { chunk ->
        chunk.chunkIndex >= 0 && chunk.text.lines().any { line ->
            chapterLineMatchTier(line.trim(), aliases)?.let { it <= 1 } != null
        }
    }
}

/** Body chunk asserts a different chapter title-line (tier ≤ 1). */
internal fun hasConflictingChapterTitleLine(
    chunk: RetrievedChunk,
    requestedNum: Int,
): Boolean {
    if (chunk.chunkIndex < 0) return false
    return chunk.text.lines().any { line ->
        val trimmed = line.trim()
        val num = extractChapterNumberFromLine(trimmed) ?: return@any false
        num != requestedNum && chapterHeaderMatchTier(trimmed)?.let { it <= 1 } == true
    }
}

internal fun stripConflictingChapterBody(
    retrieved: List<RetrievedChunk>,
    requestedNum: Int,
): List<RetrievedChunk> {
    val meta = retrieved.filter { it.chunkIndex < 0 }
    val body = retrieved.filter { it.chunkIndex >= 0 }
        .filterNot { hasConflictingChapterTitleLine(it, requestedNum) }
    return meta + body
}

internal fun buildChapterMissHint(chapterRef: String): String =
    "Chapter identity check: indexed excerpts do not contain a body header for " +
        "Chapter ${chapterRef.uppercase()}. Do not answer from a different chapter; " +
        "state that this chapter was not found in the attached document."

internal fun chapterSpanToRetrieved(span: List<RagChunkEntity>): List<RetrievedChunk> =
    span.map { entity ->
        entity.toRetrievedChunk(0.0, StructuralAnchorKind.CHAPTER_SPAN)
    }

internal fun retryChapterSpanRetrieved(
    contentChunks: List<RagChunkEntity>,
    chapterRef: String,
    maxChunks: Int,
    relaxed: Boolean,
): List<RetrievedChunk> {
    var bestSpan: List<RagChunkEntity>? = null
    var bestTier = Int.MAX_VALUE
    for ((_, docChunks) in contentChunks.groupBy { it.docUri }) {
        val sorted = docChunks.sortedBy { it.chunkIndex }
        val window = if (relaxed) {
            resolveChapterSpanWindowRelaxed(sorted, chapterRef, maxChunks)
        } else {
            resolveChapterSpanWindow(sorted, chapterRef, maxChunks)
        } ?: continue
        val span = sorted.subList(window.startChunkIndex, window.endChunkIndexExclusive)
        if (bestSpan == null || window.matchedTier < bestTier) {
            bestTier = window.matchedTier
            bestSpan = span
        }
    }
    return bestSpan?.let { chapterSpanToRetrieved(it) }.orEmpty()
}

internal fun mergeChapterSpanFirst(
    span: List<RetrievedChunk>,
    rest: List<RetrievedChunk>,
): List<RetrievedChunk> {
    val spanKeys = span.map { it.docUri to it.chunkIndex }.toSet()
    val tail = rest.filter { r ->
        r.chunkIndex < 0 || !spanKeys.contains(r.docUri to r.chunkIndex)
    }
    return span + tail
}

/**
 * Post-assembly gate: chapter-typed queries must carry the requested chapter span
 * or an explicit miss hint — not a different chapter's body.
 */
internal fun applyChapterRetrievalConfidence(
    query: String,
    retrieved: List<RetrievedChunk>,
    contentChunks: List<RagChunkEntity>,
    expandedSpanChunks: Int,
): List<RetrievedChunk> {
    if (!isChapterTypedQuery(query)) return retrieved
    val chapterRef = chapterRefFromQuery(query) ?: return retrieved
    val requestedNum = chapterNumericId(chapterRef) ?: return retrieved

    if (hasRequestedChapterTitleLine(retrieved, requestedNum)) {
        return retrieved
    }

    logRag("chapter-confidence miss id=${chapterRef.uppercase()} retry")

    val widened = retryChapterSpanRetrieved(
        contentChunks,
        chapterRef,
        expandedSpanChunks,
        relaxed = false,
    )
    if (widened.isNotEmpty()) {
        logRag("chapter-confidence retry ok chunks=${widened.size}")
        return stripConflictingChapterBody(
            mergeChapterSpanFirst(widened, retrieved),
            requestedNum,
        )
    }

    val relaxed = retryChapterSpanRetrieved(
        contentChunks,
        chapterRef,
        expandedSpanChunks,
        relaxed = true,
    )
    if (relaxed.isNotEmpty()) {
        logRag("chapter-confidence relaxed ok chunks=${relaxed.size}")
        return stripConflictingChapterBody(
            mergeChapterSpanFirst(relaxed, retrieved),
            requestedNum,
        )
    }

    logRag("chapter-confidence unresolved id=${chapterRef.uppercase()} strip-wrong")
    val stripped = stripConflictingChapterBody(retrieved, requestedNum)
    val doc = contentChunks.firstOrNull() ?: return stripped
    val hintChunk = RetrievedChunk(
        text = buildChapterMissHint(chapterRef),
        docName = doc.docName,
        score = 100.0,
        chunkIndex = RETRIEVAL_HINT_CHUNK_INDEX,
        docUri = doc.docUri,
    )
    return listOf(hintChunk) + stripped
}
