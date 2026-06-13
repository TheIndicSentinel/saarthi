package com.saarthi.feature.assistant.data

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.saarthi.core.i18n.VoiceGender
import com.saarthi.core.i18n.VoiceHint
import com.saarthi.core.inference.DebugLogger
import java.util.Locale

/**
 * Android TextToSpeech backend implementing [TtsEngine].
 *
 * All synthesis logic that previously lived directly in [TtsManager] now
 * lives here. [TtsManager] creates one instance of this class and holds it
 * permanently — it is the guaranteed fallback and the default engine when no
 * neural pack is installed.
 *
 * Initialization is lazy (on first [speakChunks] call) so no startup cost
 * is paid until the user actually presses a Listen button.
 */
internal class SystemTtsEngine(private val context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null
    private var initialized = false
    private var pendingSpeak: (() -> Unit)? = null

    override val isAvailable: Boolean
        get() = initialized && tts != null

    override fun speakChunks(
        chunks: List<String>,
        locale: Locale,
        hint: VoiceHint,
        utteranceIdPrefix: String,
        callbacks: TtsCallbacks,
    ) {
        ensureInitialized {
            applyLocaleAndVoice(locale, hint)
            tts?.setPitch(hint.pitch)
            tts?.setSpeechRate(hint.rate)

            chunks.forEachIndexed { i, chunk ->
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val chunkId = "${utteranceIdPrefix}_$i"
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, chunkId)
                }
                tts?.speak(chunk, mode, params, chunkId)
            }
        }
    }

    /** Set callbacks that [TtsManager] uses to update its StateFlows. */
    fun setCallbackDispatcher(callbacks: TtsCallbacks) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != null) callbacks.onStart(utteranceId)
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId != null) callbacks.onDone(utteranceId)
            }
            @Deprecated("Old API but still fired on some OEMs")
            override fun onError(utteranceId: String?) {
                if (utteranceId != null) callbacks.onError(utteranceId, -1)
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                DebugLogger.log("TTS", "Utterance error code=$errorCode  id=$utteranceId")
                if (utteranceId != null) callbacks.onError(utteranceId, errorCode)
            }
        })
    }

    override fun stop() {
        tts?.stop()
    }

    override fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        initialized = false
        pendingSpeak = null
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun ensureInitialized(onReady: () -> Unit) {
        if (initialized) { onReady(); return }
        pendingSpeak = onReady
        if (tts != null) return  // init already in flight
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                initialized = true
                pendingSpeak?.invoke()
                pendingSpeak = null
            } else {
                DebugLogger.log("TTS", "SystemTtsEngine init FAILED status=$status")
                pendingSpeak = null
            }
        }
    }

    private fun applyLocaleAndVoice(locale: Locale, hint: VoiceHint) {
        val engine = tts ?: return
        val supported = engine.isLanguageAvailable(locale)
        if (supported >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = locale
        } else {
            DebugLogger.log("TTS", "Language ${locale.language} unavailable — using device default")
        }
        if (hint.gender != VoiceGender.NEUTRAL) {
            pickVoiceByGender(locale, hint.gender)?.let { v ->
                engine.voice = v
                DebugLogger.log(
                    "TTS",
                    "Voice → ${v.name}  (${hint.gender})  pitch=${hint.pitch}  rate=${hint.rate}",
                )
            }
        }
    }

    /**
     * Three-tier voice selection — region-first, quality-second.
     *
     * The key fix vs. the old implementation: candidates are sorted with the
     * target region (e.g. IN for en-IN) ranked above any other region before
     * quality is applied. Without this, the highest-quality "en" voice would
     * always win regardless of locale — which is why `en-IN` was being
     * overridden by `en-us-x-iom-local`.
     */
    private fun pickVoiceByGender(
        locale: Locale,
        gender: VoiceGender,
    ): android.speech.tts.Voice? {
        val engine = tts ?: return null
        val wantMale = gender == VoiceGender.MALE
        val wantCountry = locale.country.uppercase()

        val candidates = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
            .sortedWith(
                compareByDescending<android.speech.tts.Voice> {
                    wantCountry.isNotEmpty() &&
                        it.locale.country.equals(wantCountry, ignoreCase = true)
                }.thenByDescending { runCatching { it.quality }.getOrDefault(0) }
            )
        if (candidates.isEmpty()) return null

        val malePattern   = Regex("(?:^|[-_#])(male|iol|iom|tpd|and|ene|mlc|mlh)(?:[-_#]|$)", RegexOption.IGNORE_CASE)
        val femalePattern = Regex("(?:^|[-_#])(female|tpf|tpc|iog|cxx|flc|flh)(?:[-_#]|$)", RegexOption.IGNORE_CASE)
        val wantPattern = if (wantMale) malePattern else femalePattern
        val antiPattern = if (wantMale) femalePattern else malePattern

        fun matches(v: android.speech.tts.Voice, pattern: Regex, label: String): Boolean {
            val features = runCatching { v.features }.getOrNull().orEmpty()
            return features.any { it.equals(label, ignoreCase = true) } || pattern.containsMatchIn(v.name)
        }

        val wantedLabel = if (wantMale) "male" else "female"
        val antiLabel   = if (wantMale) "female" else "male"

        candidates.firstOrNull { matches(it, wantPattern, wantedLabel) }?.let { return it }
        candidates.firstOrNull { !matches(it, antiPattern, antiLabel) }?.let { return it }

        DebugLogger.log("TTS", "No $wantedLabel voice found; keeping system default")
        return null
    }
}
