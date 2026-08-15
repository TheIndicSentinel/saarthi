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
    named: Int = 0,
    equalSlots: Boolean = false,
    whichFile: Boolean = false,
    thisDocument: Boolean = false,
    followUp: Boolean = false,
    metaReason: String? = null,
    headingChunks: Int = 0,
): String {
    val extra = buildString {
        append(" named=$named equal=${if (equalSlots) 1 else 0}")
        append(" whichFile=${if (whichFile) 1 else 0} thisDoc=${if (thisDocument) 1 else 0}")
        append(" followUp=${if (followUp) 1 else 0}")
        if (!metaReason.isNullOrBlank()) append(" meta=$metaReason")
        if (headingChunks > 0) append(" headingChunks=$headingChunks")
    }
    return "docs=$docCount boost=$boostCount path=${path.name} hits=$hitCount queryLen=$queryLen searchMs=$searchMs$extra"
}

/**
 * True for a debug/beta APK: log short filenames so a Downloads log can
 * show which file filled each slot. Release stays nameLen-only.
 */
internal fun ragLogDocNames(): Boolean =
    com.saarthi.core.inference.BuildConfig.DEBUG ||
        com.saarthi.core.inference.BuildConfig.PUBLIC_DEBUG_LOG

internal fun ragChunkLogLine(
    index1Based: Int,
    nameLen: Int,
    chunkIndex: Int,
    page: String?,
    score: Double,
    displayName: String? = null,
): String {
    val ref = if (chunkIndex < 0) "outline" else "part ${chunkIndex + 1}"
    val pages = page?.let { " · $it" } ?: ""
    val label = displayName?.takeIf { it.isNotBlank() } ?: "nameLen=$nameLen"
    return "  [$index1Based] $label · $ref$pages  score=${"%.2f".format(score)}"
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
