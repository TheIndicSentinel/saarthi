package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexLifecycleTest {

    @Test
    fun `legacy index without stamp is not replaced`() {
        assertFalse(shouldReplaceIndex(storedStamp = null, newStamp = contentStamp(100, 50)))
    }

    @Test
    fun `same size and chars is not replaced`() {
        val stamp = contentStamp(4096, 1200)
        assertFalse(shouldReplaceIndex(stamp, stamp))
    }

    @Test
    fun `changed size or chars replaces stale chunks`() {
        assertTrue(shouldReplaceIndex(contentStamp(4096, 1200), contentStamp(8192, 1200)))
        assertTrue(shouldReplaceIndex(contentStamp(4096, 1200), contentStamp(4096, 2400)))
    }

    @Test
    fun `stamp format is size colon chars`() {
        assertEquals("100:20", contentStamp(100, 20))
    }
}
