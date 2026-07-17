package com.saarthi.core.inference.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ModelEntry.isSafeFor() is the gate deciding which multi-GB models get
 * offered to a device at all — get this wrong and a user either never sees
 * a model their phone can handle, or downloads one that can't fit. The
 * storage-buffer formula here directly encodes a field-confirmed bug fix
 * (HuggingFace file-size drift up to 513.9MB against the catalog's stale
 * estimate) — these tests pin that formula down so it can't silently
 * regress back to the flat, drift-blind 500MB buffer.
 */
class ModelEntryTest {

    private fun profile(
        totalRamMb: Long = 8_000,
        availableStorageMb: Long = 10_000,
    ) = DeviceProfile(
        totalRamMb = totalRamMb,
        availableRamMb = totalRamMb / 2,
        safeModelBudgetMb = totalRamMb / 2,
        availableStorageMb = availableStorageMb,
        cpuCores = 8,
        recommendedThreads = 4,
        hasVulkan = true,
        vulkanVersion = "1.1.0",
        gpuSafe = true,
        abi = "arm64-v8a",
        apiLevel = 34,
        manufacturer = "Google",
    )

    private fun model(
        fileSizeBytes: Long = 2_500L * 1_048_576L, // 2500 MB
        requiredTier: DeviceTier = DeviceTier.MID,
    ) = ModelEntry(
        id = "test-model",
        displayName = "Test Model",
        description = "",
        downloadUrl = "https://huggingface.co/org/repo/resolve/abc123/test-model.litertlm",
        fileSizeBytes = fileSizeBytes,
        engineType = EngineType.LITERT,
        requiredTier = requiredTier,
    )

    // ── Tier gate ────────────────────────────────────────────────────────────

    @Test
    fun `device tier below required tier is unsafe`() {
        val entry = model(requiredTier = DeviceTier.FLAGSHIP)
        val lowTierProfile = profile(totalRamMb = 4_000) // LOW tier
        assertFalse(entry.isSafeFor(lowTierProfile))
    }

    @Test
    fun `device tier exactly matching required tier is safe`() {
        val entry = model(requiredTier = DeviceTier.MID)
        val midTierProfile = profile(totalRamMb = 6_000, availableStorageMb = 10_000) // exactly MID boundary
        assertTrue(entry.isSafeFor(midTierProfile))
    }

    @Test
    fun `device tier above required tier is safe`() {
        val entry = model(requiredTier = DeviceTier.LOW)
        val flagshipProfile = profile(totalRamMb = 12_000, availableStorageMb = 10_000)
        assertTrue(entry.isSafeFor(flagshipProfile))
    }

    // ── Storage gate ─────────────────────────────────────────────────────────

    @Test
    fun `insufficient storage is unsafe even when tier is satisfied`() {
        val entry = model(fileSizeBytes = 2_500L * 1_048_576L) // 2500MB, needs 2500+500=3000MB
        val tightProfile = profile(availableStorageMb = 2_900) // short by 100MB
        assertFalse(entry.isSafeFor(tightProfile))
    }

    @Test
    fun `sufficient storage with margin is safe`() {
        val entry = model(fileSizeBytes = 2_500L * 1_048_576L)
        val roomyProfile = profile(availableStorageMb = 5_000)
        assertTrue(entry.isSafeFor(roomyProfile))
    }

    @Test
    fun `storage check is inclusive at the exact boundary`() {
        // 2500MB model, 500MB floor buffer (5% of 2500 = 125MB, floor wins) -> needs exactly 3000MB.
        val entry = model(fileSizeBytes = 2_500L * 1_048_576L)
        val exactProfile = profile(availableStorageMb = 3_000)
        assertTrue("availableStorageMb == fileSizeMb + buffer must pass (condition is strictly <)", entry.isSafeFor(exactProfile))

        val oneShortProfile = profile(availableStorageMb = 2_999)
        assertFalse(entry.isSafeFor(oneShortProfile))
    }

    // ── Storage buffer scaling (the field-confirmed drift fix) ─────────────────

    @Test
    fun `small model uses the flat 500MB floor, not 5 percent`() {
        // 1000MB model: 5% = 50MB, floor 500MB wins -> needs 1500MB total.
        val entry = model(fileSizeBytes = 1_000L * 1_048_576L)
        assertTrue(entry.isSafeFor(profile(availableStorageMb = 1_500)))
        assertFalse(entry.isSafeFor(profile(availableStorageMb = 1_499)))
    }

    @Test
    fun `large model scales its buffer to 5 percent instead of the flat floor`() {
        // 20000MB (20GB) hypothetical model: 5% = 1000MB > 500MB floor,
        // so the buffer must scale, not stay pinned at 500MB.
        val entry = model(fileSizeBytes = 20_000L * 1_048_576L)
        // Needs 20000 + 1000 = 21000MB. 20500MB (flat-buffer expectation) must NOT be enough.
        assertFalse(
            "A flat 500MB buffer would incorrectly pass this — the buffer must scale with model size",
            entry.isSafeFor(profile(availableStorageMb = 20_500)),
        )
        assertTrue(entry.isSafeFor(profile(availableStorageMb = 21_000)))
    }

    @Test
    fun `buffer scaling absorbs the confirmed field drift case`() {
        // Real field case: Gemma 3n E4B catalog estimate drifted from HuggingFace's
        // actual served size by 513.9MB. A model this size (~4200MB) needs a
        // buffer bigger than the flat 500MB floor to have any chance of
        // absorbing drift of that magnitude with room to spare.
        val entry = model(fileSizeBytes = 4_200L * 1_048_576L)
        val bufferMb = maxOf(500, entry.fileSizeMb / 20)
        assertTrue("Buffer must exceed the flat 500MB floor for a model this large", bufferMb > 500)
    }

    // ── Computed properties ──────────────────────────────────────────────────

    @Test
    fun `fileSizeMb truncates bytes to whole megabytes`() {
        val entry = model(fileSizeBytes = 2_583_085_056L) // real Gemma 4 catalog value
        assertEquals(2463, entry.fileSizeMb) // 2_583_085_056 / 1_048_576 = 2463.41... -> 2463 (integer division)
    }

    @Test
    fun `fileName is the last path segment of downloadUrl`() {
        val entry = ModelEntry(
            id = "x",
            displayName = "X",
            description = "",
            downloadUrl = "https://huggingface.co/org/repo/resolve/deadbeef/gemma-4-E2B-it.litertlm",
            fileSizeBytes = 1L,
            engineType = EngineType.LITERT,
            requiredTier = DeviceTier.LOW,
        )
        assertEquals("gemma-4-E2B-it.litertlm", entry.fileName)
    }
}
