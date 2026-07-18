package com.saarthi.core.inference.engine

import com.saarthi.core.inference.model.DeviceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [gpuSafetyMarginMb], [isCompactModel], and [isGpuRestrictedToCompactOnLowTier]
 * are the pure decision math behind the GPU-admission hardening pass: replacing
 * the old flat "avail < 3000MB" veto and flat per-tier margin with a
 * continuously-scaled reserve, and gating GPU to the compact model on
 * LOW/MINIMAL-tier (or OS-flagged low-RAM) devices. This is the only part of
 * that pass that's unit-testable at all — everything else lives inside
 * LiteRTInferenceEngine.initialize(), which needs the native engine and has no
 * coverage (no Robolectric in this project).
 */
class GpuAdmissionPolicyTest {

    // ── gpuSafetyMarginMb: anchors must match the old, field-validated values ──

    @Test
    fun `at or below the LOW floor the margin is the flat 1800MB anchor`() {
        assertEquals(1_800L, gpuSafetyMarginMb(3_500L))
        assertEquals(1_800L, gpuSafetyMarginMb(2_000L))
        assertEquals(1_800L, gpuSafetyMarginMb(500L))
    }

    @Test
    fun `at the LOW-MID boundary the margin is exactly the 1400MB anchor`() {
        assertEquals(1_400L, gpuSafetyMarginMb(6_000L))
    }

    @Test
    fun `at or above the MID-FLAGSHIP boundary the margin floors at 1200MB and never drops further`() {
        assertEquals(1_200L, gpuSafetyMarginMb(10_000L))
        // Real field-log device (SM8550, ~11GB) the 1200MB anchor was
        // validated against — must land on the same floor, not something
        // extrapolated below it.
        assertEquals(1_200L, gpuSafetyMarginMb(11_044L))
        // A much larger device must not need MORE margin than the
        // validated 11GB case, nor less than the 1200MB floor.
        assertEquals(1_200L, gpuSafetyMarginMb(16_000L))
    }

    @Test
    fun `margin scales continuously between anchors instead of stepping`() {
        // 4750MB is the midpoint of the 3500-6000 LOW band: margin must sit
        // strictly between 1800 and 1400, not jump to either endpoint.
        val lowBandMid = gpuSafetyMarginMb(4_750L)
        assertTrue("expected strictly between 1400 and 1800, was $lowBandMid", lowBandMid in 1_401L..1_799L)

        // 8000MB is the midpoint of the 6000-10000 MID band: margin must
        // sit strictly between 1400 and 1200.
        val midBandMid = gpuSafetyMarginMb(8_000L)
        assertTrue("expected strictly between 1200 and 1400, was $midBandMid", midBandMid in 1_201L..1_399L)
    }

    @Test
    fun `a 6point1GB and a 9point9GB device no longer get the identical margin`() {
        // This was the concrete bug the flat 3-step lookup had: both of
        // these landed in MID and got the exact same 1400MB margin.
        val justAboveMid = gpuSafetyMarginMb(6_100L)
        val justBelowFlagship = gpuSafetyMarginMb(9_900L)
        assertTrue(
            "6.1GB ($justAboveMid) must get a larger margin than 9.9GB ($justBelowFlagship)",
            justAboveMid > justBelowFlagship,
        )
    }

    @Test
    fun `margin is monotonically non-increasing as total RAM grows`() {
        val samples = longArrayOf(3_000, 3_500, 4_000, 5_000, 6_000, 7_000, 8_000, 9_000, 10_000, 12_000, 20_000)
        for (i in 1 until samples.size) {
            val prev = gpuSafetyMarginMb(samples[i - 1])
            val curr = gpuSafetyMarginMb(samples[i])
            assertTrue(
                "margin must never increase as totalRamMb grows: ${samples[i - 1]}MB->$prev vs ${samples[i]}MB->$curr",
                curr <= prev,
            )
        }
    }

    // ── isCompactModel ──────────────────────────────────────────────────────

    @Test
    fun `a small file under 700MB is compact regardless of name`() {
        assertTrue(isCompactModel("mystery-model", 500L))
    }

    @Test
    fun `a name containing 1b is compact regardless of size`() {
        assertTrue(isCompactModel("gemma 3 · 1b compact", 3_000L))
    }

    @Test
    fun `a name containing compact is compact regardless of size`() {
        assertTrue(isCompactModel("gemma compact & fast", 3_000L))
    }

    @Test
    fun `a large named model is not compact`() {
        assertFalse(isCompactModel("gemma 4 · best quality", 3_659L))
    }

    // ── isGpuRestrictedToCompactOnLowTier ───────────────────────────────────

    @Test
    fun `LOW tier with a non-compact model is restricted to CPU`() {
        assertTrue(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.LOW, isLowRamDevice = false, isCompactModel = false,
            ),
        )
    }

    @Test
    fun `MINIMAL tier with a non-compact model is restricted to CPU`() {
        assertTrue(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.MINIMAL, isLowRamDevice = false, isCompactModel = false,
            ),
        )
    }

    @Test
    fun `LOW tier with the compact model is NOT restricted`() {
        assertFalse(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.LOW, isLowRamDevice = false, isCompactModel = true,
            ),
        )
    }

    @Test
    fun `MID tier with a non-compact model is not restricted by tier alone`() {
        assertFalse(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.MID, isLowRamDevice = false, isCompactModel = false,
            ),
        )
    }

    @Test
    fun `FLAGSHIP tier with a non-compact model is not restricted`() {
        assertFalse(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.FLAGSHIP, isLowRamDevice = false, isCompactModel = false,
            ),
        )
    }

    @Test
    fun `isLowRamDevice widens the restriction to a MID-tier device with a non-compact model`() {
        // This is the whole point of folding isLowRamDevice in: it can only
        // ever ADD devices to the restriction, never remove them.
        assertTrue(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.MID, isLowRamDevice = true, isCompactModel = false,
            ),
        )
    }

    @Test
    fun `isLowRamDevice never overrides a compact model back into restriction`() {
        // isLowRamDevice is additive on the tier/RAM axis, not a separate
        // veto that ignores the model classification.
        assertFalse(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.FLAGSHIP, isLowRamDevice = true, isCompactModel = true,
            ),
        )
    }

    @Test
    fun `isLowRamDevice alone on FLAGSHIP tier still restricts a non-compact model`() {
        // A device the OS flags as low-RAM gets the conservative treatment
        // even if its raw totalRamMb happens to classify as FLAGSHIP.
        assertTrue(
            isGpuRestrictedToCompactOnLowTier(
                tier = DeviceTier.FLAGSHIP, isLowRamDevice = true, isCompactModel = false,
            ),
        )
    }
}
