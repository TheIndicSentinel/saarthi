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
        private const val MAX_DIRECT_CHARS = 3_000   // ~750 tokens — include whole file
        private const val MAX_RAG_FILE_CHARS = 80_000 // read up to 80K for chunking
        private const val RAG_CHUNK_SIZE = 800        // chars per chunk
        private const val RAG_TOP_CHUNKS = 4          // max chunks to inject

        private val TEXT_MIME_TYPES = setOf(
            "text/plain", "text/markdown", "text/csv", "text/html",
            "text/xml", "application/json", "application/xml",
            "application/javascript", "text/x-python", "text/x-kotlin",
        )
    }

    suspend fun extract(uri: Uri): AttachedFile {
        val (name, size, mime) = queryMetadata(uri)
        val isText = TEXT_MIME_TYPES.any { mime.startsWith(it) } ||
                name.endsWithAny(".txt", ".md", ".csv", ".json", ".xml", ".kt", ".py",
                    ".js", ".ts", ".yaml", ".yml", ".html", ".log")
        val isImage = mime.startsWith("image/")

        val extractedText = when {
            isText -> readTextContent(uri, MAX_RAG_FILE_CHARS)
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

    /**
     * Builds prompt context using keyword-aware RAG chunking.
     * For small files: includes full content.
     * For large files: scores chunks by query keyword overlap, takes top N.
     */
    fun buildRagContext(files: List<AttachedFile>, query: String = ""): String {
        if (files.isEmpty()) return ""
        return buildString {
            appendLine("--- Attached Files ---")
            files.forEachIndexed { i, file ->
                appendLine("File ${i + 1}: ${file.name} (${file.mimeType})")
                when {
                    file.extractedText != null -> {
                        val content = file.extractedText
                        if (content.length <= MAX_DIRECT_CHARS) {
                            appendLine("Content:")
                            appendLine("```")
                            appendLine(content)
                            appendLine("```")
                        } else {
                            // RAG: extract relevant chunks based on query keywords
                            val chunks = extractRelevantChunks(content, query)
                            appendLine("Relevant excerpts (${file.name}):")
                            chunks.forEachIndexed { idx, chunk ->
                                appendLine("[Excerpt ${idx + 1}]")
                                appendLine(chunk.trim())
                            }
                        }
                    }
                    file.isImage ->
                        appendLine("[Image: ${file.name} — describe what you want to know about it]")
                    else ->
                        appendLine("[Binary file: ${file.name} — content not extractable]")
                }
            }
            appendLine("--- End of Files ---")
        }
    }

    private fun extractRelevantChunks(text: String, query: String): List<String> {
        val chunks = text.chunked(RAG_CHUNK_SIZE)
        if (chunks.size <= RAG_TOP_CHUNKS) return chunks

        val queryWords = query.lowercase()
            .split(Regex("\\W+"))
            .filter { it.length > 3 }
            .toSet()

        if (queryWords.isEmpty()) {
            // No query keywords: return first + last chunks (likely intro + conclusion)
            return (listOf(chunks.first()) + chunks.takeLast(RAG_TOP_CHUNKS - 1)).distinct()
        }

        return chunks
            .mapIndexed { idx, chunk ->
                val lower = chunk.lowercase()
                val score = queryWords.count { word -> lower.contains(word) }
                Triple(idx, chunk, score)
            }
            .sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })
            .take(RAG_TOP_CHUNKS)
            .sortedBy { it.first } // restore reading order
            .map { it.second }
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

    private fun readTextContent(uri: Uri, maxChars: Int): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val content = reader.readText()
            if (content.length > maxChars) content.take(maxChars) else content
        }
    }.onFailure { Timber.w(it, "Failed to read text content") }.getOrNull()

    private fun extractPdfText(uri: Uri): String {
        return "[PDF: ${runCatching { queryMetadata(uri).first }.getOrDefault("file")}. " +
                "Text extraction from PDF is not yet supported. " +
                "Copy-paste the relevant text into the chat for best results.]"
    }

    private fun String.endsWithAny(vararg suffixes: String) =
        suffixes.any { this.lowercase().endsWith(it) }
}
