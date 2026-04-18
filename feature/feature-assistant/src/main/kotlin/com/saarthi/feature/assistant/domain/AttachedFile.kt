package com.saarthi.feature.assistant.domain

import android.net.Uri

data class AttachedFile(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val extractedText: String? = null,   // null if binary/image
    val isImage: Boolean = mimeType.startsWith("image/"),
) {
    val displaySize: String get() {
        val kb = sizeBytes / 1024
        return if (kb < 1024) "${kb} KB" else "${"%.1f".format(kb / 1024f)} MB"
    }

    val fileIcon: String get() = when {
        isImage -> "🖼️"
        mimeType == "application/pdf" -> "📄"
        mimeType.startsWith("text/") -> "📝"
        mimeType.contains("spreadsheet") || name.endsWith(".csv") -> "📊"
        mimeType.contains("json") -> "{ }"
        else -> "📎"
    }
}
