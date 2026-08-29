package com.saarthi.app.packs

import org.junit.Assert.assertFalse
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
}
