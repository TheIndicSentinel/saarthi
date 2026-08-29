package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeFormatExtractTest {

    @Test
    fun `csv keeps column names on each data row`() {
        val raw = "Date,Description,Amount\n12/03/2026,UPI grocery,1250\n13/03/2026,Salary,34000"
        val text = formatCsvDocument(raw, maxChars = 4_000)
        assertTrue(text.contains("--- CSV ---"))
        assertTrue(text.contains("Columns: Date | Description | Amount"))
        assertTrue(text.contains("Date: 12/03/2026"))
        assertTrue(text.contains("Description: UPI grocery"))
        assertTrue(text.contains("--- Rows 1-2 ---"))
    }

    @Test
    fun `csv quoted comma stays in one cell`() {
        val row = parseCsvLine("\"Grocery, store\",1200", ',')
        assertEquals(listOf("Grocery, store", "1200"), row)
    }

    @Test
    fun `tab-separated sample is not split on commas`() {
        assertEquals('\t', detectCsvDelimiter("a\tb\tc\n1\t2\t3"))
    }

    @Test
    fun `xlsx shared string and numeric cells become header-prefixed rows`() {
        val shared = """
            <sst><si><t>Date</t></si><si><t>Amount</t></si><si><t>12/03/2026</t></si></sst>
        """.trimIndent()
        val sheet = """
            <sheetData>
              <row r="1">
                <c r="A1" t="s"><v>0</v></c>
                <c r="B1" t="s"><v>1</v></c>
              </row>
              <row r="2">
                <c r="A2" t="s"><v>2</v></c>
                <c r="B2"><v>1250.00</v></c>
              </row>
            </sheetData>
        """.trimIndent()
        val workbook = """<workbook><sheets><sheet name="Sales" sheetId="1"/></sheets></workbook>"""
        val text = formatXlsxDocument(shared, workbook, listOf("Sheet 1" to sheet), maxChars = 4_000)
        assertTrue(text.contains("--- Sheet: Sales ---"))
        assertTrue(text.contains("Date: 12/03/2026"))
        assertTrue(text.contains("Amount: 1250.00"))
    }

    @Test
    fun `pptx slide xml becomes a numbered slide block`() {
        val xml = """<p><a:t>MSP for wheat</a:t></p><a:p/><a:t>₹2125 / quintal</a:t>"""
        val text = formatPptxDocument(listOf(xml), maxChars = 2_000)
        assertTrue(text.contains("--- Slide 1 ---"))
        assertTrue(text.contains("MSP for wheat"))
        assertTrue(text.contains("₹2125 / quintal"))
    }

    @Test
    fun `docx xml keeps indic paragraph text`() {
        val xml = "<w:p><w:t>परिचय</w:t></w:p><w:p><w:t>यह एक वाक्य है।</w:t></w:p>"
        val text = parseDocxXml(xml, maxChars = 1_000)
        assertTrue(text.contains("परिचय"))
        assertTrue(text.contains("यह एक वाक्य है।"))
    }

    @Test
    fun `row blocks cap at twenty five`() {
        val headers = listOf("N")
        val rows = (1..30).map { listOf(it.toString()) }
        val text = formatStructuredTable(headers, rows, "CSV", maxChars = 8_000)
        assertTrue(text.contains("--- Rows 1-25 ---"))
        assertTrue(text.contains("--- Rows 26-30 ---"))
    }

    @Test
    fun `zip helper returns selected utf8 entries`() {
        val zipBytes = ByteArrayOutputStream().use { raw ->
            ZipOutputStream(raw).use { zos ->
                zos.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
                zos.write("<a:t>Hello slide</a:t>".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("ppt/slides/_rels/slide1.xml.rels"))
                zos.write("skip".toByteArray())
                zos.closeEntry()
            }
            raw.toByteArray()
        }
        val entries = readZipUtf8Entries(
            ByteArrayInputStream(zipBytes),
            { name -> name.startsWith("ppt/slides/slide") && name.endsWith(".xml") && !name.contains("/_") },
            maxBytesPerEntry = 8_000,
        )
        assertEquals(setOf("ppt/slides/slide1.xml"), entries.keys)
        assertTrue(entries.values.first().contains("Hello slide"))
    }

    @Test
    fun `column A is index 0 and AA is 26`() {
        assertEquals(0, columnIndexFromRef("A1"))
        assertEquals(25, columnIndexFromRef("Z1"))
        assertEquals(26, columnIndexFromRef("AA1"))
        assertEquals(0, columnIndexFromRef("a1"))
    }

    @Test
    fun `decodeXmlEntities resolves indic numeric references`() {
        val decoded = decodeXmlEntities("&#2361;&#2367;&#2344;&#2381;&#2342;&#2368;")
        assertTrue(decoded.contains("ह"))
        assertTrue(decoded.contains("ि"))
    }

    @Test
    fun `csv utf8 bom is stripped`() {
        val raw = "\uFEFFName,Amount\nWheat,2275"
        val text = formatCsvDocument(raw, maxChars = 2_000)
        assertTrue(text.contains("Name: Wheat"))
        assertTrue(text.contains("Amount: 2275"))
    }

    @Test
    fun `xlsx hindi shared strings decode to searchable rows`() {
        val shared = "<sst><si><t>&#2325;&#2367;&#2360;&#2366;&#2344;</t></si><si><t>Amount</t></si></sst>"
        val sheet = """
            <sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
              <row r="2"><c r="A1" t="s"><v>0</v></c><c r="B2"><v>2275</v></c></row>
            </sheetData>
        """.trimIndent()
        val workbook = """<workbook><sheets><sheet name="बिक्री" sheetId="1"/></sheets></workbook>"""
        val text = formatXlsxDocument(shared, workbook, listOf("Sheet 1" to sheet), maxChars = 4_000)
        assertTrue(text.contains("किसान"))
        assertTrue(text.contains("Amount: 2275"))
        assertTrue(text.contains("Sheet: बिक्री"))
    }

    @Test
    fun `pptx table cells are pipe joined`() {
        val xml = """
            <a:tbl>
              <a:tc><a:t>Crop</a:t></a:tc>
              <a:tc><a:t>MSP</a:t></a:tc>
              <a:tc><a:t>Wheat</a:t></a:tc>
              <a:tc><a:t>2275</a:t></a:tc>
            </a:tbl>
            <a:p><a:t>Notes</a:t></a:p>
        """.trimIndent()
        val text = parsePptxSlideXml(xml)
        assertTrue(text.contains("Crop | MSP"))
        assertTrue(text.contains("Wheat | 2275"))
        assertTrue(text.contains("Notes"))
    }

    @Test
    fun `office structure marker extracts sheet and slide`() {
        val chunk = "--- Sheet: Sales ---\nColumns: Date | Amount"
        assertEquals("Sheet: Sales", extractOfficeStructureMarker(chunk))
        assertEquals("Slide 2", extractOfficeStructureMarker("--- Slide 2 ---\nMSP"))
        assertEquals("Rows 1-25", extractOfficeStructureMarker("--- Rows 1-25 ---\nDate: 1"))
    }
}