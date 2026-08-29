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
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.saarthi.core.i18n.LanguageManager
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
    private val languageManager: LanguageManager,
    private val regionalTesseractOcr: RegionalTesseractOcr,
) {
    companion object {
        // Hard caps surfaced to the user. Bigger files are rejected with
        // an error string on AttachedFile.error — the UI renders the
        // chip with that message instead of pretending the file is
        // ingestible.
        const val MAX_FILE_BYTES = 20L * 1024L * 1024L   // 20 MB raw file
        const val MAX_EXTRACTED_CHARS = 100_000          // ~25k tokens worth of text
        const val MAX_PDF_PAGES = 25                     // raised from 5; bounded by char cap

        // Small-file fast-path: prompt/search injects the whole body when
        // indexed text is ≤ this many characters (see expandWholeSmallFiles).
        const val WHOLE_FILE_CHARS = 3_000

        private val TEXT_MIME_TYPES = setOf(
            "text/plain", "text/markdown", "text/csv", "text/html",
            "text/xml", "application/json", "application/xml",
            "application/javascript", "text/x-python", "text/x-kotlin",
        )
    }

    /** Reused across pages in one extract call — ML Kit clients are not free to construct. */
    private val latinOcr by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val devanagariOcr by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
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
        val isCsv = mime == "text/csv" || mime == "application/csv" ||
            mime == "text/comma-separated-values" || lowerName.endsWith(".csv")
        val isXlsx = mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
            lowerName.endsWith(".xlsx")
        val isPptx = mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
            lowerName.endsWith(".pptx")
        val isLegacyXls = !isXlsx && (
            mime == "application/vnd.ms-excel" || lowerName.endsWith(".xls")
        )
        val isLegacyPpt = !isPptx && (
            mime == "application/vnd.ms-powerpoint" || lowerName.endsWith(".ppt")
        )
        val isText = !isCsv && (
            TEXT_MIME_TYPES.any { mime.startsWith(it) } ||
                name.endsWithAny(".txt", ".md", ".json", ".xml", ".kt", ".py",
                    ".js", ".ts", ".yaml", ".yml", ".html", ".log")
        )
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

        if (isLegacyXls) {
            return AttachedFile(
                uri = uri,
                name = name,
                mimeType = mime,
                sizeBytes = size,
                extractedText = null,
                isImage = false,
                error = "Legacy .xls isn't supported yet. Save the file as .xlsx (Excel → Save As → Excel Workbook) and re-attach.",
            )
        }
        if (isLegacyPpt) {
            return AttachedFile(
                uri = uri,
                name = name,
                mimeType = mime,
                sizeBytes = size,
                extractedText = null,
                isImage = false,
                error = "Legacy .ppt isn't supported yet. Save the file as .pptx (File → Save As → PowerPoint Presentation) and re-attach.",
            )
        }

        val pdfOutcome = if (mime == "application/pdf") extractPdfOutcome(uri) else null
        val extractedText = when {
            isCsv -> extractCsvText(uri)
            isXlsx -> extractXlsxText(uri)
            isPptx -> extractPptxText(uri)
            isText -> readTextContent(uri, MAX_EXTRACTED_CHARS)
            mime == "application/pdf" -> pdfOutcome?.text
            isDocx -> extractDocxText(uri)
            isImage -> extractImageText(uri)
            else -> null
        }

        // Untrusted attachment text is sanitized once so prompt stuffing and
        // RAG indexing share the same cleaned body. Extractor failure
        // sentinels ([PDF: …], [Image: …], …) are left intact so
        // extractionFailureMessage still maps them to AttachedFile.error.
        val cleanedText = extractedText?.let { raw ->
            if (extractionFailureMessage(raw) != null) raw
            else DocumentTextSanitizer.sanitize(raw)
        }

        val failure = cleanedText?.let { extractionFailureMessage(it) }
            ?: if (isImage && cleanedText.isNullOrBlank()) {
                "No text detected in this image."
            } else {
                null
            }
        return AttachedFile(
            uri = uri,
            name = name,
            mimeType = mime,
            sizeBytes = size,
            extractedText = if (failure != null) null else cleanedText,
            isImage = isImage,
            error = failure,
            indexTruncationNotice = if (failure != null) null else pdfOutcome?.truncationNotice,
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

    /**
     * PDF text extraction, industry-standard two-stage:
     *  1. TEXT LAYER (PdfBox) — pull the embedded, selectable text directly.
     *     Fast, exact, and script-agnostic, so a Hindi/Telugu/Bengali *digital*
     *     PDF works just like an English one. This is what the previous
     *     OCR-only path could not do (it rasterised every page and ran a
     *     Latin-only recognizer, so any non-English PDF came back empty).
     *  2. OCR FALLBACK — when the text layer is missing or too thin to be a
     *     real document (junk 24–35 char layers used to skip OCR). Latin OCR
     *     with the same white-bitmap path as before. If OCR is still thin,
     *     a sentinel error is returned (not indexed).
     *
     * English digital PDFs now come back via stage 1 (better + faster); scanned
     * PDFs still hit the exact same OCR code as before. No regression.
     */
    private data class PdfExtractOutcome(
        val text: String,
        val truncationNotice: String? = null,
    )

    private suspend fun extractPdfOutcome(uri: Uri): PdfExtractOutcome = withContext(Dispatchers.Default) {
        val textLayer = extractPdfTextLayer(uri)
        val garbled = looksGarbledTextLayer(textLayer?.text)
        if (pdfExtractLooksUsable(textLayer?.text) && !garbled) {
            Timber.d("PDF text-layer: extracted ${textLayer!!.text.length} chars (no OCR needed)")
            return@withContext finalizePdfExtract(textLayer.text, textLayer.meta)
        }
        if (garbled) {
            Timber.d("PDF text-layer garbled (${textLayer?.text?.length ?: 0} chars) — forcing OCR")
        } else {
            Timber.d("PDF text-layer empty/thin (${textLayer?.text?.length ?: 0} chars) — falling back to OCR")
        }
        val ocr = extractPdfViaOcr(uri)
        if (pdfExtractLooksUsable(ocr.text)) return@withContext finalizePdfExtract(ocr.text, ocr.meta)
        if (ocr.text.startsWith("[PDF:")) return@withContext PdfExtractOutcome(ocr.text)
        PdfExtractOutcome("[PDF: Scan had little readable text]")
    }

    private data class PdfLayerOutcome(
        val text: String,
        val meta: PdfTruncationMeta,
    )

    private fun finalizePdfExtract(text: String, meta: PdfTruncationMeta): PdfExtractOutcome {
        val (capped, charCapped) = capExtractedText(text, MAX_EXTRACTED_CHARS)
        val notice = buildPdfTruncationNotice(meta.copy(charCapped = meta.charCapped || charCapped))
        return PdfExtractOutcome(capped, notice)
    }

    /**
     * Stage 1: read the PDF's embedded text layer via PdfBox, one page at a
     * time, so citation headers can show p.X the same way OCR already does.
     * Returns null on failure (encrypted, corrupt, or no text layer).
     */
    private fun extractPdfTextLayer(uri: Uri): PdfLayerOutcome? = runCatching {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
                val totalPages = doc.numberOfPages
                val last = minOf(totalPages, MAX_PDF_PAGES)
                if (last <= 0) return@use PdfLayerOutcome("", PdfTruncationMeta())
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                val out = StringBuilder()
                for (page in 1..last) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val pageText = stripper.getText(doc).trim()
                    if (pageText.isEmpty()) continue
                    if (out.isNotEmpty()) out.appendLine()
                    out.appendLine("--- Page $page ---")
                    out.append(pageText)
                }
                PdfLayerOutcome(
                    text = out.toString().trim(),
                    meta = PdfTruncationMeta(
                        totalPages = totalPages,
                        indexedPages = last,
                        charCapped = false,
                    ),
                )
            }
        }
    }.onFailure { Timber.w(it, "PDF text-layer extraction failed — will try OCR") }.getOrNull()

    private suspend fun extractPdfViaOcr(uri: Uri): PdfLayerOutcome = withContext(Dispatchers.Default) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                    val totalPages = renderer.pageCount
                    val pagesToScan = minOf(totalPages, MAX_PDF_PAGES)
                    if (pagesToScan == 0) throw IllegalStateException("PDF has no pages")

                    val extracted = StringBuilder()
                    var charCapped = false

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

                                val pageText = ocrBitmap(bitmap)
                                if (pageText.isNotBlank()) {
                                    if (extracted.isNotEmpty()) extracted.appendLine()
                                    extracted.appendLine("--- Page ${pageIndex + 1} ---")
                                    extracted.append(cleanOcrPageText(pageText))
                                }
                            } finally {
                                // Free native bitmap memory immediately — at
                                // 4× scale + 25-page limit the cumulative
                                // pressure is significant without recycle().
                                bitmap.recycle()
                            }
                        }
                        if (extracted.length >= MAX_EXTRACTED_CHARS) {
                            charCapped = true
                            break
                        }
                    }

                    Timber.d("PDF OCR: extracted ${extracted.length} chars from $pagesToScan page(s)")
                    val finalResult = extracted.toString()
                    val text = if (finalResult.isBlank()) "[PDF: No readable text found]" else finalResult
                    PdfLayerOutcome(
                        text = text,
                        meta = PdfTruncationMeta(
                            totalPages = totalPages,
                            indexedPages = pagesToScan,
                            charCapped = charCapped || text.length >= MAX_EXTRACTED_CHARS,
                        ),
                    )
                }
            } ?: PdfLayerOutcome("[PDF: Could not open file descriptor]", PdfTruncationMeta())
        }.getOrElse { e ->
            Timber.e(e, "PDF OCR failed")
            PdfLayerOutcome("[PDF: Could not read file contents]", PdfTruncationMeta())
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
                            return@runCatching parseDocxXml(out.toString(Charsets.UTF_8.name()), MAX_EXTRACTED_CHARS)
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

    private suspend fun extractCsvText(uri: Uri): String = withContext(Dispatchers.IO) {
        val raw = readTextContent(uri, MAX_EXTRACTED_CHARS) ?: return@withContext "[Spreadsheet: Could not open file]"
        val formatted = formatCsvDocument(raw, MAX_EXTRACTED_CHARS)
        formatted.ifBlank { "[Spreadsheet: No readable cells found]" }
    }

    private suspend fun extractXlsxText(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val xmlCap = MAX_EXTRACTED_CHARS * 6
                val entries = readZipUtf8Entries(input, { name ->
                    name == "xl/sharedStrings.xml" ||
                        name == "xl/workbook.xml" ||
                        name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")
                }, xmlCap)
                val shared = entries["xl/sharedStrings.xml"].orEmpty()
                val workbook = entries["xl/workbook.xml"].orEmpty()
                val sheets = entries.entries
                    .filter { it.key.startsWith("xl/worksheets/sheet") }
                    .sortedBy { key ->
                        Regex("sheet(\\d+)").find(key.key)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }
                    .map { (path, xml) ->
                        val n = Regex("sheet(\\d+)").find(path)?.groupValues?.get(1) ?: "?"
                        "Sheet $n" to xml
                    }
                val text = formatXlsxDocument(shared, workbook, sheets, MAX_EXTRACTED_CHARS)
                if (text.isBlank()) "[Spreadsheet: No readable cells found]" else text
            } ?: "[Spreadsheet: Could not open file]"
        }.getOrElse { e ->
            Timber.e(e, "XLSX extract failed")
            "[Spreadsheet: Could not read contents]"
        }
    }

    private suspend fun extractPptxText(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val xmlCap = MAX_EXTRACTED_CHARS * 6
                val entries = readZipUtf8Entries(input, { name ->
                    name.startsWith("ppt/slides/slide") && name.endsWith(".xml") && !name.contains("/_")
                }, xmlCap)
                val slides = entries.entries
                    .sortedBy { (path, _) ->
                        Regex("slide(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }
                    .map { it.value }
                val text = formatPptxDocument(slides, MAX_EXTRACTED_CHARS)
                if (text.isBlank()) "[Presentation: No readable text found]" else text
            } ?: "[Presentation: Could not open file]"
        }.getOrElse { e ->
            Timber.e(e, "PPTX extract failed")
            "[Presentation: Could not read contents]"
        }
    }

    private suspend fun extractImageText(uri: Uri): String? = runCatching {
        val image = InputImage.fromFilePath(context, uri)
        val bitmap = decodeBitmapForOcr(uri)
        val text = try {
            ocrWithMlKitAndRegionalFallback(image, bitmap)
        } finally {
            bitmap?.recycle()
        }
        if (text.isNotBlank()) {
            "[Extracted from image]:\n${text.take(WHOLE_FILE_CHARS)}"
        } else {
            "[Image: No text detected in this image]"
        }
    }.onFailure { Timber.e(it, "OCR failed") }.getOrNull()

    /** Decode a display-sized bitmap for Tesseract (ML Kit uses [InputImage] directly). */
    private fun decodeBitmapForOcr(uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
            val maxDim = 4096
            val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / maxDim)
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { s ->
                android.graphics.BitmapFactory.decodeStream(s, null, opts)
            }
        }
    }.onFailure { Timber.w(it, "Could not decode image for regional OCR") }.getOrNull()

    /**
     * R4 + R4 follow-up — ML Kit (Latin + Devanagari) plus Tesseract for regional
     * scripts when ML Kit output is too weak for the user's language.
     */
    private suspend fun ocrBitmap(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return ocrWithMlKitAndRegionalFallback(image, bitmap)
    }

    private suspend fun ocrWithMlKitAndRegionalFallback(image: InputImage, bitmap: Bitmap?): String {
        val latin = runCatching { recognizeText(latinOcr, image) }
            .getOrElse { e ->
                Timber.w(e, "Latin OCR failed on page")
                ""
            }
        val devanagari = runCatching { recognizeText(devanagariOcr, image) }
            .getOrElse { e ->
                Timber.w(e, "Devanagari OCR failed on page")
                ""
            }
        val mlKit = IndicOcrMerger.merge(latin, devanagari)

        val userLang = languageManager.selectedLanguage.value
        if (bitmap == null || !IndicOcrPolicy.needsRegionalTesseractPass(mlKit, userLang)) {
            return mlKit
        }

        val tessLangs = IndicOcrPolicy.tesseractLanguages(userLang, mlKit)
        val regional = regionalTesseractOcr.recognize(bitmap, tessLangs)
        if (regional.isBlank()) return mlKit
        return IndicOcrMerger.mergeAll(listOf(mlKit, regional))
    }

    private suspend fun recognizeText(
        recognizer: TextRecognizer,
        image: InputImage,
    ): String = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun String.endsWithAny(vararg suffixes: String) =
        suffixes.any { this.lowercase().endsWith(it) }
}

