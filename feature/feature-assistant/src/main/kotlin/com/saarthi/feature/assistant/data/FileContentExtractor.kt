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

        val lowerName = name.lowercase()
        val isText = TEXT_MIME_TYPES.any { mime.startsWith(it) } ||
                name.endsWithAny(".txt", ".md", ".csv", ".json", ".xml", ".kt", ".py",
                    ".js", ".ts", ".yaml", ".yml", ".html", ".log")
        // .docx — modern Word documents are ZIP archives containing
        // word/document.xml. Native parsing keeps it dep-free (no
        // Apache POI / +5 MB APK).
        val isDocx = mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                lowerName.endsWith(".docx")
        // Legacy .doc binary format requires Apache POI to parse; surface
        // a clear user-facing error rather than the generic "binary" path
        // so the user knows to re-save the file.
        val isLegacyDoc = !isDocx && (
            mime == "application/msword" ||
                (lowerName.endsWith(".doc") && !lowerName.endsWith(".docx"))
        )
        if (isLegacyDoc) {
            return AttachedFile(
                uri = uri,
                name = name,
                mimeType = mime,
                sizeBytes = size,
                extractedText = null,
                isImage = false,
                error = "Legacy .doc isn't supported yet. Save the file as .docx (File → Save As → Word Document) and re-attach.",
            )
        }

        val extractedText = when {
            isText -> readTextContent(uri, MAX_EXTRACTED_CHARS)
            mime == "application/pdf" -> extractPdfText(uri)
            isDocx -> extractDocxText(uri)
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
                                    extracted.append(cleanOcrPageText(result.text))
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

    /**
     * Post-process raw ML Kit OCR output from a single PDF page to reduce
     * noise from table/column layouts.
     *
     * ML Kit returns text in TextBlock reading order. For tables, each cell
     * is a separate TextBlock, so a word like "Battery" split across two
     * cells can appear as two short lines: "Ba" and "ttery". When these are
     * chunked without cleanup, the word fragment lands at the start of the
     * next chunk (preview shows "ttery…"), confusing BM25 and the model.
     *
     * This pass joins very short alphabetic-only lines (1–4 chars) onto the
     * next non-blank line when the next line begins with a lowercase letter —
     * the classic signature of a broken word. It does NOT alter page markers,
     * numbers, or capitalized words, so headings and figures stay intact.
     */
    private fun cleanOcrPageText(raw: String): String {
        if (raw.length < 6) return raw
        val lines = raw.lines()
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            // Preserve blank lines (paragraph gaps) and page markers as-is.
            if (trimmed.isEmpty()) { out.appendLine(); i++; continue }
            if (trimmed.startsWith("---") && trimmed.endsWith("---")) {
                out.appendLine(trimmed); i++; continue
            }

            out.append(trimmed)

            // Line-unwrap: OCR breaks a flowing paragraph into visual lines, which
            // leaves chunks full of mid-sentence fragments ("…can attract\na
            // penalty…"). When this line is a wrapped continuation, join it to the
            // next with a SPACE so the chunker sees whole sentences — better BM25
            // matching and far more readable context for the model. Otherwise keep
            // the newline (real paragraph / list / heading boundary).
            val next = (i + 1 until lines.size)
                .map { lines[it].trim() }
                .firstOrNull { it.isNotEmpty() }
            val joins = next != null &&
                !(next.startsWith("---") && next.endsWith("---")) &&
                isOcrLineWrap(trimmed, next)
            out.append(if (joins) " " else "\n")
            i++
        }
        return out.toString()
    }

    /**
     * .docx (Word) text extraction without an external library.
     *
     * A .docx file is a ZIP archive whose `word/document.xml` entry
     * carries the document body in OOXML. We open the ZIP via
     * `java.util.zip.ZipInputStream` (stdlib), pull that one entry,
     * strip the XML tags, and decode the standard entities. Paragraph
     * (`</w:p>`) and table-row (`</w:tr>`) ends become newlines so
     * the BM25 chunker sees real paragraph boundaries downstream.
     *
     * Raw XML is capped at ~600 KB before parsing so a pathologically
     * large doc can't OOM the process even though the source file is
     * already bounded by [MAX_FILE_BYTES] above.
     */
    private suspend fun extractDocxText(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.util.zip.ZipInputStream(input).use { zis ->
                    val xmlCapBytes = MAX_EXTRACTED_CHARS * 6  // ~600 KB ceiling
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "word/document.xml") {
                            val out = java.io.ByteArrayOutputStream(64 * 1024)
                            val buf = ByteArray(8 * 1024)
                            var total = 0
                            var n = zis.read(buf)
                            while (n >= 0 && total < xmlCapBytes) {
                                val take = minOf(n, xmlCapBytes - total)
                                out.write(buf, 0, take)
                                total += take
                                n = zis.read(buf)
                            }
                            return@runCatching parseDocxXml(out.toString(Charsets.UTF_8.name()))
                        }
                        entry = zis.nextEntry
                    }
                    "[Word document: could not locate document body — file may be corrupt]"
                }
            } ?: "[Word document: could not open file]"
        }.getOrElse { e ->
            Timber.e(e, "DOCX extract failed")
            "[Word document: could not read contents]"
        }
    }

    /** Convert OOXML body to plain text, preserving paragraph breaks. */
    private fun parseDocxXml(xml: String): String {
        val withBreaks = xml
            .replace("</w:p>", "\n")
            .replace("</w:tr>", "\n")
            .replace(Regex("<w:tab[^/]*/>"), "\t")
            .replace(Regex("<w:br[^/]*/>"), "\n")
        val stripped = Regex("<[^>]+>").replace(withBreaks, "")
        val decoded = stripped
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        val cleaned = decoded
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return if (cleaned.length > MAX_EXTRACTED_CHARS) cleaned.take(MAX_EXTRACTED_CHARS) else cleaned
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

private val OCR_LIST_ITEM = Regex("^([-*•]\\s+|\\d+[.)]\\s+).*")

/**
 * True when an OCR'd line [current] is a WRAPPED continuation that should be
 * joined to [next] with a space (reconstructing a paragraph), rather than left
 * as a separate line. Conservative — only joins when [current] clearly does not
 * end a sentence/clause and [next] clearly continues it — so real paragraph,
 * list and heading boundaries are preserved.
 *
 * Top-level `internal` so the line-unwrap decision is unit-testable.
 */
internal fun isOcrLineWrap(current: String, next: String): Boolean {
    if (current.isEmpty() || next.isEmpty()) return false
    // Sentence/clause end, or a hyphen we won't space-join → keep the break.
    if (current.last() in ".!?:;-»\")]") return false
    val first = next.first()
    // Next must look like a continuation: a lowercase word or a bare number.
    if (!(first.isLowerCase() || first.isDigit())) return false
    // A bulleted / numbered list item on the next line is an intentional break.
    if (next.matches(OCR_LIST_ITEM)) return false
    return true
}
