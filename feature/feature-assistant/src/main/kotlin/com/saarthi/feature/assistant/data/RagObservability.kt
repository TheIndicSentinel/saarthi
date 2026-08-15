package com.saarthi.feature.assistant.data

import com.saarthi.core.inference.DebugLogger
import timber.log.Timber

/** How this turn filled RAG slots. Logged as lowercase path=… */
internal enum class RagSearchPath { empty, bm25, meta, structural }

internal fun ragSearchLogLine(
    docCount: Int,
    boostCount: Int,
    path: RagSearchPath,
    hitCount: Int,
    queryLen: Int,
    searchMs: Long,
): String =
    "docs=$docCount boost=$boostCount path=${path.name} hits=$hitCount queryLen=$queryLen searchMs=$searchMs"

internal fun ragChunkLogLine(
    index1Based: Int,
    nameLen: Int,
    chunkIndex: Int,
    page: String?,
    score: Double,
): String {
    val ref = if (chunkIndex < 0) "outline" else "part ${chunkIndex + 1}"
    val pages = page?.let { " · $it" } ?: ""
    return "  [$index1Based] nameLen=$nameLen · $ref$pages  score=${"%.2f".format(score)}"
}

internal fun ragIndexLogLine(
    chunkCount: Int,
    chars: Int,
    hasOutline: Boolean,
    nameLen: Int,
    sessionIdLen: Int,
    indexMs: Long,
): String =
    "indexed $chunkCount chunks (${chars}c, outline=$hasOutline) nameLen=$nameLen sessionIdLen=$sessionIdLen indexMs=$indexMs"

internal fun ragIndexFailLogLine(nameLen: Int, exceptionName: String): String =
    "index failed nameLen=$nameLen ex=$exceptionName"

internal fun logRag(line: String) {
    DebugLogger.log("RAG", line)
    Timber.d("RAG: $line")
}
