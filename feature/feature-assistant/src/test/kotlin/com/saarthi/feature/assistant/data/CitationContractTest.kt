package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationContractTest {

    @Test
    fun `shortDocName matches headers and the session manifest`() {
        val raw = "Mallikarjuna Rao_NDA Agreement.pdf"
        val name = shortDocName(raw)
        val header = formatExcerptHeader(1, raw, "Term is 24 months.", chunkIndex = 0)
        val manifest = sessionManifestLine(listOf(name))
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
    fun `standard rules use Sources footer not per-claim cites`() {
        val rules = ragCitationRules(compact = false)
        assertTrue(rules.contains("Sources:"))
        assertTrue(rules.contains("Do NOT put (Name, p.X) on every bullet"))
        assertTrue(rules.contains("do not invent facts") || rules.contains("external standards"))
        assertTrue(rules.contains("Never cite files listed as unreadable"))
        assertTrue(rules.contains("Digital Personal Data Protection Act 2023 · page 17"))
        assertTrue(rules.contains("Use only information that appears in the excerpts"))
    }

    @Test
    fun `Hindi rules include localized excerpt-only grounding B3-3`() {
        val labels = SupportedLanguage.HINDI.citationDisplayLabels()
        val rules = ragCitationRules(compact = false, labels = labels)
        assertTrue(rules.contains(labels.excerptOnlyRule))
        assertFalse(rules.contains("GDPR"))
    }

    @Test
    fun `compact rules include excerpt-only grounding B3-3`() {
        val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()
        val rules = ragCitationRules(compact = true, labels = labels)
        assertTrue(rules.contains(labels.excerptOnlyRuleCompact))
    }

    @Test
    fun `Hindi rules use localized Sources header and worked example`() {
        val labels = SupportedLanguage.HINDI.citationDisplayLabels()
        val rules = ragCitationRules(compact = false, labels = labels)
        assertTrue(rules.contains("स्रोत:"))
        assertTrue(rules.contains(labels.citationRulesFooterExample()))
        assertFalse(rules.contains("Sources:"))
    }

    @Test
    fun `compact rules stay one paragraph and use Sources footer`() {
        val rules = ragCitationRules(compact = true)
        assertEquals(0, rules.trim().count { it == '\n' })
        assertTrue(rules.contains("Sources:"))
        assertTrue(rules.contains("external") || rules.contains("Never cite unread"))
        assertTrue(rules.contains("Never cite unread"))
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
        assertTrue(rules.contains("Sources:"))
        assertTrue(rules.contains("Never cite files listed as unreadable"))
    }

    @Test
    fun `weak match rules forbid inventing facts from outside excerpts`() {
        val rules = ragCitationRules(compact = false, strongMatch = false)
        assertTrue(rules.contains("do not invent facts") || rules.contains("external standards"))
    }

    @Test
    fun `compact weak rules stay one line and bound to excerpts`() {
        val rules = ragCitationRules(compact = true, strongMatch = false)
        assertEquals(0, rules.trim().count { it == '\n' })
        assertTrue(rules.contains("external") || rules.contains("Never cite unread"))
        assertTrue(rules.contains("Never cite unread"))
    }

    @Test
    fun `unreadable intro forbids citing those files`() {
        assertTrue(UNREADABLE_FILES_INTRO.contains("do not cite them"))
    }

    @Test
    fun `displayDocName skips outline boilerplate and uses act title`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val outline = "Document outline (auto-detected headings):\n- CHAPTER I\n- PRELIMINARY"
        val body = "--- Page 1 ---\nTHE DIGITAL PERSONAL DATA PROTECTION ACT, 2023"
        assertEquals(
            "Digital Personal Data",
            displayDocName(hash, outline, body),
        )
        assertFalse(displayDocName(hash, outline, body).lowercase().contains("outline"))
    }

    @Test
    fun `displayDocName falls back to Attached document for bare hash`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        assertEquals(FALLBACK_ATTACHED_DOC_LABEL, displayDocName(hash, null, null))
    }

    @Test
    fun `looksLikeInternalCitationLabel flags outline auto label`() {
        assertTrue(looksLikeInternalCitationLabel("Document outline auto"))
        assertTrue(looksLikeInternalCitationLabel("bf1f0e9f04e6fb4f8fef35e82c42"))
        assertFalse(looksLikeInternalCitationLabel("Account Statement"))
    }

    @Test
    fun `extractDocumentTitle finds THE ACT line`() {
        val title = extractDocumentTitle(
            "--- Page 1 ---\nTHE DIGITAL PERSONAL DATA PROTECTION ACT, 2023\nSection 1.",
        )
        assertTrue(title != null && title.contains("DIGITAL PERSONAL DATA"))
    }

    @Test
    fun `displayDocName uses outline heading for hash filenames`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        assertTrue(looksLikeContentStamp(hash))
        assertEquals(
            "Digital Personal Data",
            displayDocName(hash, "Digital Personal Data Protection Act, 2023"),
        )
        val header = formatExcerptHeader(
            1,
            hash,
            "Consent is required.",
            chunkIndex = 2,
            outlineText = "Digital Personal Data Protection Act, 2023",
        )
        assertTrue(header.startsWith("[1] Digital Personal Data"))
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

  // ── B3-1 document role heuristic ─────────────────────────────────────────

    @Test
    fun `documentRoleLabel detects guide from filename`() {
        assertEquals(
            DocumentRoleLabel.GUIDE,
            documentRoleLabel("EY_India_DPDP_Guide.pdf"),
        )
    }

    @Test
    fun `documentRoleLabel detects summary from filename`() {
        assertEquals(
            DocumentRoleLabel.SUMMARY,
            documentRoleLabel("DPDP_Act_one_page_summary.pdf"),
        )
    }

    @Test
    fun `documentRoleLabel detects sample from filename`() {
        assertEquals(
            DocumentRoleLabel.SAMPLE,
            documentRoleLabel(DemoDocument.NAME),
        )
    }

    @Test
    fun `documentRoleLabel detects sample from demo body`() {
        assertEquals(
            DocumentRoleLabel.SAMPLE,
            documentRoleLabel(
                "bf1f0e9f04e6fb4f8fef35e82c42.pdf",
                contentHint = DemoDocument.TEXT,
                contentCharCount = DemoDocument.TEXT.length,
            ),
        )
    }

    @Test
    fun `documentRoleLabel null for full act filename`() {
        assertEquals(
            null,
            documentRoleLabel(
                "Digital_Personal_Data_Protection_Act_2023.pdf",
                contentCharCount = 80_000,
            ),
        )
    }

    @Test
    fun `documentRoleLabel null when long body only mentions summary in a clause`() {
        val rightsClause = """
            --- Page 11 ---
            A Data Principal has the right to access a summary of the personal data
            being processed about them and to correction of inaccurate data.
        """.trimIndent()
        assertEquals(
            null,
            documentRoleLabel(
                "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf",
                contentHint = rightsClause,
                contentCharCount = 50_000,
            ),
        )
    }

    @Test
    fun `documentRoleLabel detects circular from Hindi opening`() {
        assertEquals(
            DocumentRoleLabel.CIRCULAR,
            documentRoleLabel(
                "office_note.pdf",
                contentHint = "कार्यालय परिपत्र\nधारा 12 के अंतर्गत जुर्माना",
                contentCharCount = 800,
            ),
        )
    }

    @Test
    fun `documentRoleLabel null for ordinary NDA`() {
        assertEquals(null, documentRoleLabel("Mallikarjuna_Rao_NDA_Agreement.pdf"))
    }

    @Test
    fun `documentRoleLabel summary from plain language opening on short doc`() {
        assertEquals(
            DocumentRoleLabel.SUMMARY,
            documentRoleLabel(
                "bf1f0e9f04e6fb4f8fef35e82c42.pdf",
                contentHint = "DPDP Act — plain-language summary for teams\nPurpose: …",
                contentCharCount = 2_400,
            ),
        )
    }

    @Test
    fun `displayCitationDocName adds localized Guide prefix`() {
        val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()
        val name = displayCitationDocName(
            "EY_India_DPDP_Guide.pdf",
            labels = labels,
        )
        assertTrue(name.startsWith("Guide:"))
        assertTrue(name.contains("EY India DPDP Guide") || name.contains("EY India"))
    }

    @Test
    fun `displayCitationDocName adds Hindi summary prefix`() {
        val labels = SupportedLanguage.HINDI.citationDisplayLabels()
        val name = displayCitationDocName(
            "DPDP_one_page_summary.pdf",
            labels = labels,
        )
        assertTrue(name.startsWith("सारांश:"))
    }

    @Test
    fun `displayCitationDocName leaves primary act without prefix`() {
        val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val body = "--- Page 1 ---\nTHE DIGITAL PERSONAL DATA PROTECTION ACT, 2023"
        val name = displayCitationDocName(hash, null, body, 80_000, labels)
        assertFalse(name.startsWith("Summary:"))
        assertFalse(name.startsWith("Guide:"))
        assertTrue(name.contains("Digital Personal Data"))
    }

    @Test
    fun `formatExcerptHeader includes role prefix when labels provided`() {
        val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()
        val header = formatExcerptHeader(
            1,
            DemoDocument.NAME,
            DemoDocument.TEXT,
            chunkIndex = 0,
            labels = labels,
        )
        assertTrue(header.contains("Sample:"))
    }

    // ── Wave 3 P12 citation labels ───────────────────────────────────────────

    @Test
    fun `body prose is not used as citation document title`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val prose = "the Board may, after giving the Data Principal an opportunity of being heard"
        assertTrue(looksLikeBodyProseLine(prose))
        assertEquals(
            FALLBACK_ATTACHED_DOC_LABEL,
            displayCitationDocName(hash, null, prose, prose.length),
        )
        assertTrue(looksLikeInternalCitationLabel("the Board may after giving"))
    }

    @Test
    fun `chapter header is section context not document title`() {
        assertTrue(isChapterOnlyCitationLabel("CHAPTER VII"))
        assertTrue(looksLikeInternalCitationLabel("Chapter VII"))
        val outline = "Document outline (auto-detected headings):\n- CHAPTER I\n- CHAPTER VII"
        assertEquals(
            FALLBACK_ATTACHED_DOC_LABEL,
            displayDocName("2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf", outline, null),
        )
    }

    @Test
    fun `extractCitationSectionHeading finds chapter line`() {
        val heading = extractCitationSectionHeading(
            "CHAPTER VIII\nPENALTIES AND ADJUDICATION\n33. Penalty for failure",
        )
        assertTrue(heading != null && heading.contains("Chapter VIII", ignoreCase = true))
    }

    @Test
    fun `txt without pages uses section heading in excerpt header`() {
        val header = formatExcerptHeader(
            1,
            "act.pdf",
            "CHAPTER II\nObligations of Data Fiduciary",
            chunkIndex = 3,
        )
        assertTrue(header.contains("Chapter II"))
        assertFalse(header.contains("location not marked"))
    }

    // ── Phase 2.3 title-eligibility residuals ───────────────────────────────

    @Test
    fun `cross-ref labels are not citation titles`() {
        assertTrue(looksLikeInternalCitationLabel("Code 2016"))
        assertTrue(looksLikeInternalCitationLabel("Income Tax Code 2016"))
        assertTrue(looksLikeInternalCitationLabel("sub-sections 12 and 14"))
        assertTrue(looksLikeInternalCitationLabel("Interim orders under Chapter VII"))
        assertFalse(looksLikeInternalCitationLabel("Digital Personal Data Protection Act, 2023"))
    }

    @Test
    fun `truncated fragments are not citation titles`() {
        assertTrue(looksLikeTruncatedTitleFragment("Penalties and adjudication of"))
        assertTrue(looksLikeTruncatedTitleFragment("Digital Personal Data Prote…"))
        assertFalse(looksLikeTruncatedTitleFragment("Digital Personal Data"))
    }

    @Test
    fun `displayDocName skips cross-ref opening line`() {
        val hash = "2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
        val crossRefBody =
            "--- Page 8 ---\n" +
                "Section 108. Inquiry on interim orders under Chapter VII of this Act."
        assertEquals(
            FALLBACK_ATTACHED_DOC_LABEL,
            displayDocName(hash, null, crossRefBody),
        )
    }

    @Test
    fun `extractCitationSectionHeading skips cross-ref line`() {
        val crossRef = "Section 108. Inquiry on interim orders under Chapter VII of this Act."
        assertNull(extractCitationSectionHeading(crossRef))
        val header = extractCitationSectionHeading(
            "CHAPTER VII\nAPPEAL AND ALTERNATE DISPUTE RESOLUTION",
        )
        assertTrue(header != null && header.contains("Chapter VII", ignoreCase = true))
    }
}