/**
 * True when extracted PDF text is substantial enough to skip OCR (or to
 * keep OCR output). A 24–35 character junk layer ("Page 1", a date) must
 * not count — that was the Account Statement miss. Number-heavy statements
 * still pass via digit count, statement shape, or overall length.
 */
internal fun pdfExtractLooksUsable(text: String?): Boolean {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return false
    val body = PAGE_MARKER_STRIP.replace(t, " ")
    // A short bank/statement page can be all numbers with only a few letters,
    // so the letter/length thresholds below would wrongly drop it. Accept it
    // when it has the shape of a statement (several dated money rows).
    if (looksLikeStatement(body)) return true
    val letters = body.count { it.isLetter() }
    val digits = body.count { it.isDigit() }
    val indicLetters = body.count(::isIndicLetter)
    // Short Hindi/Tamil/etc. digital or OCR pages — Latin letter count alone was too strict.
    if (indicLetters >= 24) return true
    if (indicLetters >= 12 && body.length >= 48) return true
    if (letters >= 80) return true
    if (letters + digits >= 80 && body.length >= 80) return true
    if (body.length >= 400) return true
    return false
}

private val PAGE_MARKER_STRIP = Regex("---\\s*Page\\s+\\d+\\s*---", RegexOption.IGNORE_CASE)

