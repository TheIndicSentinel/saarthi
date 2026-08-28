package com.saarthi.core.inference

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins leftover-hardening point 1: the inference module must not bake a
 * Hugging Face token into BuildConfig (debug or release).
 */
class HuggingFaceTokenNotInBuildConfigTest {

    @Test
    fun inference_BuildConfig_has_no_hf_token_field() {
        val names = BuildConfig::class.java.declaredFields.map { it.name }
        assertFalse(
            "BuildConfig must not embed an HF token field. Found: $names",
            names.any { it.contains("HF_APP_TOKEN", ignoreCase = true) || it.contains("HF_TOKEN", ignoreCase = true) },
        )
    }
}
