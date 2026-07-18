package com.saarthi.core.inference.engine

import com.saarthi.core.inference.model.DeviceTier
import com.saarthi.core.inference.model.PromptTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [gpuSafetyMarginMb], [isLargeTier]/[isCompactTier], and
 * [isGpuRestrictedToCompactOnLowTier] are the pure decision math behind the
 * GPU-admission and token-ladder-tier hardening passes: replacing the old
 * flat "avail < 3000MB" veto and flat per-tier margin with a
 * continuously-scaled reserve, and replacing name-matched tier
 * classification ("1b"/"compact"/"gemma 4" substrings) with
 * ModelEntry.promptTier (data-driven — see ModelCatalog). Getting the tier
 * classification wrong reproduces exactly the field bugs this project's
 * history is full of (Gemma 4 E4B/E2B token starvation, Kisan RAG failing
 * on the compact model's old 512-token cap), so this is real, load-bearing
 * logic, not just a legibility improvement. This is the only part of
 * either pass that's unit-testable at all — everything else lives inside
 * LiteRTInferenceEngine.initialize(), which needs the native engine and
 * has no coverage (no Robolectric in this project).
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

    // ── isLargeTier / isCompactTier ──────────────────────────────────────────

    @Test
    fun `LARGE promptTier is always large tier regardless of size`() {
        assertTrue(isLargeTier(PromptTier.LARGE, sizeMb = 100L))
    }

    @Test
    fun `COMPACT promptTier is never large tier even if the file is huge`() {
        // The explicit classification wins — a genuinely miscatalogued huge
        // "compact" model isn't silently upgraded via the size fallback,
        // which only applies to STANDARD (unclassified) models.
        assertFalse(isLargeTier(PromptTier.COMPACT, sizeMb = 5_000L))
    }

    @Test
    fun `STANDARD promptTier falls back to the sizeMb heuristic for large tier`() {
        assertFalse(isLargeTier(PromptTier.STANDARD, sizeMb = 1_500L))
        assertTrue(isLargeTier(PromptTier.STANDARD, sizeMb = 1_501L))
    }

    @Test
    fun `COMPACT promptTier is always compact tier regardless of size`() {
        assertTrue(isCompactTier(PromptTier.COMPACT, sizeMb = 5_000L))
    }

    @Test
    fun `LARGE promptTier is never compact tier even if the file is tiny`() {
        assertFalse(isCompactTier(PromptTier.LARGE, sizeMb = 100L))
    }

    @Test
    fun `STANDARD promptTier falls back to the sizeMb heuristic for compact tier`() {
        assertFalse(isCompactTier(PromptTier.STANDARD, sizeMb = 700L))
        assertTrue(isCompactTier(PromptTier.STANDARD, sizeMb = 699L))
    }

    @Test
    fun `a model can never be classified as both large and compact tier`() {
        val allCombinations = listOf(
            PromptTier.LARGE to 50L,
            PromptTier.COMPACT to 5_000L,
            PromptTier.STANDARD to 100L,
            PromptTier.STANDARD to 1_000L,
            PromptTier.STANDARD to 5_000L,
        )
        for ((tier, sizeMb) in allCombinations) {
            assertFalse(
                "tier=$tier sizeMb=$sizeMb must not be both large and compact",
                isLargeTier(tier, sizeMb) && isCompactTier(tier, sizeMb),
            )
        }
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
