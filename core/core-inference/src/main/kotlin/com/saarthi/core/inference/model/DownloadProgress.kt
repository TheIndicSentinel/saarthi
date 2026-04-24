package com.saarthi.core.inference.model

sealed class DownloadProgress {
    data object Idle : DownloadProgress()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgress() {
        val percent: Int get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
    }
    data class Paused(val reason: String) : DownloadProgress()
    data class Completed(val filePath: String) : DownloadProgress()
    data class Failed(val reason: String) : DownloadProgress()
}
