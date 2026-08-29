package com.saarthi.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3.3: Settings "Delete all" and Privacy stored-here copy must
 * disclose remembered personal facts, not just conversations. Guards
 * against a conversations-only regression in English and in languages
 * that override [SettingsStrings.clearDialogBody].
 */
class SettingsPrivacyCopyTest {

    private val oldHindiClearDialogBody =
        "यह सभी सहेजी गई बातचीत को हमेशा के लिए हटा देगा। सक्रिय मॉडल लोड रहेगा।"

    @Test
    fun english_clearDialogBody_mentions_conversations_remembered_facts_and_model_stays() {
        val body = SupportedLanguage.ENGLISH.settings.clearDialogBody
        assertTrue(
            "English clearDialogBody must mention conversations. Got: '$body'",
            body.contains("conversation", ignoreCase = true),
        )
        assertTrue(
            "English clearDialogBody must mention remembered/personal facts. Got: '$body'",
            body.contains("remember", ignoreCase = true) || body.contains("personal", ignoreCase = true),
        )
        assertTrue(
            "English clearDialogBody must say the model stays loaded. Got: '$body'",
            body.contains("model stays loaded", ignoreCase = true),
        )
    }

    @Test
    fun hindi_clearDialogBody_is_not_conversations_only_and_still_mentions_model() {
        val body = SupportedLanguage.HINDI.settings.clearDialogBody
        assertNotEquals(
            "Hindi clearDialogBody must not still be the old conversations-only sentence",
            oldHindiClearDialogBody,
            body,
        )
        assertTrue(
            "Hindi clearDialogBody must still say the model stays loaded. Got: '$body'",
            body.contains("सक्रिय मॉडल लोड रहेगा"),
        )
    }

    @Test
    fun tamil_clearDialogBody_is_not_conversations_only_and_still_mentions_model() {
        val body = SupportedLanguage.TAMIL.settings.clearDialogBody
        val oldTamil =
            "இது சேமித்த எல்லா உரையாடல்களையும் நிரந்தரமாக நீக்கும். செயலில் உள்ள மாடல் அப்படியே இருக்கும்."
        assertNotEquals(
            "Tamil clearDialogBody must not still be the old conversations-only sentence",
            oldTamil,
            body,
        )
        assertTrue(
            "Tamil clearDialogBody must still say the model stays. Got: '$body'",
            body.contains("மாடல் அப்படியே இருக்கும்"),
        )
    }

    @Test
    fun english_clearHistorySub_mentions_conversations_and_remembered_facts() {
        val sub = SupportedLanguage.ENGLISH.settings.clearHistorySub
        assertTrue(
            "English clearHistorySub must mention conversations. Got: '$sub'",
            sub.contains("conversation", ignoreCase = true),
        )
        assertTrue(
            "English clearHistorySub must mention remembered facts. Got: '$sub'",
            sub.contains("remember", ignoreCase = true),
        )
    }

    @Test
    fun privacy_stored_here_includes_remembered_facts_in_english_and_overrides() {
        val english = SupportedLanguage.ENGLISH.settingsDetail.privacyRememberedFacts
        assertTrue(english.isNotBlank())
        assertTrue(
            "English privacy row must mention remembered/personal facts. Got: '$english'",
            english.contains("remember", ignoreCase = true) || english.contains("personal", ignoreCase = true),
        )
        val hindi = SupportedLanguage.HINDI.settingsDetail.privacyRememberedFacts
        assertNotEquals(
            "Hindi privacy remembered-facts label must be translated",
            english,
            hindi,
        )
        assertTrue(hindi.isNotBlank())
    }

    @Test
    fun english_privacy_copy_says_voice_is_on_device() {
        val hero = SupportedLanguage.ENGLISH.settingsDetail.privacyHeroBody
        assertTrue(
            "privacyHeroBody must say voice is on-device. Got: '$hero'",
            hero.contains("on-device", ignoreCase = true),
        )
        assertTrue(
            "privacyHeroBody must not point at a Settings voice toggle. Got: '$hero'",
            !hero.contains("On-device voice only", ignoreCase = true) &&
                !hero.contains("turn off Settings", ignoreCase = true),
        )
        assertTrue(
            "privacyHeroBody must tell users they can type if voice is missing. Got: '$hero'",
            hero.contains("type", ignoreCase = true),
        )
        val noAccounts = SupportedLanguage.ENGLISH.settingsDetail.privacyNoAccountsSub
        assertTrue(
            "privacyNoAccountsSub must say voice stays on this device. Got: '$noAccounts'",
            noAccounts.contains("on this device", ignoreCase = true) ||
                noAccounts.contains("on-device", ignoreCase = true),
        )
        assertTrue(
            "privacyNoAccountsSub must not offer a phone-speech opt-in. Got: '$noAccounts'",
            !noAccounts.contains("unless you allow", ignoreCase = true),
        )
        val detailsSub = SupportedLanguage.ENGLISH.settings.privacyDetailsSub
        assertTrue(
            "privacyDetailsSub must say voice is on-device. Got: '$detailsSub'",
            detailsSub.contains("Voice on-device", ignoreCase = false) ||
                detailsSub.contains("voice on-device", ignoreCase = true),
        )
        assertTrue(
            "privacyDetailsSub must not say by default (no toggle). Got: '$detailsSub'",
            !detailsSub.contains("by default", ignoreCase = true),
        )
    }

