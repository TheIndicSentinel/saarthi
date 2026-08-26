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
    ftsPrefilter: Boolean = false,
): String {
    val extra = buildString {
        append(" named=$named equal=${if (equalSlots) 1 else 0}")
        append(" whichFile=${if (whichFile) 1 else 0} thisDoc=${if (thisDocument) 1 else 0}")
        append(" followUp=${if (followUp) 1 else 0}")
        if (ftsPrefilter) append(" fts=1")
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

/** First ~200c of raw model text, whitespace-collapsed. Debug APKs only. */
internal const val RAG_RAW_PREVIEW_CHARS = 200

internal fun ragRawModelPreview(raw: String, maxChars: Int = RAG_RAW_PREVIEW_CHARS): String =
    raw.replace(Regex("\\s+"), " ").trim().take(maxChars)

/**
 * Prompt-side correlators without document text: recap size and each
 * retrieved URI's length (full content:// paths stay off the log).
 */
internal fun ragPromptObsLogLine(priorTurnsChars: Int, uriLens: List<Int>): String {
    val lens = if (uriLens.isEmpty()) "-" else uriLens.joinToString(",")
    return "priorTurnsChars=$priorTurnsChars promptDocs=${uriLens.size} uriLens=$lens"
}

/**
 * End-of-turn line so a Downloads log can show whether the model deflected
 * without re-running. [preview] is the collapsed raw prefix (debug only);
 * release callers pass null.
 */
internal fun ragGenerationLogLine(
    rawChars: Int,
    priorTurnsChars: Int,
    uriLens: List<Int>,
    preview: String? = null,
): String = buildString {
    append("gen rawChars=$rawChars ")
    append(ragPromptObsLogLine(priorTurnsChars, uriLens))
    if (!preview.isNullOrEmpty()) {
        append(" preview=")
        append(preview)
    }
}

/** R24 — FTS5 only when a session is actually slow or huge. Never schema-bump speculatively. */
internal const val FTS5_CHUNK_THRESHOLD = 500
internal const val FTS5_SEARCH_MS_THRESHOLD = 50L

internal fun fts5IsWarranted(chunkCount: Int, searchMs: Long): Boolean =
    chunkCount > FTS5_CHUNK_THRESHOLD || searchMs > FTS5_SEARCH_MS_THRESHOLD

internal fun ragFts5CandidateLogLine(chunkCount: Int, searchMs: Long): String =
    "fts5-candidate chunks=$chunkCount searchMs=$searchMs"

internal fun logRag(line: String) {
    DebugLogger.log("RAG", line)
    Timber.d("RAG: $line")
}
