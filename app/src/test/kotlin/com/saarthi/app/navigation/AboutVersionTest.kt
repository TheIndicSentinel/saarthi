package com.saarthi.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AboutVersionTest {

    @Test
    fun uses_package_version_name_and_code() {
        assertEquals("v 1.0.39 · 40", aboutVersionLabel("1.0.39", 40L))
    }

    @Test
    fun blank_name_does_not_invent_a_stale_version() {
        assertEquals("v ? · 40", aboutVersionLabel("  ", 40L))
        assertEquals("v ? · 1", aboutVersionLabel(null, 1L))
        assertFalse(aboutVersionLabel("1.0.39", 40L).contains("1.4.0"))
        assertFalse(aboutVersionLabel("1.0.39", 40L).contains("187"))
    }
}
