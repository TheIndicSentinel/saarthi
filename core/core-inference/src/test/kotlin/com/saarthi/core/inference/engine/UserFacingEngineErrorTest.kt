package com.saarthi.core.inference.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * User-visible engine errors must not leak sandbox paths, fd paths, or
 * exception class names. Native / JNI strings stay in logs only.
 */
class UserFacingEngineErrorTest {

    @Test
    fun missing_catalog_file_message_has_no_path() {
        assertFalse(containsFilesystemPath(USER_FACING_MODEL_MISSING))
        assertFalse(looksLikeInternalEngineDetail(USER_FACING_MODEL_MISSING))
        assertFalse(USER_FACING_MODEL_MISSING.contains("/data/", ignoreCase = true))
    }

    @Test
    fun sandbox_path_is_replaced_with_generic_load_copy() {
        val raw = "Model file not found: /data/user/0/com.saarthi.app/files/models/gemma.litertlm"
        assertTrue(containsFilesystemPath(raw))
        assertEquals(USER_FACING_LOAD_GENERIC, userFacingLoadError(raw))
    }

    @Test
    fun fd_path_is_replaced_with_generic_load_copy() {
        val raw = "cannot stat /proc/self/fd/12.litertlm"
        assertTrue(containsFilesystemPath(raw))
        assertEquals(USER_FACING_LOAD_GENERIC, userFacingLoadError(raw))
    }

    @Test
    fun content_uri_is_treated_as_internal() {
        val raw = "open failed for content://com.android.providers.downloads.documents/document/42"
        assertTrue(containsFilesystemPath(raw))
        assertEquals(USER_FACING_LOAD_GENERIC, userFacingLoadError(raw))
    }

    @Test
    fun exception_class_name_is_replaced() {
        assertEquals(
            USER_FACING_LOAD_GENERIC,
            userFacingLoadError("java.io.FileNotFoundException: gemma.litertlm"),
        )
        assertEquals(
            USER_FACING_LOAD_GENERIC,
            userFacingLoadError("UnsatisfiedLinkError"),
        )
    }

    @Test
    fun litert_and_jni_brand_are_replaced() {
        assertEquals(
            USER_FACING_LOAD_GENERIC,
            userFacingLoadError("LiteRT failed to load model: INTERNAL"),
        )
        assertEquals(
            USER_FACING_LOAD_GENERIC,
            userFacingLoadError("LiteRtLmJni: createEngine failed"),
        )
    }

    @Test
    fun blank_or_null_uses_fallback() {
        assertEquals(USER_FACING_LOAD_GENERIC, userFacingLoadError(null))
        assertEquals(USER_FACING_LOAD_GENERIC, userFacingLoadError("  "))
        assertEquals(USER_FACING_GENERATION_FAILED, userFacingGenerationError(null))
    }

    @Test
    fun already_plain_load_copy_passes_through() {
        val ram = "Not enough RAM to load this model. Close background apps and try again, or choose a smaller model."
        assertEquals(ram, userFacingLoadError(ram))
        assertEquals(USER_FACING_MODEL_MISSING, userFacingLoadError(USER_FACING_MODEL_MISSING))
    }

    @Test
    fun generation_error_strips_prefix_and_paths() {
        assertEquals(
            USER_FACING_GENERATION_FAILED,
            userFacingGenerationError("Generation failed: /data/user/0/com.saarthi.app/cache/tmp"),
        )
        assertEquals(
            USER_FACING_GENERATION_FAILED,
            userFacingGenerationError("Generation failed: null"),
        )
        val plain = "This model cannot run on your device. Please go back and choose a different model."
        assertEquals(plain, userFacingGenerationError("Generation failed: $plain"))
    }

    @Test
    fun percent_and_http_copy_are_not_treated_as_paths() {
        assertFalse(containsFilesystemPath("Download incomplete: 1200MB of 2500MB."))
        assertFalse(containsFilesystemPath("Access denied (HTTP 401)"))
    }
}
