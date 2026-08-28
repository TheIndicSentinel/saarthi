package com.saarthi.core.inference.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins gated-repo detection and the download auth gate after the embedded
 * BuildConfig Hugging Face token was removed from the APK.
 */
class HuggingFaceAuthTest {

    private val googleGemma3n =
        "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/c03b6f60b8da6c5400b6838a2cf26420f80c0a01/gemma-3n-E2B-it-int4.litertlm"
    private val litertCommunity =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/9262660a1676eed6d0c477ab1a86344430854664/gemma-4-E2B-it.litertlm"

    @Test
    fun google_org_urls_require_auth() {
        assertTrue(huggingFaceDownloadRequiresAuth(googleGemma3n))
        assertTrue(
            huggingFaceDownloadRequiresAuth(
                "http://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/abc/file.litertlm",
            ),
        )
    }

    @Test
    fun litert_community_and_other_hosts_do_not_require_auth() {
        assertFalse(huggingFaceDownloadRequiresAuth(litertCommunity))
        assertFalse(huggingFaceDownloadRequiresAuth("https://huggingface.co/org/repo/resolve/abc/file.litertlm"))
        assertFalse(huggingFaceDownloadRequiresAuth("https://example.com/model.litertlm"))
    }

    @Test
    fun blank_token_is_trimmed_to_empty() {
        assertEquals("", resolveHuggingFaceDownloadToken("  "))
        assertEquals("hf_abc", resolveHuggingFaceDownloadToken(" hf_abc "))
    }

    @Test
    fun public_url_is_allowed_without_a_token() {
        assertEquals(
            HuggingFaceAuthGate.ALLOW,
            resolveHuggingFaceAuthGate(litertCommunity, ""),
        )
    }

    @Test
    fun gated_url_without_token_is_blocked() {
        assertEquals(
            HuggingFaceAuthGate.NEED_TOKEN,
            resolveHuggingFaceAuthGate(googleGemma3n, "  "),
        )
    }

    @Test
    fun gated_url_with_saved_token_is_allowed() {
        assertEquals(
            HuggingFaceAuthGate.ALLOW,
            resolveHuggingFaceAuthGate(googleGemma3n, "hf_read_only"),
        )
    }
}
