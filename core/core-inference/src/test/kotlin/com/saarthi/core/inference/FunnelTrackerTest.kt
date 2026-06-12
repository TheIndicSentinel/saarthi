package com.saarthi.core.inference

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [FunnelTracker] is the conversion-funnel instrumentation. The contract that
 * matters: [FunnelTracker.track] records every time, [FunnelTracker.trackOnce]
 * records a "first_*" milestone at most once per process so a session's
 * activation isn't double-counted on every send. Output goes through the
 * [DebugLogger] object, which we mock to observe the calls.
 */
class FunnelTrackerTest {

    @Before
    fun setUp() {
        mockkObject(DebugLogger)
        every { DebugLogger.log(any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(DebugLogger)
    }

    @Test
    fun `track records every call`() {
        val tracker = FunnelTracker()
        tracker.track(FunnelEvent.PAYWALL_VIEWED)
        tracker.track(FunnelEvent.PAYWALL_VIEWED)
        verify(exactly = 2) { DebugLogger.log("FUNNEL", "paywall_viewed") }
    }

    @Test
    fun `trackOnce records a milestone only once`() {
        val tracker = FunnelTracker()
        repeat(5) { tracker.trackOnce(FunnelEvent.FIRST_CHAT_SENT) }
        verify(exactly = 1) { DebugLogger.log("FUNNEL", match { it.startsWith("first_chat_sent") }) }
    }

    @Test
    fun `trackOnce de-dupes per event, not globally`() {
        val tracker = FunnelTracker()
        tracker.trackOnce(FunnelEvent.FIRST_CHAT_SENT)
        tracker.trackOnce(FunnelEvent.FIRST_DOC_ATTACHED)
        tracker.trackOnce(FunnelEvent.FIRST_CHAT_SENT)   // ignored — already fired
        verify(exactly = 2) { DebugLogger.log("FUNNEL", any()) }
    }

    @Test
    fun `every funnel event has a non-blank stable id`() {
        // Guards the ids used for log grepping / dashboards from going empty.
        for (e in FunnelEvent.values()) {
            assert(e.id.isNotBlank()) { "Event ${e.name} has a blank id" }
        }
    }
}
