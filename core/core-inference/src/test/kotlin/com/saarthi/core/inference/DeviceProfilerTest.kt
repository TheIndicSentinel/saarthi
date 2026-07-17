package com.saarthi.core.inference

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.StatFs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * DeviceProfiler feeds the two gates that decide whether a multi-GB model
 * gets offered to a device (ModelEntry.isSafeFor, tier-based) and whether it
 * gets loaded (LiteRTInferenceEngine, availableRamMb-based) — a wrong budget
 * calculation here either starves a capable device of models it could
 * actually run, or lets a struggling device attempt a load that will OOM.
 * Zero prior coverage existed for this class.
 *
 * ActivityManager.getMemoryInfo() and StatFs are both mocked (RAM/storage are
 * runtime-queried, not deterministic across whatever machine runs this test)
 * so the tier-multiplier and cap math can be pinned down exactly.
 */
class DeviceProfilerTest {

    private lateinit var mockContext: Context
    private lateinit var mockActivityManager: ActivityManager
    private lateinit var mockPackageManager: PackageManager
    private lateinit var profiler: DeviceProfiler

    private var totalMemBytes: Long = 8_000L * 1_048_576L
    private var availMemBytes: Long = 4_000L * 1_048_576L

    @Before
    fun setUp() {
        mockActivityManager = mockk()
        mockPackageManager = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)

        every { mockContext.getSystemService(Context.ACTIVITY_SERVICE) } returns mockActivityManager
        every { mockContext.packageManager } returns mockPackageManager
        // A real, always-existing path — StatFs's methods are mocked below via
        // mockkConstructor, but the constructor call itself still runs first.
        every { mockContext.filesDir } returns File(System.getProperty("java.io.tmpdir"))
        every { mockPackageManager.hasSystemFeature(any<String>()) } returns false

        every { mockActivityManager.getMemoryInfo(any()) } answers {
            val info = firstArg<ActivityManager.MemoryInfo>()
            info.totalMem = totalMemBytes
            info.availMem = availMemBytes
        }

        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBlocksLong } returns 5_000L
        every { anyConstructed<StatFs>().blockSizeLong } returns 1_048_576L // 1MB blocks -> 5000MB available

        profiler = DeviceProfiler(mockContext)
    }

    @After
    fun tearDown() {
        unmockkConstructor(StatFs::class)
    }

    // ── RAM budget: tier-aware multiplier ───────────────────────────────────

    @Test
    fun `flagship tier (10GB+) uses 85 percent of available RAM`() {
        totalMemBytes = 12_000L * 1_048_576L
        availMemBytes = 8_000L * 1_048_576L
        // budgetFromAvail = 8000*85/100=6800; budgetFromTotal=12000*60/100=7200; min=6800
        assertEquals(6_800L, profiler.profile().safeModelBudgetMb)
    }

    @Test
    fun `mid tier (6-10GB) uses 75 percent of available RAM`() {
        totalMemBytes = 8_000L * 1_048_576L
        availMemBytes = 4_000L * 1_048_576L
        // budgetFromAvail = 4000*75/100=3000; budgetFromTotal=8000*60/100=4800; min=3000
        assertEquals(3_000L, profiler.profile().safeModelBudgetMb)
    }

    @Test
    fun `below mid tier (under 6GB total) uses 65 percent of available RAM`() {
        totalMemBytes = 4_000L * 1_048_576L
        availMemBytes = 2_000L * 1_048_576L
        // budgetFromAvail = 2000*65/100=1300; budgetFromTotal=4000*60/100=2400; min=1300
        assertEquals(1_300L, profiler.profile().safeModelBudgetMb)
    }

    @Test
    fun `multiplier switches exactly at the 10000MB flagship boundary`() {
        totalMemBytes = 9_999L * 1_048_576L
        availMemBytes = 4_000L * 1_048_576L
        // Just under the flagship threshold -> 75% multiplier still applies.
        // budgetFromAvail = 4000*75/100=3000; budgetFromTotal=9999*60/100=5999; min=3000
        assertEquals(3_000L, profiler.profile().safeModelBudgetMb)

        totalMemBytes = 10_000L * 1_048_576L
        // Exactly at the threshold -> 85% multiplier now applies.
        // budgetFromAvail = 4000*85/100=3400; budgetFromTotal=10000*60/100=6000; min=3400
        assertEquals(3_400L, profiler.profile().safeModelBudgetMb)
    }

    @Test
    fun `budget is capped at 60 percent of total RAM even with abundant available RAM`() {
        // A freshly-booted device can report almost all RAM as "available" —
        // the 60%-of-total cap exists specifically so the budget doesn't
        // balloon toward ~100% of total RAM in that scenario, starving the
        // OS/GPU driver/background apps of headroom.
        totalMemBytes = 8_000L * 1_048_576L
        availMemBytes = 7_800L * 1_048_576L // nearly all of total reported as free
        // budgetFromAvail = 7800*75/100=5850; budgetFromTotal=8000*60/100=4800; min=4800 (cap wins)
        assertEquals(4_800L, profiler.profile().safeModelBudgetMb)
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    @Test
    fun `storage is available blocks times block size, in MB`() {
        assertEquals(5_000L, profiler.profile().availableStorageMb) // 5000 blocks * 1MB blocks
    }

    // ── Thread count ─────────────────────────────────────────────────────────

    @Test
    fun `recommendedThreads stays within the documented 2 to 4 clamp on any host`() {
        // Deliberately does not assert an exact value — Runtime.availableProcessors()
        // reflects whatever machine runs this test (sandbox, CI runner, laptop).
        // The invariant that must hold everywhere is the clamp itself.
        val threads = profiler.profile().recommendedThreads
        assertTrue("recommendedThreads must stay in [2,4], was $threads", threads in 2..4)
    }
}
