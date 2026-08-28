package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3.1 — explicit lookup anchor extraction and support checks. */
class ExplicitLookupLaneTest {

    @Test
    fun `extracts section and amount anchors`() {
        val anchors = extractExplicitLookupAnchors("Penalty under section 33 up to ₹125 crore")
        assertTrue(anchors.sectionRefs.any { it.kind == "section" && it.token == "33" })
        assertTrue(anchors.amountSignatures.contains("125crore"))
    }

    @Test
    fun `lexical expansion includes section tokens`() {
        val expansion = explicitLookupLexicalExpansion("What does section 15 say")
        assertTrue(expansion.contains("section 15"))
    }

    @Test
    fun `anchor support fails when corpus lacks section`() {
        val anchors = extractExplicitLookupAnchors("section 99 penalties")
        assertFalse(hasExplicitLookupAnchorSupport(anchors, "General obligations on fiduciaries."))
    }

    @Test
    fun `anchor support passes when chapter header present`() {
        val anchors = extractExplicitLookupAnchors("highlights from chapter VII")
        val corpus = "CHAPTER VII\nAPPEAL AND ALTERNATE DISPUTE RESOLUTION"
        assertTrue(hasExplicitLookupAnchorSupport(anchors, corpus))
    }
}