/** DD/MM/YYYY, D-M-YY, DD.MM.YYYY, or ISO YYYY-MM-DD — the dates a statement row carries. */
internal val DATE_TOKEN = Regex("""\b(\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4}|\d{4}-\d{2}-\d{2})\b""")

/**
 * Money amounts: Indian rupee prefixes (₹ / Rs / INR), grouped thousands
 * including lakh-style (1,20,000), or a plain decimal (1200.50).
 */
internal val AMOUNT_TOKEN = Regex(
    """(?:₹|Rs\.?|INR)\s*\d[\d,]*(?:\.\d{1,2})?""" +
        """|\b\d{1,3}(?:,\d{2,3})+(?:\.\d{1,2})?\b""" +
        """|\b\d+\.\d{2}\b""",
)

/**
 * True when [body] has the shape of a bank/account statement: at least two
 * dates AND three money amounts. Indian statements are often number-heavy
 * with few prose letters, so the generic letter/length gates in
 * [pdfExtractLooksUsable] can drop a perfectly real short statement page.
 * The two-date + three-amount floor keeps a lone "Page 1 / 01/08/2026 / HDFC"
 * junk layer from qualifying.
 */
internal fun looksLikeStatement(body: String): Boolean {
    if (DATE_TOKEN.findAll(body).count() < 2) return false
    return AMOUNT_TOKEN.findAll(body).count() >= 3
}

