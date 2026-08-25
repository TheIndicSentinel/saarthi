package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationContractTest {

    @Test
    fun `shortDocName matches headers and the session manifest`() {
        val raw = "Mallikarjuna Rao_NDA Agreement.pdf"
        val name = shortDocName(raw)
        val header = formatExcerptHeader(1, raw, "Term is 24 months.", chunkIndex = 0)
        val manifest = sessionManifestLine(listOf(raw))
        assertTrue(header.startsWith("[1] $name"))
        assertTrue(manifest.contains(name))
        assertFalse(header.contains(".pdf"))
        assertFalse(manifest.contains(".pdf"))
    }

    @Test
    fun `page marker becomes p-dot in the header`() {
        val header = formatExcerptHeader(
            2,
            "Account Statement.pdf",
            "--- Page 4 ---\nSalary credit 50,000",
            chunkIndex = 3,
        )
        assertEquals("[2] Account Statement · p.4\n", header)
    }

    @Test
    fun `page range uses pp`() {
        assertEquals("pp.5-7", extractPageRange("--- Page 5 ---\n...\n--- Page 7 ---"))
    }

    @Test
    fun `outline never invents a page even if markers exist`() {
        val header = formatExcerptHeader(
            1,
            "NDA.pdf",
            "--- Page 1 ---\nOutline",
            chunkIndex = -1,
        )
        assertEquals("[1] NDA\n", header)
        assertFalse(header.contains("p."))
    }

    @Test
    fun `txt without pages has name only`() {
        val header = formatExcerptHeader(1, "saarthi_debug.log.txt", "E/foo", chunkIndex = 0)
        assertEquals("[1] saarthi debug\n", header)
    }

    @Test
    fun `standard rules cover compare, no cite on In general, unread`() {
        val rules = ragCitationRules(compact = false)
        assertTrue(rules.contains("cite each file that contributed"))
        assertTrue(rules.contains("In general:' with no (Name, p.X) citation"))
        assertTrue(rules.contains("Never cite files listed as unreadable"))
        assertTrue(rules.contains("Consent is required (DPDP Act, p.3)."))
    }

    @Test
    fun `compact rules stay one paragraph and still ban unread and In general cites`() {
        val rules = ragCitationRules(compact = true)
        assertEquals(0, rules.trim().count { it == '\n' })
        assertTrue(rules.contains("In general:"))
        assertTrue(rules.contains("no citation"))
        assertTrue(rules.contains("Never cite unread"))
        assertTrue(rules.contains("cite each file"))
        assertFalse(rules.contains("• "))
    }

    @Test
    fun `strong match rules drop the ignore-the-document escape hatch`() {
        // G2: on a confident hit, the "if not about the document, ignore the
        // excerpts" clause is gone (it caused false refusals), but the core
        // citation contract stays intact.
        val rules = ragCitationRules(compact = false, strongMatch = true)
        assertTrue(rules.contains("matches these documents"))
        assertFalse(rules.contains("IGNORE"))
        assertFalse(rules.contains("NOT about the document"))
        assertTrue(rules.contains("cite each file that contributed"))
        assertTrue(rules.contains("Never cite files listed as unreadable"))
        assertTrue(rules.contains("Consent is required (DPDP Act, p.3)."))
    }

    @Test
    fun `weak match rules forbid refusing and keep the In general path`() {
        // G5: when the excerpts may not be relevant, the model must answer
        // generally rather than deflect.
        val rules = ragCitationRules(compact = false, strongMatch = false)
        assertTrue(rules.contains("Do NOT refuse"))
        assertTrue(rules.contains("In general:' with no (Name, p.X) citation"))
    }

    @Test
    fun `compact weak rules forbid refusing and stay one line`() {
        val rules = ragCitationRules(compact = true, strongMatch = false)
        assertEquals(0, rules.trim().count { it == '\n' })
        assertTrue(rules.contains("don't refuse"))
        assertTrue(rules.contains("In general:"))
        assertTrue(rules.contains("Never cite unread"))
    }

    @Test
    fun `unreadable intro forbids citing those files`() {
        assertTrue(UNREADABLE_FILES_INTRO.contains("do not cite them"))
    }

    @Test
    fun `known shortDocName examples`() {
        assertEquals(
            "Douglas Repair Maintenance",
            shortDocName("2015_Douglas_Repair-Maintenance-Mobile-Cell-Phones.pdf"),
        )
        assertEquals("saarthi debug", shortDocName("saarthi_debug.log.txt"))
        assertEquals("Dpdpact", shortDocName("Dpdpact.pdf"))
        assertEquals("MSP rates", shortDocName("MSP_rates.xlsx"))
        assertEquals("Kharif slides", shortDocName("Kharif_slides.pptx"))
    }
}
