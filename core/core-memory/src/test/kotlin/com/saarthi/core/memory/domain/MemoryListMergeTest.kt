package com.saarthi.core.memory.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the multi-value (list) memory helpers. */
class MemoryListMergeTest {

    @Test
    fun `list keys accumulate, identity keys do not`() {
        assertTrue(MemoryRepository.isListKey("likes"))
        assertTrue(MemoryRepository.isListKey("user_likes"))
        assertTrue(MemoryRepository.isListKey("hobbies"))
        assertTrue(MemoryRepository.isListKey("allergies"))
        assertFalse(MemoryRepository.isListKey("name"))
        assertFalse(MemoryRepository.isListKey("age"))
        assertFalse(MemoryRepository.isListKey("city"))
        assertFalse(MemoryRepository.isListKey("diet"))
    }

    @Test
    fun `merge appends new value`() {
        assertEquals("apples, oranges", MemoryRepository.mergeListValue("apples", "oranges"))
    }

    @Test
    fun `merge seeds from empty`() {
        assertEquals("apples", MemoryRepository.mergeListValue(null, "apples"))
        assertEquals("apples", MemoryRepository.mergeListValue("", "apples"))
    }

    @Test
    fun `merge dedups case-insensitively`() {
        assertEquals("apples", MemoryRepository.mergeListValue("apples", "Apples"))
        assertEquals("apples, oranges", MemoryRepository.mergeListValue("apples, oranges", "ORANGES"))
    }

    @Test
    fun `merge caps to LIST_MAX_ITEMS dropping oldest`() {
        var v = ""
        for (i in 1..(MemoryRepository.LIST_MAX_ITEMS + 3)) {
            v = MemoryRepository.mergeListValue(v, "item$i")
        }
        val items = v.split(MemoryRepository.LIST_SEP)
        assertEquals(MemoryRepository.LIST_MAX_ITEMS, items.size)
        // oldest dropped, newest kept
        assertFalse(items.contains("item1"))
        assertTrue(items.contains("item${MemoryRepository.LIST_MAX_ITEMS + 3}"))
    }
}
