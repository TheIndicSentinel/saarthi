package com.saarthi.feature.assistant.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.saarthi.feature.assistant.domain.AttachedFile
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileContentExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val MAX_TEXT_CHARS = 12_000   // ~3k tokens — keeps context manageable
        private val TEXT_MIME_TYPES = setOf(
            "text/plain", "text/markdown", "text/csv", "text/html",
            "text/xml", "application/json", "application/xml",
            "application/javascript", "text/x-python", "text/x-kotlin",
        )
    }

    suspend fun extract(uri: Uri): AttachedFile {
        val (name, size, mime) = queryMetadata(uri)
        val isText = TEXT_MIME_TYPES.any { mime.startsWith(it) } ||
                name.endsWithAny(".txt", ".md", ".csv", ".json", ".xml", ".kt", ".py", ".js", ".ts", ".yaml", ".yml")
        val isImage = mime.startsWith("image/")

        val extractedText = when {
            isText -> readTextContent(uri)
            mime == "application/pdf" -> extractPdfText(uri)
            else -> null
        }

        return AttachedFile(
            uri = uri,
            name = name,
            mimeType = mime,
            sizeBytes = size,
            extractedText = extractedText,
            isImage = isImage,
        )
    }

    private fun queryMetadata(uri: Uri): Triple<String, Long, String> {
        var name = "Attachment"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: "Attachment"
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return Triple(name, size, mime)
    }

    private fun readTextContent(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val content = reader.readText()
            if (content.length > MAX_TEXT_CHARS)
                content.take(MAX_TEXT_CHARS) + "\n\n[... truncated at $MAX_TEXT_CHARS chars]"
            else content
        }
    }.onFailure { Timber.w(it, "Failed to read text content") }.getOrNull()

    private fun extractPdfText(uri: Uri): String {
        // Production: integrate iText or Apache PDFBox Android port.
        // Android PdfRenderer renders pages as bitmaps (not extractable text).
        return "[PDF attached: ${queryMetadata(uri).first}. Text extraction requires PDFBox integration.]"
    }

    fun buildPromptContext(files: List<AttachedFile>): String {
        if (files.isEmpty()) return ""
        return buildString {
            appendLine("\n--- Attached Files ---")
            files.forEachIndexed { i, file ->
                appendLine("File ${i + 1}: ${file.name} (${file.mimeType})")
                when {
                    file.extractedText != null -> {
                        appendLine("Content:")
                        appendLine("```")
                        appendLine(file.extractedText)
                        appendLine("```")
                    }
                    file.isImage -> appendLine("[Image attached — this model is text-only; describe what you want to know about it]")
                    else -> appendLine("[Binary file — content not extractable]")
                }
            }
            appendLine("--- End of Files ---\n")
        }
    }

    private fun String.endsWithAny(vararg suffixes: String) =
        suffixes.any { this.lowercase().endsWith(it) }
}
