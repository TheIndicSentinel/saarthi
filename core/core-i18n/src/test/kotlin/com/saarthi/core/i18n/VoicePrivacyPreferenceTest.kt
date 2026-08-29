package com.saarthi.core.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the product default: unset installs are on-device-only; an explicit
 * stored false (older install allowed phone speech) is preserved.
 */
class VoicePrivacyPreferenceTest {

    @Test
    fun product_default_is_on_device_only() {
        assertTrue(DEFAULT_ON_DEVICE_VOICE_ONLY)
    }

    @Test
    fun unset_key_uses_on_device_only_default() {
        assertTrue(resolveOnDeviceVoiceOnlyPreference(null))
    }

    @Test
    fun stored_true_stays_on_device_only() {
        assertTrue(resolveOnDeviceVoiceOnlyPreference(true))
    }

    @Test
    fun stored_false_keeps_phone_speech_allowed() {
        assertFalse(resolveOnDeviceVoiceOnlyPreference(false))
    }
}
