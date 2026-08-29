package com.saarthi.core.i18n

/**
 * Maps stored download-failure reasons (English, often with HTTP codes) to
 * the copy the user should see. Keep [DownloadFailureStore] / emitFailed
 * strings as-is so logs and tests stay stable; only the UI goes through here.
 */
fun userFacingDownloadFailure(
    reason: String?,
    strings: OnboardingStrings = OnboardingStrings(),
): String {
    val raw = reason?.trim().orEmpty()
    if (raw.isEmpty()) return strings.failGeneric
    val lower = raw.lowercase()
    return when {
        TOKEN_HINT.containsMatchIn(lower) -> strings.failNeedsToken
        lower.contains("404") || lower.contains("not found") -> strings.failNotFound
        STORAGE_HINT.containsMatchIn(lower) -> strings.failNoStorage
        CORRUPT_HINT.containsMatchIn(lower) -> strings.failCorrupt
        lower.contains("could not start the download service") -> strings.failService
        NETWORK_HINT.containsMatchIn(lower) -> strings.failNoNetwork
        else -> strings.failGeneric
    }
}

private val TOKEN_HINT = Regex("token|access denied|http 401|http 403")
private val STORAGE_HINT = Regex("storage|enospc|no space")
private val CORRUPT_HINT = Regex("integrity|checksum|http 416|file size does not match")
private val NETWORK_HINT = Regex("http |unable to resolve|failed to connect|timeout|unknownhost|socket|ssl|connection")
