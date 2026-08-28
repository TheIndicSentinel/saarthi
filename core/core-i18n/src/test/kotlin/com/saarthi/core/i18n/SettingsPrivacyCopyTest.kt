package com.saarthi.core.i18n

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
    fun english_privacy_copy_says_voice_is_on_device_by_default() {
        val hero = SupportedLanguage.ENGLISH.settingsDetail.privacyHeroBody
        assertTrue(
            "privacyHeroBody must say on-device voice is the default. Got: '$hero'",
            hero.contains("on-device", ignoreCase = true) &&
                hero.contains("default", ignoreCase = true),
        )
        assertTrue(
            "privacyHeroBody must tell users how to allow phone speech. Got: '$hero'",
            hero.contains("On-device voice only", ignoreCase = true),
        )
        val noAccounts = SupportedLanguage.ENGLISH.settingsDetail.privacyNoAccountsSub
        assertTrue(
            "privacyNoAccountsSub must not treat cloud speech as the default. Got: '$noAccounts'",
            noAccounts.contains("on-device", ignoreCase = true),
        )
        val detailsSub = SupportedLanguage.ENGLISH.settings.privacyDetailsSub
        assertTrue(
            "privacyDetailsSub must say voice is on-device by default. Got: '$detailsSub'",
            detailsSub.contains("on-device by default", ignoreCase = true),
        )
        val off = SupportedLanguage.ENGLISH.settings.onDeviceVoiceOnlyOff
        assertTrue(
            "Off subtitle must say the user is allowing phone speech. Got: '$off'",
            off.contains("allows", ignoreCase = true),
        )
    }

    @Test
    fun english_hf_token_copy_says_gated_models_and_not_in_the_apk() {
        val s = SupportedLanguage.ENGLISH.settings
        assertTrue(s.hfToken.isNotBlank())
        assertTrue(
            "hfTokenDialogBody must say Gemma 3n is gated. Got: '${s.hfTokenDialogBody}'",
            s.hfTokenDialogBody.contains("Gemma 3n", ignoreCase = true) &&
                s.hfTokenDialogBody.contains("gated", ignoreCase = true),
        )
        assertTrue(
            "hfTokenDialogBody must say the token is not in the app file. Got: '${s.hfTokenDialogBody}'",
            s.hfTokenDialogBody.contains("never put in the app file", ignoreCase = true),
        )
        assertTrue(
            "hfTokenMissing must say Gemma 3n. Got: '${s.hfTokenMissing}'",
            s.hfTokenMissing.contains("Gemma 3n", ignoreCase = true),
        )
    }
}
