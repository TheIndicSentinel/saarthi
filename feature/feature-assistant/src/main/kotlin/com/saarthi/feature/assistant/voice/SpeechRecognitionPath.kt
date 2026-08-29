package com.saarthi.feature.assistant.voice

/**
 * Which Android speech path [com.saarthi.feature.assistant.viewmodel.AssistantViewModel]
 * should open for a voice turn.
 *
 * Pure decision — no Context / SpeechRecognizer — so unit tests pin the
 * on-device-only gate without Robolectric.
 */
enum class SpeechRecognitionPath {
    /** API 33+ on-device recognizer is available — fully offline STT. */
    ON_DEVICE,
    /** Standard recognizer; may use the device speech provider's cloud. */
    STANDARD_MAY_USE_CLOUD,
    /** No usable path (recognition missing, or on-device-only with no on-device model). */
    UNAVAILABLE,
}

/**
 * @param recognitionAvailable [android.speech.SpeechRecognizer.isRecognitionAvailable]
 * @param onDeviceAvailable API 33+ and [android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable]
 * @param onDeviceVoiceOnly product default on; older installs may have
 *   stored off. No Settings toggle.
 */
fun resolveSpeechRecognitionPath(
    recognitionAvailable: Boolean,
    onDeviceAvailable: Boolean,
    onDeviceVoiceOnly: Boolean,
): SpeechRecognitionPath = when {
    !recognitionAvailable -> SpeechRecognitionPath.UNAVAILABLE
    onDeviceAvailable -> SpeechRecognitionPath.ON_DEVICE
    onDeviceVoiceOnly -> SpeechRecognitionPath.UNAVAILABLE
    else -> SpeechRecognitionPath.STANDARD_MAY_USE_CLOUD
}
