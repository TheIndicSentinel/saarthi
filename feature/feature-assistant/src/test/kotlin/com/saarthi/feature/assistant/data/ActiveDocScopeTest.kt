package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkDao
import com.saarthi.core.memory.db.RagChunkFtsSearch
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1-1 — active-document retrieval scope resolver. */
class ActiveDocScopeTest {

    private val bank = "content://bank" to "January Bank Statement"
    private val payslip = "content://pay" to "February Payslip"

    private fun route(
        query: String,
        docs: List<Pair<String, String>> = listOf(bank, payslip),
        named: Set<String> = emptySet(),
        equalSlots: Boolean = false,
        whichFile: Boolean = false,
        thisDocument: Boolean = false,
    ): QueryRoute = QueryRoute(
        namedDocUris = named,
        equalSlots = equalSlots,
        whichFile = whichFile,
        thisDocument = thisDocument,
        expandedQuery = query,
    )

    @Test
    fun `follow-up on multi-doc session restricts to active document`() {
        val decision = resolveRetrievalScope(
            query = "what is the total credit amount",
            sessionDocs = listOf(bank, payslip),
            attachmentUris = emptyList(),
            activeDocUri = payslip.first,
            route = route("what is the total credit amount"),
        )
        assertEquals(RetrievalScope.ACTIVE_DOC, decision.scope)
        assertEquals(setOf(payslip.first), decision.restrictUris)
    }

    @Test
    fun `single-doc session restricts to that document`() {
        val decision = resolveRetrievalScope(
            query = "what are the penalties",
            sessionDocs = listOf(bank),
            attachmentUris = emptyList(),
            activeDocUri = null,
            route = route("what are the penalties", docs = listOf(bank)),
        )
        assertEquals(RetrievalScope.ACTIVE_DOC, decision.scope)
        assertEquals(setOf(bank.first), decision.restrictUris)
    }

    @Test
    fun `attach overview scopes to newest file in batch`() {
        val uris = listOf(bank.first, payslip.first)
        val decision = resolveRetrievalScope(
            query = ATTACH_BRIEF_OVERVIEW_QUERY,
            sessionDocs = listOf(bank, payslip),
            attachmentUris = uris,
            activeDocUri = bank.first,
            route = route(ATTACH_BRIEF_OVERVIEW_QUERY),
        )
        assertEquals(RetrievalScope.ATTACH_OVERVIEW, decision.scope)
        assertEquals(setOf(payslip.first), decision.restrictUris)
    }

    @Test
    fun `substantive attach turn scopes to all this-turn files`() {
        val uris = listOf(bank.first, payslip.first)
        val decision = resolveRetrievalScope(
            query = "what is the penalty clause",
            sessionDocs = listOf(bank, payslip),
            attachmentUris = uris,
            activeDocUri = bank.first,
            route = route("what is the penalty clause"),
        )
        assertEquals(RetrievalScope.THIS_TURN, decision.scope)
        assertEquals(uris.toSet(), decision.restrictUris)
    }

    @Test
    fun `compare query uses full session corpus`() {
        val decision = resolveRetrievalScope(
            query = "compare both documents",
            sessionDocs = listOf(bank, payslip),
            attachmentUris = emptyList(),
            activeDocUri = payslip.first,
            route = route("compare both documents", equalSlots = true),
        )
        assertEquals(RetrievalScope.SESSION, decision.scope)
        assertTrue(decision.restrictUris.isEmpty())
    }

    @Test
    fun `which-file query uses full session corpus`() {
        val decision = resolveRetrievalScope(
            query = "which file mentions salary",
            sessionDocs = listOf(bank, payslip),
            attachmentUris = emptyList(),
            activeDocUri = payslip.first,
            route = route("which file mentions salary", whichFile = true),
        )
        assertEquals(RetrievalScope.SESSION, decision.scope)
        assertTrue(decision.restrictUris.isEmpty())
    }

    @Test
    fun `explicit all-files query uses full session corpus`() {
        val decision = resolveRetrievalScope(
            query = "summarise all files in this chat",
            sessionDocs = listOf(bank, payslip),
            attachmentUris = emptyList(),
            activeDocUri = payslip.first,
            route = route("summarise all files in this chat"),
        )
        assertEquals(RetrievalScope.SESSION, decision.scope)
        assertTrue(decision.restrictUris.isEmpty())
    }

    @Test
    fun `multi-doc session without active pointer searches full corpus`() {
        val decision = resolveRetrievalScope(
            query = "what is the penalty",
            sessionDocs = listOf(bank, payslip),
            attachmentUris = emptyList(),
            activeDocUri = null,
            route = route("what is the penalty"),
            recencyDocUri = payslip.first,
        )
        assertEquals(RetrievalScope.SESSION, decision.scope)
        assertTrue(decision.restrictUris.isEmpty())
    }

    @Test
    fun `named file query restricts to matched documents`() {
        val decision = resolveRetrievalScope(
            query = "what is in the payslip",
            sessionDocs = listOf(bank, payslip),
            attachmentUris = emptyList(),
            activeDocUri = bank.first,
            route = route("what is in the payslip", named = setOf(payslip.first)),
        )
        assertEquals(RetrievalScope.NAMED, decision.scope)
        assertEquals(setOf(payslip.first), decision.restrictUris)
    }

    @Test
    fun `resolveActiveDocUri prefers cached pointer over older indexed file`() {
        val repo = RagDocumentRepository(mockk<RagChunkDao>(relaxed = true), mockk<RagChunkFtsSearch>(relaxed = true))
        val sessionId = "session-1"
        val docs = listOf(
            SessionRagDocument(uri = bank.first, name = bank.second, lastIndexedAt = 100L),
            SessionRagDocument(uri = payslip.first, name = payslip.second, lastIndexedAt = 200L),
        )
        repo.setActiveDocUri(sessionId, bank.first)
        assertEquals(bank.first, repo.resolveActiveDocUri(sessionId, docs))
    }

    @Test
    fun `resolveActiveDocUri falls back to most recently indexed`() {
        val repo = RagDocumentRepository(mockk<RagChunkDao>(relaxed = true), mockk<RagChunkFtsSearch>(relaxed = true))
        val sessionId = "session-2"
        val docs = listOf(
            SessionRagDocument(uri = bank.first, name = bank.second, lastIndexedAt = 100L),
            SessionRagDocument(uri = payslip.first, name = payslip.second, lastIndexedAt = 200L),
        )
        assertEquals(payslip.first, repo.resolveActiveDocUri(sessionId, docs))
    }

    @Test
    fun `follow-up carry scopes named file from merged prior plus current`() {
        val payslip = "content://pay" to "February Payslip"
        val bank = "content://bank" to "January Bank Statement"
        val prior = "Is the salary credit mentioned in the payslip"
        val current = "what about penalties"
        val routingQuery = followUpScopeRoutingQuery(current, prior)
        val route = route(
            routingQuery,
            docs = listOf(bank, payslip),
            named = routeQuery(routingQuery, listOf(bank, payslip)).namedDocUris,
        )
        val decision = resolveRetrievalScope(
            query = routingQuery,
            sessionDocs = listOf(bank, payslip),
            attachmentUris = emptyList(),
            activeDocUri = bank.first,
            route = route,
        )
        assertEquals(RetrievalScope.NAMED, decision.scope)
        assertEquals(setOf(payslip.first), decision.restrictUris)
    }
}
