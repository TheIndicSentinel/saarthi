package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B1 — substance queries bypass meta routing; penalties use LIST shape. */
class SubstanceRoutingTest {

    @Test
    fun `penalty questions bypass meta route`() {
        assertTrue(bypassMetaForSubstanceQuery("What does document say about penalties"))
        assertNull(effectiveMetaRouteReason("What does document say about penalties", isFollowUp = false))
    }

    @Test
    fun `chapter count bypasses meta chapters token`() {
        assertTrue(bypassMetaForSubstanceQuery("How many chapters are there on total"))
        assertNull(effectiveMetaRouteReason("How many chapters are there on total", isFollowUp = false))
        assertEquals("chapters", RagDocumentRepository.metaRouteReason("How many chapters are there on total"))
    }

    @Test
    fun `overview still uses meta route`() {
        assertFalse(bypassMetaForSubstanceQuery("give an overview in short"))
        assertEquals("overview", effectiveMetaRouteReason("give an overview in short", isFollowUp = false))
    }

    @Test
    fun `list penalties bypasses meta list token`() {
        assertTrue(bypassMetaForSubstanceQuery("list all penalties in the schedule"))
        assertNull(effectiveMetaRouteReason("list all penalties in the schedule", isFollowUp = false))
    }

    @Test
    fun `penalty query uses LIST answer shape`() {
        assertEquals(
            RagAnswerShape.LIST,
            detectRagAnswerShape("What does document say about penalties", metaOverview = false),
        )
    }

    @Test
    fun `isPenaltyScheduleQuery matches Hindi and English penalty asks`() {
        assertTrue(isPenaltyScheduleQuery("What does document say about penalties"))
        assertTrue(isPenaltyScheduleQuery("दंड क्या है"))
        assertFalse(isPenaltyScheduleQuery("give an overview in short"))
    }
}
