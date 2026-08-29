package com.saarthi.core.inference

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 production log gate: [BuildConfig.PUBLIC_DEBUG_LOG] must stay off
 * unless a build explicitly passes `-Psaarthi.publicLog=true`. Unit tests
 * run the debug variant with no such property, so this pins the default.
 */
class PublicDebugLogDefaultTest {

    @Test
    fun public_debug_log_field_exists_and_defaults_off() {
        val names = BuildConfig::class.java.declaredFields.map { it.name }
        assertTrue(
            "BuildConfig must expose PUBLIC_DEBUG_LOG. Found: $names",
            names.contains("PUBLIC_DEBUG_LOG"),
        )
        assertFalse(
            "PUBLIC_DEBUG_LOG must default false (app-private log). " +
                "Pass -Psaarthi.publicLog=true only for a beta that needs " +
                "world-readable Downloads.",
            BuildConfig.PUBLIC_DEBUG_LOG,
        )
    }
}