/**
 * True when a PDF's embedded text layer decoded to garbage and must be
 * ignored in favour of OCR. Many Indian bank statements ship a CID / no-
 * ToUnicode font whose "text layer" is present (so it passes length gates)
 * but decodes to replacement chars, control bytes, private-use code points,
 * or one unbroken run with no spaces. Forcing OCR on those recovers the
 * real content instead of indexing mojibake.
 *
 * Deliberately conservative: a clean digital PDF (English or any Indic
 * script) has ~0% of these signals, so a good text layer is never discarded
 * (OCR would be strictly worse for digital Indic text).
 */
internal fun looksGarbledTextLayer(text: String?): Boolean {
    val body = PAGE_MARKER_STRIP.replace(text?.trim().orEmpty(), " ")
    if (body.length < 40) return false
    val n = body.length.toDouble()
    if (body.count { it == '\uFFFD' } / n > 0.10) return true
    val control = body.count { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }
    if (control / n > 0.05) return true
    if (body.count { it.code in 0xE000..0xF8FF } / n > 0.10) return true
    // A long letter-bearing run with zero whitespace is a kerning-collapsed
    // / scrambled layer — real text of this length always has word breaks.
    if (body.length >= 200 && body.any { it.isLetter() } && body.none { it.isWhitespace() }) return true
    return false
}

