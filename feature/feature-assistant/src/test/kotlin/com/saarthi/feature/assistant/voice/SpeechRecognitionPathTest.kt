package com.saarthi.feature.assistant.voice

import com.saarthi.core.i18n.DEFAULT_ON_DEVICE_VOICE_ONLY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins STT path selection: prefer on-device; block cloud when on-device-only
 * is on (the product default); standard path only when the user turned the
 * toggle off and no on-device model exists.
 */
class SpeechRecognitionPathTest {

    @Test
    fun `on-device available always wins — even with on-device-only off`() {
        assertEquals(
            SpeechRecognitionPath.ON_DEVICE,
            resolveSpeechRecognitionPath(
                recognitionAvailable = true,
                onDeviceAvailable = true,
                onDeviceVoiceOnly = false,
            ),
        )
    }

    @Test
    fun `on-device available still wins when on-device-only is on`() {
        assertEquals(
            SpeechRecognitionPath.ON_DEVICE,
            resolveSpeechRecognitionPath(
                recognitionAvailable = true,
                onDeviceAvailable = true,
                onDeviceVoiceOnly = true,
            ),
        )
    }

    @Test
    fun `no on-device model uses standard path when toggle is off`() {
        assertEquals(
            SpeechRecognitionPath.STANDARD_MAY_USE_CLOUD,
            resolveSpeechRecognitionPath(
                recognitionAvailable = true,
                onDeviceAvailable = false,
                onDeviceVoiceOnly = false,
            ),
        )
    }

    @Test
    fun `on-device-only blocks cloud fallback when no on-device model`() {
        assertEquals(
            SpeechRecognitionPath.UNAVAILABLE,
            resolveSpeechRecognitionPath(
                recognitionAvailable = true,
                onDeviceAvailable = false,
                onDeviceVoiceOnly = true,
            ),
        )
    }

    @Test
    fun `product default on-device-only blocks cloud when no on-device model`() {
        assertTrue(DEFAULT_ON_DEVICE_VOICE_ONLY)
        assertEquals(
            SpeechRecognitionPath.UNAVAILABLE,
            resolveSpeechRecognitionPath(
                recognitionAvailable = true,
                onDeviceAvailable = false,
                onDeviceVoiceOnly = DEFAULT_ON_DEVICE_VOICE_ONLY,
            ),
        )
    }

    @Test
    fun `no recognition at all is unavailable regardless of toggle`() {
        assertEquals(
            SpeechRecognitionPath.UNAVAILABLE,
            resolveSpeechRecognitionPath(
                recognitionAvailable = false,
                onDeviceAvailable = false,
                onDeviceVoiceOnly = false,
            ),
        )
        assertEquals(
            SpeechRecognitionPath.UNAVAILABLE,
            resolveSpeechRecognitionPath(
                recognitionAvailable = false,
                onDeviceAvailable = true,
                onDeviceVoiceOnly = true,
            ),
        )
    }
}
