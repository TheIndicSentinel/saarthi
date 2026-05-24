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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
        // Hard caps surfaced to the user. Bigger files are rejected with
        // an error string on AttachedFile.error — the UI renders the
        // chip with that message instead of pretending the file is
        // ingestible.
        const val MAX_FILE_BYTES = 20L * 1024L * 1024L   // 20 MB raw file
        const val MAX_EXTRACTED_CHARS = 100_000          // ~25k tokens worth of text
        const val MAX_PDF_PAGES = 25                     // raised from 5; bounded by char cap

        // Small-file fast-path used by the prompt builder to skip BM25
        // and emit the whole file when it comfortably fits.
        const val WHOLE_FILE_CHARS = 3_000

        private val TEXT_MIME_TYPES = setOf(
            "text/plain", "text/markdown", "text/csv", "text/html",
            "text/xml", "application/json", "application/xml",
            "application/javascript", "text/x-python", "text/x-kotlin",
        )
    }

    suspend fun extract(uri: Uri): AttachedFile {
        val (name, size, mime) = queryMetadata(uri)
        val isImage = mime.startsWith("image/")

        // Size gate up front — never read a 200 MB PDF into memory.
        // Images are exempt because OCR processes them as scaled bitmaps;
        // we still cap downstream by extracted-char count.
        if (!isImage && size > MAX_FILE_BYTES) {
            val mb = MAX_FILE_BYTES / (1024 * 1024)
            return AttachedFile(
                uri = uri,
                name = name,
                mimeType = mime,
                sizeBytes = size,
                extractedText = null,
                isImage = false,
                error = "File too large (${formatMb(size)}). Maximum supported size is ${mb} MB.",
            )
        }

        val isText = TEXT_MIME_TYPES.any { mime.startsWith(it) } ||
                name.endsWithAny(".txt", ".md", ".csv", ".json", ".xml", ".kt", ".py",
                    ".js", ".ts", ".yaml", ".yml", ".html", ".log")

        val extractedText = when {
            isText -> readTextContent(uri, MAX_EXTRACTED_CHARS)
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

    private fun formatMb(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return "%.1f MB".format(mb)
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

    private suspend fun extractPdfText(uri: Uri): String = withContext(Dispatchers.Default) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                    val pagesToScan = minOf(renderer.pageCount, MAX_PDF_PAGES)
                    if (pagesToScan == 0) throw IllegalStateException("PDF has no pages")

                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val extracted = StringBuilder()

                    // Cap rendered bitmap to ~16 MP / ~64 MB so a high-DPI
                    // multi-page PDF can't OOM the process. Within that
                    // cap, render at the highest scale that fits — small
                    // pages get 4×, A4 at 72-dpi gets ~4×, huge scanned
                    // pages auto-shrink.
                    val maxDim = 4096

                    for (pageIndex in 0 until pagesToScan) {
                        renderer.openPage(pageIndex).use { page ->
                            // CRITICAL: this scale + white-fill combo is what
                            // makes ML Kit actually return text. The previous
                            // version created an ARGB_8888 bitmap that started
                            // FULLY TRANSPARENT (alpha = 0); PdfRenderer's
                            // render() draws the page contents but does NOT
                            // paint the implicit white background that PDFs
                            // assume. ML Kit OCR then saw dark glyphs on a
                            // transparent canvas with near-zero contrast and
                            // missed most of the text — the production log
                            // showed only 2185 c extracted from a 6-page PDF.
                            // Pre-erasing to WHITE gives the OCR proper
                            // contrast and unlocks the rest of the document.
                            val scale = (maxDim.toFloat() / maxOf(page.width, page.height, 1))
                                .coerceIn(2f, 4f)
                            val bw = (page.width * scale).toInt().coerceAtLeast(1)
                            val bh = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = android.graphics.Bitmap.createBitmap(
                                bw, bh, android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            try {
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(
                                    bitmap, null, null,
                                    android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )

                                val result = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
                                    val image = InputImage.fromBitmap(bitmap, 0)
                                    recognizer.process(image)
                                        .addOnSuccessListener { cont.resume(it) }
                                        .addOnFailureListener { cont.resumeWithException(it) }
                                }

                                if (result.text.isNotBlank()) {
                                    if (extracted.isNotEmpty()) extracted.appendLine()
                                    extracted.appendLine("--- Page ${pageIndex + 1} ---")
                                    extracted.append(result.text)
                                }
                            } finally {
                                // Free native bitmap memory immediately — at
                                // 4× scale + 25-page limit the cumulative
                                // pressure is significant without recycle().
                                bitmap.recycle()
                            }
                        }
                        if (extracted.length >= MAX_EXTRACTED_CHARS) break
                    }

                    Timber.d("PDF OCR: extracted ${extracted.length} chars from $pagesToScan page(s)")
                    val finalResult = extracted.toString()
                    if (finalResult.isBlank()) "[PDF: No readable text found]"
                    else finalResult.take(MAX_EXTRACTED_CHARS)
                }
            } ?: "[PDF: Could not open file descriptor]"
        }.getOrElse { e ->
            Timber.e(e, "PDF OCR failed")
            "[PDF: Could not read file contents]"
        }
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
            "[Extracted from image]:\n${result.text.take(WHOLE_FILE_CHARS)}"
        } else {
            "[Image: No text detected in this image]"
        }
    }.onFailure { Timber.e(it, "OCR failed") }.getOrNull()

    private fun String.endsWithAny(vararg suffixes: String) =
        suffixes.any { this.lowercase().endsWith(it) }
}
