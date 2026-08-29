package com.saarthi.app.packs

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackUpdateSchedulePolicyTest {

    @Test
    fun `empty URL does not enqueue`() {
        assertFalse(PackUpdateSchedulePolicy.shouldEnqueue(""))
    }

    @Test
    fun `whitespace-only URL does not enqueue`() {
        assertFalse(PackUpdateSchedulePolicy.shouldEnqueue("   "))
    }

    @Test
    fun `configured manifest URL enqueues`() {
        assertTrue(
            PackUpdateSchedulePolicy.shouldEnqueue(
                "https://example.com/kisan/manifest.json",
            ),
        )
    }

    @Test
    fun `pack refresh uses any connected network not unmetered wifi`() {
        assertEquals(NetworkType.CONNECTED, PackUpdateSchedulePolicy.requiredNetworkType)
        assertNotEquals(NetworkType.UNMETERED, PackUpdateSchedulePolicy.requiredNetworkType)
    }
}
