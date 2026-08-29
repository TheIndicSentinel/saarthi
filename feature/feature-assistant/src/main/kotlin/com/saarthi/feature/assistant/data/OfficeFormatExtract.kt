package com.saarthi.feature.assistant.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Structured ingest for CSV / XLSX / PPTX / DOCX — ZIP+XML only, no Apache POI.
 * Pure helpers so JVM tests can lock the format without a ContentResolver.
 */

internal const val TABLE_ROWS_PER_BLOCK = 25
internal const val MAX_XLSX_SHEETS = 8
internal const val MAX_PPTX_SLIDES = 40

internal fun formatCsvDocument(raw: String, maxChars: Int): String {
    val normalized = stripUtf8Bom(raw)
    val (headers, rows) = parseCsv(normalized)
    if (headers.isEmpty() || rows.isEmpty()) return normalized.take(maxChars)
    return formatStructuredTable(headers, rows, title = "CSV", maxChars = maxChars)
}

/** Strip BOM from Excel-exported UTF-8 CSV (common on Indian Windows builds). */
internal fun stripUtf8Bom(raw: String): String = raw.removePrefix("\uFEFF")

internal fun parseCsv(raw: String): Pair<List<String>, List<List<String>>> {
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n')
        .lines()
        .map { it.trimEnd() }
        .filter { it.isNotEmpty() }
    if (lines.isEmpty()) return emptyList<String>() to emptyList()
    val delim = detectCsvDelimiter(lines.take(5).joinToString("\n"))
    val records = lines.map { parseCsvLine(it, delim) }
    val first = records.first()
    val headerLike = first.any { isSubstantiveTableCell(it) }
    return if (headerLike && records.size >= 2) {
        first to records.drop(1)
    } else {
        first.indices.map { i -> "Col${i + 1}" } to records
    }
}

internal fun detectCsvDelimiter(sample: String): Char {
    val comma = sample.count { it == ',' }
    val tab = sample.count { it == '\t' }
    val semi = sample.count { it == ';' }
    return when {
        tab > comma && tab > semi -> '\t'
        semi > comma && semi > tab -> ';'
        else -> ','
    }
}

internal fun parseCsvLine(line: String, delim: Char): List<String> {
    val out = ArrayList<String>(8)
    val cell = StringBuilder()
    var i = 0
    var inQuotes = false
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' -> {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    cell.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            }
            c == delim && !inQuotes -> {
                out.add(cell.toString().trim())
                cell.clear()
            }
            else -> cell.append(c)
        }
        i++
    }
    out.add(cell.toString().trim())
    return out
}

/**
 * Header-prefixed row blocks so BM25 still sees column names after chunking.
 * Each block is [TABLE_ROWS_PER_BLOCK] data rows, capped at [maxChars].
 */
internal fun formatStructuredTable(
    headers: List<String>,
    rows: List<List<String>>,
    title: String,
    maxChars: Int,
    rowsPerBlock: Int = TABLE_ROWS_PER_BLOCK,
): String {
    if (headers.isEmpty() || rows.isEmpty() || maxChars < 40) return ""
    val colLine = "Columns: " + headers.joinToString(" | ")
    val out = StringBuilder()
    out.append("--- ").append(title).append(" ---\n")
    out.append(colLine).append("\n")
    var rowIndex = 0
    while (rowIndex < rows.size) {
        val end = minOf(rowIndex + rowsPerBlock, rows.size)
        val blockHeader = "\n--- Rows ${rowIndex + 1}-$end ---\n"
        if (out.length + blockHeader.length >= maxChars) break
        out.append(blockHeader)
        for (r in rowIndex until end) {
            val line = formatTableRow(headers, rows[r]) + "\n"
            if (out.length + line.length > maxChars) {
                return out.toString().trimEnd()
            }
            out.append(line)
        }
        rowIndex = end
    }
    return out.toString().trimEnd()
}

