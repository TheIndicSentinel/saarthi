package com.saarthi.core.inference.engine

import android.content.SharedPreferences
import com.saarthi.core.inference.model.SocFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistence contract for [CrashRecoveryStore] — keys, 24h expiry, crash-loop
 * threshold, version reset, conv-ready default, and commit-vs-apply on
 * kill-sensitive writes. In-memory [SharedPreferences]; no Robolectric.
 *
 * Policy (ban or not after a crash) stays in [ConvReadyGpuBanTest] /
 * [GpuBanSelfHealPolicyTest]; this file only checks what the store writes.
 */
class CrashRecoveryStoreTest {

    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var store: CrashRecoveryStore

    @Before
    fun setUp() {
        prefs = InMemorySharedPreferences()
        store = CrashRecoveryStore(prefs)
    }

    @Test
    fun `wasConvReadyAtCrash defaults true — unknown crash is conservative`() {
        assertTrue(store.wasConvReadyAtCrash())
    }

    @Test
    fun `markConvStarted then Ready round-trips`() {
        store.markConvStarted()
        assertFalse(store.wasConvReadyAtCrash())
        assertTrue(prefs.lastWriteWasCommit == true)
        store.markConvReady()
        assertTrue(store.wasConvReadyAtCrash())
        assertTrue(prefs.lastWriteWasCommit == true)
    }

    @Test
    fun `GPU ban is active until timestamp is older than 24h`() {
        val path = "/models/gemma.litertlm"
        store.markGpuGenCrashed(path)
        assertTrue(prefs.lastWriteWasCommit == true)
        assertTrue(store.gpuPreviouslyCrashedDuringGen(path))

        prefs.putLongDirect("litert_gpu_ban_ts_gemma.litertlm", System.currentTimeMillis() - 25L * 60 * 60 * 1000)
        assertFalse(store.gpuPreviouslyCrashedDuringGen(path))
    }

    @Test
    fun `SoC family ban expires the same way`() {
        store.markGpuGenCrashedForSoc(SocFamily.MEDIATEK_FLAGSHIP)
        assertTrue(store.gpuFamilyPreviouslyCrashedDuringGen(SocFamily.MEDIATEK_FLAGSHIP))
        prefs.putLongDirect(
            "litert_gpu_ban_soc_ts_MEDIATEK_FLAGSHIP",
            System.currentTimeMillis() - 25L * 60 * 60 * 1000,
        )
        assertFalse(store.gpuFamilyPreviouslyCrashedDuringGen(SocFamily.MEDIATEK_FLAGSHIP))
    }

    @Test
    fun `crash count increments with commit and expires after 24h`() {
        val path = "/x/model.bin"
        store.incrementCrashCount(path, wasGpuOrNpu = true)
        assertTrue(prefs.lastWriteWasCommit == true)
        assertEquals(1, store.getCrashCount(path))

        prefs.putLongDirect("litert_crash_count_model.bin_ts", System.currentTimeMillis() - 25L * 60 * 60 * 1000)
        assertEquals(0, store.getCrashCount(path))
    }

    @Test
    fun `crash loop at 4 blocks and keeps the count`() {
        val path = "/x/loop.bin"
        repeat(4) { store.incrementCrashCount(path, wasGpuOrNpu = true) }
        assertTrue(store.breakCrashLoopIfNeeded(path))
        assertEquals(4, store.getCrashCount(path))
        assertTrue(prefs.lastWriteWasCommit == true)
    }

    @Test
    fun `three crashes is not a loop`() {
        val path = "/x/ok.bin"
        repeat(3) { store.incrementCrashCount(path, wasGpuOrNpu = true) }
        assertFalse(store.breakCrashLoopIfNeeded(path))
        assertEquals(3, store.getCrashCount(path))
    }

    @Test
    fun `new app version clears crash counts and GPU bans`() {
        val path = "/models/a.litertlm"
        store.incrementCrashCount(path, wasGpuOrNpu = true)
        store.markGpuGenCrashed(path)
        prefs.edit().putInt("litert_app_version", 10).commit()

        store.resetOnVersionChange(11)

        assertEquals(0, store.getCrashCount(path))
        assertFalse(store.gpuPreviouslyCrashedDuringGen(path))
        assertEquals(11, prefs.getInt("litert_app_version", 0))
        assertTrue(prefs.lastWriteWasCommit == true)
    }

    @Test
    fun `same app version does not clear crash state`() {
        val path = "/models/a.litertlm"
        store.incrementCrashCount(path, wasGpuOrNpu = true)
        prefs.edit().putInt("litert_app_version", 5).commit()
        store.resetOnVersionChange(5)
        assertEquals(1, store.getCrashCount(path))
    }

    @Test
    fun `kill-sensitive pending flags use commit`() {
        store.markInitStarted("/m.litertlm")
        assertTrue(store.wasKilledDuringInit())
        assertTrue(prefs.lastWriteWasCommit == true)
        store.markInitEnded()

        store.markGenerationStarted(wasUsingGpuOrNpu = true, modelPath = "/m.litertlm")
        assertTrue(store.wasKilledDuringGeneration())
        assertTrue(store.wasUsingGpuAtCrash())
        assertTrue(prefs.lastWriteWasCommit == true)
        store.markGenerationEnded()
        assertFalse(store.wasKilledDuringGeneration())
    }

    @Test
    fun `resetCrashCount uses apply not commit`() {
        val path = "/x/c.bin"
        store.incrementCrashCount(path, wasGpuOrNpu = false)
        store.resetCrashCount(path)
        assertEquals(0, store.getCrashCount(path))
        assertTrue(prefs.lastWriteWasCommit == false)
    }
}

/** Minimal [SharedPreferences] that records whether the last flush was commit(). */
internal class InMemorySharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()
    var lastWriteWasCommit: Boolean? = null
        private set

    fun putLongDirect(key: String, value: Long) {
        data[key] = value
    }

    override fun getAll(): MutableMap<String, *> = HashMap(data)
    override fun getString(key: String?, defValue: String?): String? =
        data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (data[key] as? MutableSet<String>) ?: defValues
    }
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = MemEditor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class MemEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
        override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
        override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
        override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
        override fun remove(key: String?) = apply { if (key != null) removals.add(key) }
        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            flush()
            lastWriteWasCommit = true
            return true
        }

        override fun apply() {
            flush()
            lastWriteWasCommit = false
        }

        private fun flush() {
            if (clearAll) data.clear()
            removals.forEach { data.remove(it) }
            pending.forEach { (k, v) ->
                if (v == null) data.remove(k) else data[k] = v
            }
        }
    }
}
