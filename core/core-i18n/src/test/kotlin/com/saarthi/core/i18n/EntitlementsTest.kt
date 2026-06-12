package com.saarthi.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Entitlements] is the free-vs-Pro gate policy. These tests pin BOTH branches
 * (enforced and dormant) explicitly via the `enforced` parameter, so they hold
 * regardless of the current [Entitlements.ENFORCED] flag — including after it is
 * flipped to true when billing ships.
 */
class EntitlementsTest {

    // ── Dormant (beta default) — nothing is gated ──────────────────────────────

    @Test
    fun `dormant unlocks every feature for a free user`() {
        for (f in ProFeature.values()) {
            assertTrue("$f must be allowed while dormant",
                Entitlements.isAllowed(f, isPro = false, enforced = false))
        }
        assertEquals(Int.MAX_VALUE, Entitlements.maxDocuments(isPro = false, enforced = false))
        assertEquals(Int.MAX_VALUE, Entitlements.maxDocPages(isPro = false, enforced = false))
    }

    @Test
    fun `gating is enforced so testers and users see the free vs Pro flow`() {
        // Enforcement is ON: free-tier limits apply, a Pro unlock lifts them.
        // The unlock source (local button now, Play purchase later) is what
        // changes for production — not this flag.
        assertTrue("ENFORCED must be true so the gates actually apply", Entitlements.ENFORCED)
    }

    // ── Enforced — free users hit limits, Pro users do not ─────────────────────

    @Test
    fun `enforced free user is blocked and capped`() {
        assertFalse(Entitlements.isAllowed(ProFeature.VOICE_READ_ALOUD, isPro = false, enforced = true))
        assertEquals(Entitlements.FREE_MAX_DOCUMENTS,
            Entitlements.maxDocuments(isPro = false, enforced = true))
        assertEquals(Entitlements.FREE_MAX_DOC_PAGES,
            Entitlements.maxDocPages(isPro = false, enforced = true))
    }

    @Test
    fun `enforced Pro user is always allowed and uncapped`() {
        for (f in ProFeature.values()) {
            assertTrue("$f must be allowed for Pro",
                Entitlements.isAllowed(f, isPro = true, enforced = true))
        }
        assertEquals(Int.MAX_VALUE, Entitlements.maxDocuments(isPro = true, enforced = true))
        assertEquals(Int.MAX_VALUE, Entitlements.maxDocPages(isPro = true, enforced = true))
    }

    @Test
    fun `free-tier limits are sane`() {
        // The free PDF trial must still let the "wow" land — not zero, not huge.
        assertTrue(Entitlements.FREE_MAX_DOCUMENTS in 1..5)
        assertTrue(Entitlements.FREE_MAX_DOC_PAGES in 5..50)
    }
}
