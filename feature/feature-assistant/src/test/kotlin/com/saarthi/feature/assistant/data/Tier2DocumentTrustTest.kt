package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 2 — document role trust, named/deictic routing, compare guardrails. */
class Tier2DocumentTrustTest {

    private val labels = SupportedLanguage.ENGLISH.citationDisplayLabels()
    private val actUri = "content://act"
    private val guideUri = "content://guide"
    private val actName = "Digital_Personal_Data_Protection_Act_2023.pdf"
    private val guideName = "EY_India_DPDP_Guide.pdf"
    private val docs = listOf(actUri to actName, guideUri to guideName)

    @Test
    fun `deictic act query prefers primary over guide in substance filter`() {
        val docRoles = mapOf(guideUri to DocumentRoleLabel.GUIDE, actUri to null)
        val chunks = listOf(
            ragEntity(guideUri, guideName, "Guide penalty amounts differ from the Act."),
            ragEntity(actUri, actName, "Section 33 penalty schedule under the Act."),
        )
        val route = QueryRoute(emptySet(), false, false, false, "")
        val filtered = filterSubstanceContentChunks(
            chunks,
            docRoles,
            "What are penalties in the act",
            route,
            isFollowUp = false,
        )
        assertEquals(1, filtered.size)
        assertEquals(actUri, filtered.first().docUri)
    }

    @Test
    fun `commentary query keeps guide chunks`() {
        val docRoles = mapOf(guideUri to DocumentRoleLabel.GUIDE, actUri to null)
        val chunks = listOf(
            ragEntity(guideUri, guideName, "Compliance journey overview."),
            ragEntity(actUri, actName, "Section 33 penalties."),
        )
        val route = QueryRoute(emptySet(), false, false, false, "")
        val filtered = filterSubstanceContentChunks(
            chunks,
            docRoles,
            "What does the guide say about compliance journey",
            route,
            isFollowUp = false,
        )
        assertEquals(1, filtered.size)
        assertEquals(guideUri, filtered.first().docUri)
    }

    @Test
    fun `ey consulting filename can label as guide`() {
        assertEquals(
            DocumentRoleLabel.GUIDE,
            documentRoleLabel("EY_India_DPDP_Compliance.pdf"),
        )
    }

    @Test
    fun `guide role prefix appears in citation display name`() {
        val name = displayCitationDocName(guideName, null, null, 2_000, labels)
        assertTrue(name.startsWith(labels.guideRolePrefix))
    }

    @Test
    fun `named role cue matches guide file`() {
        val matched = matchNamedDocs("What does the guide say about penalties", docs)
        assertEquals(setOf(guideUri), matched)
    }

    @Test
    fun `this document scopes to recency not stale active`() {
        val bank = "content://bank" to "bank_statement.pdf"
        val payslip = "content://pay" to "salary_slip.pdf"
        val session = listOf(bank, payslip)
        val decision = resolveRetrievalScope(
            query = "What is in this document",
            sessionDocs = session,
            attachmentUris = emptyList(),
            activeDocUri = bank.first,
            route = routeQuery("What is in this document", session),
            recencyDocUri = payslip.first,
        )
        assertEquals(RetrievalScope.ACTIVE_DOC, decision.scope)
        assertEquals(setOf(payslip.first), decision.restrictUris)
    }

    @Test
    fun `section contrast does not enable equal slots compare`() {
        assertFalse(shouldUseEqualSlotsCompare("compare section 5 vs section 10", docCount = 2))
        assertTrue(shouldUseEqualSlotsCompare("compare both files", docCount = 2))
    }

    @Test
    fun `single doc versus prose does not enable equal slots`() {
        val singleDoc = listOf(actUri to actName)
        assertFalse(shouldUseEqualSlotsCompare("Godrej vs the rules", docCount = 1))
        assertFalse(routeQuery("Godrej vs the rules", singleDoc).equalSlots)
    }

    private fun ragEntity(uri: String, name: String, text: String) =
        com.saarthi.core.memory.db.RagChunkEntity(
            sessionId = "s1",
            docUri = uri,
            docName = name,
            mimeType = "application/pdf",
            chunkIndex = 0,
            text = text,
        )
}
