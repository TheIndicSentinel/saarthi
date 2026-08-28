package com.saarthi.core.inference

/**
 * HTTP 416 (Range Not Satisfiable) on a resume: decide whether the local
 * tmp file is safe to promote to "complete" or must be discarded.
 *
 * A 416 means our Range start is at or past what the server currently
 * has. That is *not* the same as matching the catalog's [fileSizeBytes] —
 * HuggingFace can serve a shorter object than the pinned catalog size
 * (revision drift, truncated upload). Accepting on 416 alone then failed
 * later in [ModelDownloadManager.isFileComplete] and left onboarding
 * looking finished with a file too small to load.
 *
 * The catalog comparison uses the same 85% bar as
 * [ModelDownloadManager.isFileComplete] with `trustOS = true` (a transfer
 * the OS/server reported as done).
 */
internal object DownloadRangePolicy {

    const val MIN_COMPLETE_BYTES = 1_000_000L
    const val TRUSTED_SIZE_FRACTION = 0.85

    fun shouldAcceptAsComplete(
        localBytes: Long,
        serverTotalBytes: Long?,
        catalogFileSizeBytes: Long?,
    ): Boolean {
        if (localBytes < MIN_COMPLETE_BYTES) return false
        if (serverTotalBytes != null && localBytes < serverTotalBytes) return false
        val catalog = catalogFileSizeBytes?.takeIf { it > 0L } ?: return true
        return localBytes >= (catalog * TRUSTED_SIZE_FRACTION).toLong()
    }
}
