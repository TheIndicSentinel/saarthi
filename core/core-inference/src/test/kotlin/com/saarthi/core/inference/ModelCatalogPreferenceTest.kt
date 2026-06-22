package com.saarthi.core.inference

import com.saarthi.core.inference.model.DeviceProfile
import com.saarthi.core.inference.model.SocFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [ModelCatalog.preferredAutoModel] (the E4B auto-default policy). */
class ModelCatalogPreferenceTest {

    private val catalog = ModelCatalog()
    private val e4bId = "gemma4-e4b-it-litert"

    private fun profile(totalRamMb: Long) = DeviceProfile(
        totalRamMb = totalRamMb,
        availableRamMb = totalRamMb / 2,
        safeModelBudgetMb = totalRamMb / 2,
        availableStorageMb = 100_000,   // ample — storage gate never the limiter here
        cpuCores = 8,
        recommendedThreads = 4,
        hasVulkan = true,
        vulkanVersion = "1.3",
        gpuSafe = true,
        abi = "arm64-v8a",
        apiLevel = 34,
        manufacturer = "samsung",
        socModel = "SM8550",
        socFamily = SocFamily.GENERIC,
    )

    private val flagship = profile(11_000)   // FLAGSHIP (>=10GB)
    private val mid = profile(8_000)         // MID (6-10GB)

    @Test
    fun `flagship + charging + E4B downloaded prefers E4B`() {
        val pick = catalog.preferredAutoModel(flagship, setOf(e4bId), charging = true)
        assertEquals(e4bId, pick?.id)
    }

    @Test
    fun `flagship but not charging returns null`() {
        assertNull(catalog.preferredAutoModel(flagship, setOf(e4bId), charging = false))
    }

    @Test
    fun `E4B not downloaded returns null`() {
        assertNull(catalog.preferredAutoModel(flagship, emptySet(), charging = true))
    }

    @Test
    fun `mid tier never prefers E4B even when charging and downloaded`() {
        assertNull(catalog.preferredAutoModel(mid, setOf(e4bId), charging = true))
    }
}