    @Test
    fun voice_unavailable_copy_does_not_point_at_a_settings_toggle() {
        for (lang in SupportedLanguage.entries) {
            val s = lang.voiceOnDeviceOnlyUnavailable
            assertTrue("${lang.englishName} voiceOnDeviceOnlyUnavailable must be non-blank", s.isNotBlank())
            assertTrue(
                "${lang.englishName} voiceOnDeviceOnlyUnavailable must not mention Settings. Got: '$s'",
                !s.contains("Settings", ignoreCase = true),
            )
            val detailsSub = lang.settings.privacyDetailsSub
            assertTrue(
                "${lang.englishName} privacyDetailsSub must not mention a Settings voice option. Got: '$detailsSub'",
                !detailsSub.contains("Settings", ignoreCase = true),
            )
        }
    }

    @Test
    fun english_privacy_hardware_copy_has_no_engine_jargon() {
        val d = SupportedLanguage.ENGLISH.settingsDetail
        assertTrue(
            "privacyRunsHardware must say the model runs on this phone. Got: '${d.privacyRunsHardware}'",
            d.privacyRunsHardware.contains("phone", ignoreCase = true),
        )
        for (lang in SupportedLanguage.entries) {
            val sub = lang.settingsDetail.privacyRunsHardwareSub
            assertTrue(
                "${lang.englishName} privacyRunsHardwareSub must not mention Vulkan. Got: '$sub'",
                !sub.contains("Vulkan", ignoreCase = true),
            )
        }
    }

    @Test
    fun english_hf_token_copy_says_gated_models_and_not_in_the_apk() {
        val s = SupportedLanguage.ENGLISH.settings
        assertTrue(
            "hfTokenDialogBody must say Gemma 3n is gated. Got: '${s.hfTokenDialogBody}'",
            s.hfTokenDialogBody.contains("Gemma 3n", ignoreCase = true) &&
                s.hfTokenDialogBody.contains("gated", ignoreCase = true),
        )
        assertTrue(
            "hfTokenDialogBody must say the token is not in the app file. Got: '${s.hfTokenDialogBody}'",
            s.hfTokenDialogBody.contains("never put in the app file", ignoreCase = true),
        )
    }

    @Test
    fun about_copy_has_no_engine_or_license_jargon() {
        val english = SupportedLanguage.ENGLISH
        assertEquals(
            "English aboutEngineTitle must be plain language",
            "On-device AI",
            english.settingsDetail.aboutEngineTitle,
        )
        assertEquals(
            "English aboutLiteRtSub must say the model runs on this phone",
            "Runs the model on this phone",
            english.settingsDetail.aboutLiteRtSub,
        )
        assertEquals(
            "English aboutSaarthiSub must not mention source code",
            "Version and credits",
            english.settings.aboutSaarthiSub,
        )
        for (lang in SupportedLanguage.entries) {
            val d = lang.settingsDetail
            val aboutBits = listOf(
                d.aboutEngineTitle,
                d.aboutLiteRtSub,
                d.aboutGemmaSub,
                d.aboutBuiltWith,
                lang.settings.aboutSaarthiSub,
                lang.settings.aboutSaarthi,
            )
            for (bit in aboutBits) {
                assertTrue(
                    "${lang.englishName} About copy must not mention LiteRT. Got: '$bit'",
                    !bit.contains("LiteRT", ignoreCase = true),
                )
                assertTrue(
                    "${lang.englishName} About copy must not mention Apache. Got: '$bit'",
                    !bit.contains("Apache", ignoreCase = true),
                )
                assertTrue(
                    "${lang.englishName} About copy must not mention inference runtime. Got: '$bit'",
                    !bit.contains("inference", ignoreCase = true),
                )
                assertTrue(
                    "${lang.englishName} About copy must not mention source code. Got: '$bit'",
                    !bit.contains("source code", ignoreCase = true) &&
                        !bit.contains("sourcecode", ignoreCase = true),
                )
            }
        }
    }
}
