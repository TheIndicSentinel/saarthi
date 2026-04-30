package com.saarthi.feature.assistant.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.saarthi.feature.assistant.domain.AttachedFile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
            isImage -> extractImageText(uri)
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
                        appendLine("[Image: ${file.name} — text was extracted if available. If the image contains no readable text, ask the user to describe what they want to know.]")
                    else ->
                        appendLine("[Binary file: ${file.name} — content not extractable. Ask the user to provide the relevant text or a brief description of what they need from this file.]")
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

    private suspend fun extractPdfText(uri: Uri): String {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pagesToScan = minOf(renderer.pageCount, 2)
                    if (pagesToScan == 0) throw IllegalStateException("PDF has no pages")

                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val extracted = StringBuilder()
                    var shouldStop = false

                    for (pageIndex in 0 until pagesToScan) {
                        if (shouldStop) break
                        renderer.openPage(pageIndex).use { page ->
                            val targetWidth = minOf(page.width * 2, 1200)
                            val targetHeight = maxOf((targetWidth.toFloat() / page.width * page.height).toInt(), 1)
                            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            val result = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
                                val image = InputImage.fromBitmap(bitmap, 0)
                                recognizer.process(image)
                                    .addOnSuccessListener { cont.resume(it) }
                                    .addOnFailureListener { cont.resumeWithException(it) }
                            }

                            if (result.text.isNotBlank()) {
                                if (extracted.isNotEmpty()) extracted.appendLine()
                                extracted.appendLine("Page ${pageIndex + 1}:")
                                extracted.appendLine(result.text.take(MAX_DIRECT_CHARS - extracted.length))
                                if (extracted.length >= MAX_DIRECT_CHARS) shouldStop = true
                            }
                        }
                    }

                    val text = extracted.toString().trim()
                    if (text.isBlank()) {
                        "[PDF: ${queryMetadata(uri).first}. No readable text was found. Please provide the relevant text or a short summary of what you need from this file.]"
                    } else {
                        text
                    }
                }
            } ?: "[PDF: ${queryMetadata(uri).first}. Could not open file. Please provide relevant text or a short summary.]"
        }.onFailure {
            Timber.w(it, "PDF extraction failed")
        }.getOrNull() ?: "[PDF: ${runCatching { queryMetadata(uri).first }.getOrDefault("file")}. Extraction failed. Please provide the relevant text or a short summary.]"
    }

    private suspend fun extractImageText(uri: Uri): String? = runCatching {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromFilePath(context, uri)
        val result = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        if (result.text.isNotBlank()) {
            "[Extracted from image]:\n${result.text.take(MAX_DIRECT_CHARS)}"
        } else {
            "[Image: No text detected in this image]"
        }
    }.onFailure { Timber.e(it, "OCR failed") }.getOrNull()

    private fun String.endsWithAny(vararg suffixes: String) =
        suffixes.any { this.lowercase().endsWith(it) }
}