internal fun formatTableRow(headers: List<String>, values: List<String>): String {
    val parts = ArrayList<String>(headers.size)
    for (i in headers.indices) {
        val h = headers[i].ifBlank { "Col${i + 1}" }
        val v = values.getOrElse(i) { "" }.trim()
        if (v.isEmpty()) continue
        parts.add("$h: $v")
    }
    return parts.joinToString(" | ")
}

internal fun parseSharedStringsXml(xml: String): List<String> {
    if (xml.isEmpty()) return emptyList()
    return Regex("<si>(.*?)</si>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(xml)
        .map { si ->
            Regex("<t(?:\\s[^>]*)?>(.*?)</t>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(si.groupValues[1])
                .joinToString("") { decodeXmlEntities(it.groupValues[1]) }
        }
        .toList()
}

internal fun parseXlsxSheetRows(xml: String, shared: List<String>): List<List<String>> {
    if (xml.isEmpty()) return emptyList()
    val rows = ArrayList<List<String>>()
    val rowRe = Regex("<row\\b[^>]*>(.*?)</row>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val cellRe = Regex("<c\\b([^>]*)>(.*?)</c>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    for (rowMatch in rowRe.findAll(xml)) {
        val cells = ArrayList<Pair<Int, String>>()
        for (cell in cellRe.findAll(rowMatch.groupValues[1])) {
            val attrs = cell.groupValues[1]
            val body = cell.groupValues[2]
            val col = columnIndexFromRef(Regex("""\br="([A-Z]+\d+)"""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1))
            val type = Regex("""\bt="([^"]+)"""").find(attrs)?.groupValues?.get(1).orEmpty()
            val value = when {
                type.equals("s", ignoreCase = true) -> {
                    val idx = Regex("<v>(.*?)</v>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.toIntOrNull()
                    if (idx != null) shared.getOrElse(idx) { "" } else ""
                }
                type.equals("inlineStr", ignoreCase = true) -> {
                    Regex("<t(?:\\s[^>]*)?>(.*?)</t>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(body)?.groupValues?.get(1)?.let { decodeXmlEntities(it) }.orEmpty()
                }
                else -> {
                    Regex("<v>(.*?)</v>", RegexOption.IGNORE_CASE).find(body)
                        ?.groupValues?.get(1)?.let { decodeXmlEntities(it) }.orEmpty()
                }
            }
            cells.add(col to value)
        }
        if (cells.isEmpty() || cells.all { it.second.isBlank() }) continue
        val width = (cells.maxOf { it.first } + 1).coerceAtLeast(1)
        val row = MutableList(width) { "" }
        for ((col, v) in cells) {
            if (col in row.indices) row[col] = v
        }
        rows.add(row)
    }
    return rows
}

internal fun parseWorkbookSheetNames(workbookXml: String): List<String> =
    Regex("""<sheet\b[^>]*\bname="([^"]+)"""", RegexOption.IGNORE_CASE)
        .findAll(workbookXml)
        .map { decodeXmlEntities(it.groupValues[1]) }
        .toList()

internal fun formatXlsxDocument(
    sharedXml: String,
    workbookXml: String,
    sheetXmlByName: List<Pair<String, String>>,
    maxChars: Int,
): String {
    val shared = parseSharedStringsXml(sharedXml)
    val names = parseWorkbookSheetNames(workbookXml)
    val out = StringBuilder()
    sheetXmlByName.take(MAX_XLSX_SHEETS).forEachIndexed { i, (fallbackName, xml) ->
        if (out.length >= maxChars) return@forEachIndexed
        val rows = parseXlsxSheetRows(xml, shared)
        if (rows.isEmpty()) return@forEachIndexed
        val title = names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: fallbackName
        val (headers, data) = if (rows.size >= 2 && rows.first().any { isSubstantiveTableCell(it) }) {
            rows.first() to rows.drop(1)
        } else {
            rows.first().indices.map { c -> "Col${c + 1}" } to rows
        }
        val remaining = maxChars - out.length
        if (remaining < 40) return@forEachIndexed
        val block = formatStructuredTable(headers, data, title = "Sheet: $title", maxChars = remaining)
        if (block.isNotEmpty()) {
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(block)
        }
    }
    return out.toString().trimEnd()
}

internal fun parsePptxSlideXml(xml: String): String {
    if (xml.isEmpty()) return ""
    val parts = ArrayList<String>(8)
    val tableRe = Regex("<a:tbl>(.*?)</a:tbl>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val cellRe = Regex("<a:tc>(.*?)</a:tc>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val textRe = Regex("<a:t(?:\\s[^>]*)?>(.*?)</a:t>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    for (table in tableRe.findAll(xml)) {
        val rowCells = cellRe.findAll(table.groupValues[1]).map { cell ->
            textRe.findAll(cell.groupValues[1])
                .joinToString("") { decodeXmlEntities(it.groupValues[1]) }
                .trim()
        }.filter { it.isNotEmpty() }.toList()
        if (rowCells.isNotEmpty()) {
            parts.add(rowCells.joinToString(" | "))
        }
    }
    val bodyXml = tableRe.replace(xml, "")
    val withBreaks = bodyXml.replace(Regex("</a:p>", RegexOption.IGNORE_CASE), "\n")
    val bodyTexts = textRe.findAll(withBreaks)
        .map { decodeXmlEntities(it.groupValues[1]) }
        .filter { it.isNotBlank() }
        .toList()
    if (bodyTexts.isNotEmpty()) {
        parts.add(bodyTexts.joinToString(" ").replace(Regex(" *\n *"), "\n").trim())
    }
    return parts.filter { it.isNotBlank() }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

internal fun formatPptxDocument(slides: List<String>, maxChars: Int): String {
    val out = StringBuilder()
    slides.take(MAX_PPTX_SLIDES).forEachIndexed { i, xml ->
        val text = parsePptxSlideXml(xml)
        if (text.isEmpty()) return@forEachIndexed
        val header = if (out.isEmpty()) "--- Slide ${i + 1} ---\n" else "\n\n--- Slide ${i + 1} ---\n"
        if (out.length + header.length + text.length > maxChars) {
            val room = maxChars - out.length - header.length
            if (room > 40) {
                out.append(header).append(text.take(room))
            }
            return out.toString().trimEnd()
        }
        out.append(header).append(text)
    }
    return out.toString().trimEnd()
}

internal fun parseDocxXml(xml: String, maxChars: Int): String {
    val withBreaks = xml
        .replace("</w:p>", "\n")
        .replace("</w:tr>", "\n")
        .replace(Regex("<w:tab[^/]*/>"), "\t")
        .replace(Regex("<w:br[^/]*/>"), "\n")
    val stripped = Regex("<[^>]+>").replace(withBreaks, "")
    val cleaned = decodeXmlEntities(stripped)
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n[ \\t]+"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
    return if (cleaned.length > maxChars) cleaned.take(maxChars) else cleaned
}

internal fun decodeXmlEntities(s: String): String {
    var out = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#10;", "\n")
        .replace("&#13;", "")
    out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
        val code = m.groupValues[1].toIntOrNull(16)
        if (code != null && code in 1..0x10FFFF) {
            String(Character.toChars(code))
        } else {
            m.value
        }
    }
    out = Regex("&#(\\d+);").replace(out) { m ->
        val code = m.groupValues[1].toIntOrNull()
        if (code != null && code in 1..0x10FFFF) {
            String(Character.toChars(code))
        } else {
            m.value
        }
    }
    return out
}

/**
 * Read selected ZIP entries as UTF-8 strings in a single pass. Caps each
 * entry so a zip-bomb cannot blow the heap.
 */
internal fun readZipUtf8Entries(
    input: InputStream,
    want: (String) -> Boolean,
    maxBytesPerEntry: Int,
): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    ZipInputStream(input).use { zis ->
        val buf = ByteArray(8 * 1024)
        var entry = zis.nextEntry
        while (entry != null) {
            val name = entry.name
            if (!entry.isDirectory && want(name)) {
                val bytes = ByteArrayOutputStream(minOf(64 * 1024, maxBytesPerEntry))
                var total = 0
                var n = zis.read(buf)
                while (n >= 0 && total < maxBytesPerEntry) {
                    val take = minOf(n, maxBytesPerEntry - total)
                    bytes.write(buf, 0, take)
                    total += take
                    n = zis.read(buf)
                }
                out[name] = bytes.toString(Charsets.UTF_8.name())
            }
            entry = zis.nextEntry
        }
    }
    return out
}

internal fun columnIndexFromRef(ref: String?): Int {
    if (ref.isNullOrEmpty()) return 0
    var n = 0
    for (c in ref.uppercase()) {
        if (c !in 'A'..'Z') break
        n = n * 26 + (c - 'A' + 1)
    }
    return (n - 1).coerceAtLeast(0)
}

/** Header / label cell: Latin, Indic script, or digits (₹ amounts, dates). */
internal fun isSubstantiveTableCell(cell: String): Boolean =
    cell.any { ch -> ch.isLetter() || isIndicLetter(ch) || ch.isDigit() }

/**
 * Sheet / slide / row markers from structured office ingest — used for Sources
 * locations when page numbers do not exist.
 */
internal fun extractOfficeStructureMarker(text: String): String? {
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.matches(Regex("^---\\s*Sheet:\\s*.+\\s*---$", RegexOption.IGNORE_CASE)) ->
                return line.removePrefix("---").removeSuffix("---").trim()
            line.matches(Regex("^---\\s*Slide\\s+\\d+\\s*---$", RegexOption.IGNORE_CASE)) ->
                return line.removePrefix("---").removeSuffix("---").trim()
            line.matches(Regex("^---\\s*Rows\\s+\\d+-\\d+\\s*---$", RegexOption.IGNORE_CASE)) ->
                return line.removePrefix("---").removeSuffix("---").trim()
            line.equals("--- CSV ---", ignoreCase = true) -> return "CSV"
        }
    }
    return null
}

/** Max chars per indexed chunk for structured spreadsheet/slide blocks. */
internal const val STRUCTURED_OFFICE_CHUNK_CHARS = 1800
internal const val STRUCTURED_OFFICE_CHUNK_OVERLAP = 80

/** True when text came from [formatCsvDocument] / XLSX / PPTX structured ingest. */
internal fun isStructuredOfficeExtract(text: String): Boolean {
    val t = text.trimStart()
    return t.startsWith("--- CSV ---") ||
        t.contains("--- Sheet:") ||
        t.contains("--- Slide ") ||
        t.contains("--- Rows ")
}

/**
 * Chunk on sheet/slide/row block boundaries so large CSV/XLSX exports stay
 * row-complete for BM25 and FTS5 at scale.
 */
internal fun chunkStructuredOfficeExtract(
    text: String,
    chunkSize: Int = STRUCTURED_OFFICE_CHUNK_CHARS,
    overlap: Int = STRUCTURED_OFFICE_CHUNK_OVERLAP,
): List<String> {
    if (!isStructuredOfficeExtract(text)) return emptyList()
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    val sections = trimmed.split(
        Regex("(?m)\n\n(?=--- (?:Rows \\d+-\\d+|Sheet:|Slide \\d+|CSV ---))"),
    )
    val out = ArrayList<String>(sections.size.coerceAtLeast(1))
    for (section in sections) {
        val block = section.trim()
        if (block.isEmpty()) continue
        if (block.length <= chunkSize) {
            out.add(block)
        } else {
            out.addAll(chunkDocumentText(block, chunkSize, overlap))
        }
    }
    return if (out.isEmpty()) listOf(trimmed) else out
}
