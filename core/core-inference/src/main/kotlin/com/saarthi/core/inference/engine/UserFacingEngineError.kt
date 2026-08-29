package com.saarthi.core.inference.engine

/**
 * Plain-language engine errors for the UI. Native / JNI failures often
 * include sandbox paths (`/data/user/0/…`), fd paths, or exception class
 * names — those must not land in onboarding copy or a chat bubble.
 *
 * Logs (Timber / [com.saarthi.core.inference.DebugLogger]) keep the raw
 * detail; this layer is only what the user sees.
 */

const val USER_FACING_MODEL_MISSING =
    "The AI model isn't on this phone. Download it again from the list in the app."

const val USER_FACING_LOAD_GENERIC =
    "Could not load the AI model. Please try again, or pick a different model."

const val USER_FACING_GENERATION_FAILED =
    "Couldn't finish that answer. Please try again."

const val USER_FACING_ENGINE_NOT_READY =
    "The AI is not ready yet. Wait a moment and try again."

private val ABSOLUTE_PATH = Regex("""(?:^|[\s:(])(/[\w._-]+){2,}""")
private val CLASS_NAME_DETAIL =
    Regex("""\b(?:[a-z][a-z0-9_]*\.)*[A-Z][\w$]*(Exception|Error|Throwable)\b""")

fun containsFilesystemPath(message: String): Boolean {
    val lower = message.lowercase()
    if (lower.contains("file://") || lower.contains("content://")) return true
    return ABSOLUTE_PATH.containsMatchIn(message)
}

fun looksLikeInternalEngineDetail(message: String): Boolean {
    val msg = message.trim()
    if (msg.isEmpty() || msg.equals("null", ignoreCase = true)) return true
    val lower = msg.lowercase()
    if (containsFilesystemPath(msg)) return true
    if (lower.contains("litert")) return true
    if (lower.contains("jni")) return true
    if (lower.contains("openfiledescriptor")) return true
    if (lower.contains("models folder")) return true
    if (CLASS_NAME_DETAIL.containsMatchIn(msg)) return true
    return false
}

fun userFacingLoadError(rawMessage: String?): String =
    userFacingEngineMessage(rawMessage, USER_FACING_LOAD_GENERIC)

fun userFacingGenerationError(rawMessage: String?): String {
    val stripped = rawMessage.orEmpty()
        .trim()
        .removePrefix("Generation failed:")
        .trim()
    return userFacingEngineMessage(stripped, USER_FACING_GENERATION_FAILED)
}

internal fun userFacingEngineMessage(rawMessage: String?, fallback: String): String {
    val msg = rawMessage?.trim().orEmpty()
    if (msg.isEmpty() || looksLikeInternalEngineDetail(msg)) return fallback
    return msg
}
