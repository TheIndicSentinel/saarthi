package com.saarthi.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins Point 9: user-sourced strings must appear in logs as lengths only.
 */
class LogPrivacyTest {

    @Test
    fun `nameLen reports length without echoing the name`() {
        val name = "Aadhaar_scan_Ramesh.pdf"
        val fragment = LogPrivacy.nameLen(name)
        assertEquals("nameLen=23", fragment)
        assertFalse(fragment.contains("Aadhaar"))
        assertFalse(fragment.contains("Ramesh"))
    }

    @Test
    fun `keyLen and sessionIdLen never echo raw values`() {
        assertEquals("keyLen=4", LogPrivacy.keyLen("city"))
        assertEquals("sessionIdLen=3", LogPrivacy.sessionIdLen("abc"))
        assertFalse(LogPrivacy.keyLen("diet:vegetarian").contains("vegetarian"))
    }

    @Test
    fun `valueLen hides crash-tag payloads`() {
        assertEquals("valueLen=5", LogPrivacy.valueLen("hello"))
    }
}
