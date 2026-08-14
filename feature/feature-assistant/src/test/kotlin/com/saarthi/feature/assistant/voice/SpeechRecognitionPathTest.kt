package com.saarthi.feature.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins Point 6 STT path selection: prefer on-device, optional block of cloud
 * fallback, never interrupt the default path when the user left the toggle off.
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
