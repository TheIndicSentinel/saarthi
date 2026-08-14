package com.saarthi.core.inference.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the mmap-aware pre-load RAM gate (Point 2): admission uses ~60% of
 * on-disk size ([estimateResidentModelMb]), matching the token ladder and
 * GPU memory-pressure estimate — not the old 70%-of-full-file-size reject
 * that falsely blocked capable devices.
 *
 * Pure helpers only — no Robolectric (same convention as GpuAdmissionPolicyTest).
 */
class ResidentRamGateTest {

    @Test
    fun `resident estimate is 60 percent of file size using integer math`() {
        assertEquals(0L, estimateResidentModelMb(0L))
        assertEquals(600L, estimateResidentModelMb(1_000L))
        // E4B-class ~3490MB → 2094MB resident (matches engine field comments)
        assertEquals(2_094L, estimateResidentModelMb(3_490L))
        // E2B-class ~2468MB → 1480MB
        assertEquals(1_480L, estimateResidentModelMb(2_468L))
    }

    @Test
    fun `rejects when available RAM is below resident estimate`() {
        assertTrue(isInsufficientRamForModelLoad(availableRamMb = 2_000L, sizeMb = 3_490L))
        assertTrue(isInsufficientRamForModelLoad(availableRamMb = 2_093L, sizeMb = 3_490L))
    }

    @Test
    fun `allows when available RAM meets or exceeds resident estimate`() {
        assertFalse(isInsufficientRamForModelLoad(availableRamMb = 2_094L, sizeMb = 3_490L))
        assertFalse(isInsufficientRamForModelLoad(availableRamMb = 3_000L, sizeMb = 3_490L))
    }

    @Test
    fun `0_6 gate is less strict than the old 0_70 file-size gate for E4B`() {
        // Old gate: avail < size * 0.70 → reject below ~2443MB for 3490MB file.
        // New gate: avail < size * 0.60 → reject below 2094MB.
        // Devices in the gap (2094..2442) were false rejects under the old gate.
        val sizeMb = 3_490L
        val oldRejectBelow = (sizeMb * 0.70).toLong() // 2443
        val midGapAvail = 2_200L
        assertTrue("sanity: mid-gap is below old 70% threshold", midGapAvail < oldRejectBelow)
        assertFalse(
            "mmap-aware gate must allow the mid-gap device the old 70% gate rejected",
            isInsufficientRamForModelLoad(midGapAvail, sizeMb),
        )
    }

    @Test
    fun `compact model still rejects when free RAM is tiny`() {
        // Compact ~584MB → resident 350MB
        assertEquals(350L, estimateResidentModelMb(584L))
        assertTrue(isInsufficientRamForModelLoad(availableRamMb = 300L, sizeMb = 584L))
        assertFalse(isInsufficientRamForModelLoad(availableRamMb = 350L, sizeMb = 584L))
    }
}
