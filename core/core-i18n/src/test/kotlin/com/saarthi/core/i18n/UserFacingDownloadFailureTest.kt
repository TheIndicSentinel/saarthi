package com.saarthi.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Onboarding download status/failure copy is localized, and stored engine
 * reasons (HTTP codes, "catalog") must not be shown as-is.
 */
class UserFacingDownloadFailureTest {

    private val english = OnboardingStrings()
    private val hindi = SupportedLanguage.HINDI.onboarding

    @Test
    fun token_and_401_map_to_plain_token_copy() {
        val gated = "This model is on a gated Hugging Face repo. Choose it again and paste a read-only token when asked."
        assertEquals(english.failNeedsToken, userFacingDownloadFailure(gated, english))
        assertEquals(
            english.failNeedsToken,
            userFacingDownloadFailure(
                "Access denied (HTTP 401) — the Hugging Face token is missing or invalid.",
                english,
            ),
        )
        assertFalse(userFacingDownloadFailure(gated, english).contains("HTTP", ignoreCase = true))
        assertEquals(hindi.failNeedsToken, userFacingDownloadFailure(gated, hindi))
    }

    @Test
    fun storage_reason_does_not_echo_mb_math() {
        val raw = "Not enough storage: needs ~500MB, only 200MB available"
        val shown = userFacingDownloadFailure(raw, english)
        assertEquals(english.failNoStorage, shown)
        assertFalse(shown.contains("500"))
        assertFalse(shown.contains("200"))
        assertEquals(hindi.failNoStorage, userFacingDownloadFailure(raw, hindi))
    }

    @Test
    fun range_416_and_integrity_map_to_damaged_copy() {
        val range = "Download failed: file size does not match server or catalog (HTTP 416)"
        val shown = userFacingDownloadFailure(range, english)
        assertEquals(english.failCorrupt, shown)
        assertFalse(shown.contains("416"))
        assertFalse(shown.contains("catalog", ignoreCase = true))
        assertEquals(
            english.failCorrupt,
            userFacingDownloadFailure("Downloaded file failed integrity check after 3 attempts", english),
        )
    }

    @Test
    fun http_and_blank_fall_back_to_network_or_generic() {
        assertEquals(
            english.failNoNetwork,
            userFacingDownloadFailure("HTTP 503: Service Unavailable", english),
        )
        assertEquals(english.failGeneric, userFacingDownloadFailure(null, english))
        assertEquals(english.failGeneric, userFacingDownloadFailure("  ", english))
        assertEquals(
            english.failService,
            userFacingDownloadFailure("Could not start the download service. Please try again.", english),
        )
        assertEquals(
            english.failNotFound,
            userFacingDownloadFailure("Model not found at download URL (HTTP 404).", english),
        )
    }

    @Test
    fun every_language_has_download_status_words() {
        for (lang in SupportedLanguage.entries) {
            val o = lang.onboarding
            assertTrue("${lang.englishName} statusDownloading", o.statusDownloading.isNotBlank())
            assertTrue("${lang.englishName} downloadFailedLabel", o.downloadFailedLabel.isNotBlank())
            assertTrue("${lang.englishName} lastAttemptLabel", o.lastAttemptLabel.isNotBlank())
            val shown = userFacingDownloadFailure("HTTP 416 catalog", o)
            assertFalse(
                "${lang.englishName} mapped failure must not mention HTTP. Got: '$shown'",
                shown.contains("HTTP", ignoreCase = true),
            )
            assertFalse(
                "${lang.englishName} mapped failure must not mention catalog. Got: '$shown'",
                shown.contains("catalog", ignoreCase = true),
            )
        }
    }

    @Test
    fun every_language_has_localized_first_run_badge_and_expectation() {
        val english = OnboardingStrings()
        for (lang in SupportedLanguage.entries) {
            val o = lang.onboarding
            assertTrue("${lang.englishName} badgeMemory", o.badgeMemory.contains("%d"))
            assertTrue("${lang.englishName} badgeFree", o.badgeFree.contains("%d"))
            assertTrue("${lang.englishName} expectFlagship", o.expectFlagship.isNotBlank())
            assertTrue("${lang.englishName} expectMidRecommended", o.expectMidRecommended.isNotBlank())
            assertTrue("${lang.englishName} expectMidTight", o.expectMidTight.isNotBlank())
            assertTrue("${lang.englishName} expectLow", o.expectLow.isNotBlank())
            assertTrue("${lang.englishName} expectMinimal", o.expectMinimal.isNotBlank())
            assertTrue("${lang.englishName} aiModelFallback", o.aiModelFallback.isNotBlank())
            assertTrue("${lang.englishName} modelTagFallback", o.modelTagFallback.isNotBlank())
            if (lang == SupportedLanguage.ENGLISH) continue
            assertTrue(
                "${lang.englishName} badgeMemory must not stay English. Got: '${o.badgeMemory}'",
                o.badgeMemory != english.badgeMemory,
            )
            assertTrue(
                "${lang.englishName} expectFlagship must not stay English. Got: '${o.expectFlagship}'",
                o.expectFlagship != english.expectFlagship,
            )
            assertTrue(
                "${lang.englishName} aiModelFallback must not stay English. Got: '${o.aiModelFallback}'",
                o.aiModelFallback != english.aiModelFallback,
            )
            assertTrue(
                "${lang.englishName} modelTagFallback must not stay English. Got: '${o.modelTagFallback}'",
                o.modelTagFallback != english.modelTagFallback,
            )
        }
    }

    @Test
    fun initDesc_does_not_say_weights() {
        assertEquals("Loading the model into memory…", OnboardingStrings().initDesc)
        for (lang in SupportedLanguage.entries) {
            val text = lang.onboarding.initDesc
            assertTrue("${lang.englishName} initDesc must be non-blank", text.isNotBlank())
            assertFalse(
                "${lang.englishName} initDesc must not say weights. Got: '$text'",
                text.contains("weight", ignoreCase = true),
            )
        }
    }
}
