package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase A2 — meta route whitelist for substance/absence/mechanism asks. */
class PhaseAMetaRoutingTest {

    @Test
    fun `oceans mechanism question bypasses meta`() {
        val q = "How do oceans affect Earth's climate system?"
        assertTrue(bypassMetaForSubstanceQuery(q))
        assertNull(effectiveMetaRouteReason(q, isFollowUp = false))
    }

    @Test
    fun `topics not discussed bypasses meta topics token`() {
        val q = "Which topics related to climate change are not discussed in the document?"
        assertTrue(bypassMetaForSubstanceQuery(q))
        assertNull(effectiveMetaRouteReason(q, isFollowUp = false))
    }

    @Test
    fun `key conclusions about climate bypasses meta conclusions token`() {
        val q = "What key conclusions does the document present about Earth's changing climate"
        assertTrue(bypassMetaForSubstanceQuery(q))
        assertNull(effectiveMetaRouteReason(q, isFollowUp = false))
    }

    @Test
    fun `earth system summarize bypasses meta summarize`() {
        val q =
            "Summarize the document's explanation of how different parts of the Earth system interact to influence climate."
        assertTrue(bypassMetaForSubstanceQuery(q))
        assertNull(effectiveMetaRouteReason(q, isFollowUp = false))
    }

    @Test
    fun `bare overview still uses meta route`() {
        assertFalse(bypassMetaForSubstanceQuery("give an overview in short"))
        assertEquals("overview", effectiveMetaRouteReason("give an overview in short", isFollowUp = false))
    }

    @Test
    fun `feedback examples bypasses meta`() {
        val q = "Find the section that discusses feedback mechanisms. What examples are given?"
        assertTrue(bypassMetaForSubstanceQuery(q))
        assertNull(effectiveMetaRouteReason(q, isFollowUp = false))
    }
}
