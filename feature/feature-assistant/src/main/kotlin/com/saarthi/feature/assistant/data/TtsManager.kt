package com.saarthi.feature.assistant.data

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around Android's built-in [TextToSpeech]. Lazily initialises on
 * first speak so we don't pay engine startup cost (~50–200ms) until the user
 * actually presses a Listen button. Exposes [isSpeaking] for the UI to flip
 * the bubble action chip between "Listen" and "Stop".
 *
 * Offline-by-default: the Google TTS engine ships with most language packs
 * already on-device; we don't request the network and don't fall back to a
 * cloud engine. If the user's selected Saarthi language isn't installed, we
 * speak in the device default and log a debug line.
 */
@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var initialized: Boolean = false
    private var pendingSpeak: (() -> Unit)? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _activeUtteranceId = MutableStateFlow<String?>(null)
    val activeUtteranceId: StateFlow<String?> = _activeUtteranceId.asStateFlow()

    private fun ensureInitialized(onReady: () -> Unit) {
        if (initialized) { onReady(); return }
        pendingSpeak = onReady
        if (tts != null) return  // init already in flight
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                initialized = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                        _activeUtteranceId.value = utteranceId
                    }
                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        if (_activeUtteranceId.value == utteranceId) _activeUtteranceId.value = null
                    }
                    @Deprecated("Old API but TTS still calls this on some OEMs")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        if (_activeUtteranceId.value == utteranceId) _activeUtteranceId.value = null
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        DebugLogger.log("TTS", "Utterance error code=$errorCode  id=$utteranceId")
                        _isSpeaking.value = false
                        if (_activeUtteranceId.value == utteranceId) _activeUtteranceId.value = null
                    }
                })
                pendingSpeak?.invoke()
                pendingSpeak = null
            } else {
                DebugLogger.log("TTS", "TTS init FAILED status=$status — speak ignored")
                pendingSpeak = null
            }
        }
    }

    /**
     * Speak [text] in [language]'s locale if available; falls back to device
     * default otherwise. Returns the utteranceId so callers can correlate
     * progress events with a specific message bubble.
     */
    fun speak(text: String, language: SupportedLanguage): String {
        if (text.isBlank()) return ""
        val id = UUID.randomUUID().toString()
        ensureInitialized {
            val locale = ttsLocaleFor(language)
            val supported = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (supported >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = locale
            } else {
                DebugLogger.log("TTS", "Language ${locale.language} unavailable — using device default")
            }
            // QUEUE_FLUSH replaces any in-progress utterance — matches "tap
            // Listen on a different bubble while one is already playing".
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        }
        return id
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
        _activeUtteranceId.value = null
    }

    fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        initialized = false
        _isSpeaking.value = false
        _activeUtteranceId.value = null
    }

    /**
     * Map the Saarthi-supported language to a Locale the system TTS engine
     * understands. India-script languages frequently lack regional voices on
     * non-Pixel Android devices, so we use the script-only locale ("hi")
     * rather than "hi-IN" — falls back to the device default if missing.
     */
    private fun ttsLocaleFor(language: SupportedLanguage): Locale = when (language) {
        SupportedLanguage.ENGLISH  -> Locale("en", "IN")
        SupportedLanguage.HINDI    -> Locale("hi")
        SupportedLanguage.TAMIL    -> Locale("ta")
        SupportedLanguage.TELUGU   -> Locale("te")
        SupportedLanguage.BENGALI  -> Locale("bn")
        SupportedLanguage.MARATHI  -> Locale("mr")
        SupportedLanguage.KANNADA  -> Locale("kn")
        SupportedLanguage.GUJARATI -> Locale("gu")
        SupportedLanguage.PUNJABI  -> Locale("pa")
        SupportedLanguage.ODIA     -> Locale("or")
    }
}
