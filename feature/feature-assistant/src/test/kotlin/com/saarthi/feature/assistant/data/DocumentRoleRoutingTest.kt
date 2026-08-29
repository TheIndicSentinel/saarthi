package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4.1 — index-time document role routing. */
class DocumentRoleRoutingTest {

    @Test
    fun `encode and decode indexed document role`() {
        assertEquals("guide", encodeIndexedDocumentRole(DocumentRoleLabel.GUIDE))
        assertEquals(DocumentRoleLabel.GUIDE, decodeIndexedDocumentRole("guide"))
    }

    @Test
    fun `resolveDocumentRoleFromChunks prefers stamp`() {
        val chunks = listOf(
            entity(uri = "u1", name = "DPDP_Act.pdf", index = 0, text = "Section 1 body"),
            entity(
                uri = "u1",
                name = "DPDP_Act.pdf",
                index = DOCUMENT_ROLE_CHUNK_INDEX,
                text = "guide",
            ),
        )
        assertEquals(DocumentRoleLabel.GUIDE, resolveDocumentRoleFromChunks(chunks))
    }

    @Test
    fun `filterSubstanceContentChunks keeps primary for deictic act query`() {
        val guideUri = "guide-uri"
        val actUri = "act-uri"
        val docRoles = mapOf(guideUri to DocumentRoleLabel.GUIDE, actUri to null)
        val chunks = listOf(
            entity(uri = guideUri, name = "EY_Guide.pdf", index = 0, text = "guide penalties"),
            entity(uri = actUri, name = "DPDP_Act.pdf", index = 0, text = "Section 33 penalties"),
        )
        val route = QueryRoute(emptySet(), equalSlots = false, whichFile = false, thisDocument = false, "")
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
    fun `filterSubstanceContentChunks keeps primary when guide and act mixed`() {
        val guideUri = "guide-uri"
        val actUri = "act-uri"
        val docRoles = mapOf(
            guideUri to DocumentRoleLabel.GUIDE,
            actUri to null,
        )
        val chunks = listOf(
            entity(uri = guideUri, name = "EY_Guide.pdf", index = 0, text = "guide penalties"),
            entity(uri = actUri, name = "DPDP_Act.pdf", index = 0, text = "Section 33 penalties"),
        )
        val route = QueryRoute(emptySet(), equalSlots = false, whichFile = false, thisDocument = false, "")
        val filtered = filterSubstanceContentChunks(
            chunks,
            docRoles,
            "What are penalties in section 33",
            route,
            isFollowUp = false,
        )
        assertEquals(1, filtered.size)
        assertEquals(actUri, filtered.first().docUri)
    }

    @Test
    fun `filterSubstanceContentChunks unchanged for compare equal slots`() {
        val guideUri = "guide-uri"
        val actUri = "act-uri"
        val docRoles = mapOf(guideUri to DocumentRoleLabel.GUIDE, actUri to null)
        val chunks = listOf(
            entity(uri = guideUri, name = "Guide.pdf", index = 0, text = "guide"),
            entity(uri = actUri, name = "Act.pdf", index = 0, text = "act"),
        )
        val route = QueryRoute(emptySet(), equalSlots = true, whichFile = false, thisDocument = false, "")
        val filtered = filterSubstanceContentChunks(
            chunks,
            docRoles,
            "compare penalties in both documents",
            route,
            isFollowUp = false,
        )
        assertEquals(2, filtered.size)
    }

    @Test
    fun `orderDocUrisForStructuralSample prefers commentary on overview`() {
        val guideUri = "guide-uri"
        val actUri = "act-uri"
        val docRoles = mapOf(guideUri to DocumentRoleLabel.GUIDE, actUri to null)
        val route = QueryRoute(emptySet(), false, false, false, "")
        val ordered = orderDocUrisForStructuralSample(
            listOf(actUri, guideUri),
            docRoles,
            metaReason = "overview",
            whichFile = false,
            query = "give an overview",
            route = route,
        )
        assertEquals(guideUri, ordered.first())
    }

    @Test
    fun `orderDocUrisForStructuralSample prefers primary on section query`() {
        val guideUri = "guide-uri"
        val actUri = "act-uri"
        val docRoles = mapOf(guideUri to DocumentRoleLabel.GUIDE, actUri to null)
        val route = QueryRoute(emptySet(), false, false, false, "")
        val ordered = orderDocUrisForStructuralSample(
            listOf(guideUri, actUri),
            docRoles,
            metaReason = null,
            whichFile = false,
            query = "explain section 5 obligations",
            route = route,
        )
        assertEquals(actUri, ordered.first())
    }

    private fun entity(
        uri: String,
        name: String,
        index: Int,
        text: String,
    ) = RagChunkEntity(
        sessionId = "s1",
        docUri = uri,
        docName = name,
        mimeType = "text/plain",
        chunkIndex = index,
        text = text,
    )
}
