package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryNamedStatuteMismatchTest {

    private val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()
    private val dpdpHash = "bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf"
    private val labourHash = "20250414714956973.pdf"
    private val dpdpUri = "content://dpdp"
    private val labourUri = "content://labour"

    @Test
    fun `extracts dpdp signals from query`() {
        val signals = extractNamedStatuteSignalsFromQuery(
            "What are penalties under the Digital Personal Data Protection Act, 2023?",
        )
        assertTrue(signals.contains("digital"))
        assertTrue(signals.contains("protection"))
    }

    @Test
    fun `extracts labour signals from document outline`() {
        val signals = extractNamedStatuteSignalsFromDocument(
            labourHash,
            "THE INDUSTRIAL DISPUTES ACT, 1947 — Amendment Bill",
            null,
        )
        assertTrue(signals.contains("industrial"))
        assertTrue(signals.contains("disputes"))
    }

    @Test
    fun `mismatch when dpdp question on labour scoped doc`() {
        val outline = mapOf(
            labourHash to "THE INDUSTRIAL DISPUTES ACT, 1947 — Amendment",
        )
        assertTrue(
            shouldEmitNamedStatuteDocumentMismatch(
                query = "What are fiduciary duties under the Digital Personal Data Protection Act?",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                restrictDocUris = setOf(labourUri),
                sessionDocs = listOf(labourUri to labourHash),
                outlineByDocName = outline,
                retrieved = listOf(
                    RetrievedChunk(
                        text = outline[labourHash]!!,
                        docName = labourHash,
                        score = 1.0,
                        chunkIndex = -1,
                        docUri = labourUri,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `no mismatch when query matches attached act`() {
        val outline = mapOf(
            dpdpHash to "THE DIGITAL PERSONAL DATA PROTECTION ACT, 2023",
        )
        assertFalse(
            shouldEmitNamedStatuteDocumentMismatch(
                query = "What are penalties under the Digital Personal Data Protection Act?",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                restrictDocUris = setOf(dpdpUri),
                sessionDocs = listOf(dpdpUri to dpdpHash),
                outlineByDocName = outline,
                retrieved = listOf(
                    RetrievedChunk(
                        text = outline[dpdpHash]!!,
                        docName = dpdpHash,
                        score = 1.0,
                        chunkIndex = -1,
                        docUri = dpdpUri,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `generic section question does not mismatch`() {
        assertFalse(
            shouldEmitNamedStatuteDocumentMismatch(
                query = "What are penalties in section 33?",
                turnMode = RagTurnMode.DOCUMENT_GROUNDED,
                restrictDocUris = setOf(labourUri),
                sessionDocs = listOf(labourUri to labourHash),
                outlineByDocName = emptyMap(),
                retrieved = emptyList(),
            ),
        )
    }

    @Test
    fun `mismatch message names both sides`() {
        val msg = buildNamedStatuteDocumentMismatchMessage(
            query = "Penalties under Digital Personal Data Protection Act",
            restrictDocUris = setOf(labourUri),
            sessionDocs = listOf(labourUri to labourHash),
            outlineByDocName = mapOf(labourHash to "INDUSTRIAL DISPUTES ACT amendment"),
            labels = labels,
        )
        assertTrue(msg.contains("attached document", ignoreCase = true))
        assertTrue(msg.contains("digital", ignoreCase = true) || msg.contains("protection", ignoreCase = true))
    }
}