/**
 * Extractor failure strings used to be stored as [AttachedFile.extractedText]
 * and indexed as if they were document content. Map them to a user-facing
 * [AttachedFile.error] instead. Returns null when [text] is real content.
 */
internal fun extractionFailureMessage(text: String): String? {
    val t = text.trim()
    if (t.isEmpty()) return "No readable text found."
    return when {
        t.startsWith("[PDF: No readable text found]") ->
            "No readable text found in this PDF."
        t.startsWith("[PDF: Scan had little readable text]") ->
            "This PDF looks like a scan. On-device OCR (English + Indian scripts) found little readable text. Try a clearer scan or a digital PDF with selectable text."
        t.startsWith("[PDF: Could not open file descriptor]") ->
            "Could not open this PDF."
        t.startsWith("[PDF: Could not read file contents]") ->
            "Could not read this PDF."
        t.startsWith("[Word document: could not locate document body") ->
            "Could not read this Word document (file may be corrupt)."
        t.startsWith("[Word document: could not open file]") ->
            "Could not open this Word document."
        t.startsWith("[Word document: could not read contents]") ->
            "Could not read this Word document."
        t.startsWith("[Image: No text detected") ->
            "No text detected in this image."
        t.startsWith("[Spreadsheet: No readable cells found]") ->
            "No readable cells found in this spreadsheet."
        t.startsWith("[Spreadsheet: Could not open file]") ->
            "Could not open this spreadsheet."
        t.startsWith("[Spreadsheet: Could not read contents]") ->
            "Could not read this spreadsheet."
        t.startsWith("[Presentation: No readable text found]") ->
            "No readable text found in this presentation."
        t.startsWith("[Presentation: Could not open file]") ->
            "Could not open this presentation."
        t.startsWith("[Presentation: Could not read contents]") ->
            "Could not read this presentation."
        else -> null
    }
}

/** Prompt unreadable list: errors and empty extracts, including blank images. */
internal fun isUnreadableThisTurn(error: String?, extractedText: String?): Boolean =
    error != null || extractedText.isNullOrBlank()

private val OCR_LIST_ITEM = Regex("^([-*•]\\s+|\\d+[.)]\\s+).*")

/**
 * True when a line reads like a statement/table record rather than flowing
 * prose: it carries a date, or it is a multi-column row whose last column is
 * a money amount ("Grocery store    1,200.00"). Used to stop OCR line-unwrap
 * from gluing consecutive records together. Conservative — prose lines that
 * merely start with a number ("250 crore rupees.") are not matched.
 */
internal fun looksLikeTableRow(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return false
    if (DATE_TOKEN.containsMatchIn(t)) return true
    val tokens = t.split(Regex("\\s+"))
    return tokens.size >= 3 && AMOUNT_TOKEN.containsMatchIn(tokens.last())
}

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
    // Statement / table rows must stay on their own line: a row ending in an
    // amount followed by the next dated row would otherwise be joined (the row
    // starts with a digit → "continuation"), collapsing two records into one
    // and wrecking BM25 + readability. Keep each record separate.
    if (looksLikeTableRow(current) || looksLikeTableRow(next)) return false
    // Sentence/clause end — Latin and Indic (danda) punctuation.
    if (current.last() in ".!?:;-»\")]" || current.last() == '।' || current.last() == '॥') return false
    val first = next.first()
    // Latin continuation: lowercase word or number.
    if (first.isLowerCase() || first.isDigit()) {
        if (next.matches(OCR_LIST_ITEM)) return false
        return true
    }
    // Indic continuation: no case distinction — mid-sentence wraps start with another letter.
    if (isIndicLetter(first)) {
        if (next.matches(OCR_LIST_ITEM)) return false
        return true
    }
    return false
}
