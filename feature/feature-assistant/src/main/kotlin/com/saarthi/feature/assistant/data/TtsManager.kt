package com.saarthi.feature.assistant.data

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.VoiceGender
import com.saarthi.core.i18n.VoiceHint
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
 * first speak so we don't pay engine startup cost until the user presses Listen.
 * Exposes [isSpeaking] for the UI to flip the bubble action chip.
 *
 * Offline-by-default: the Google TTS engine ships with most language packs
 * on-device. For English we request the en-IN locale and prefer India-region
 * voices, so the assistant sounds Indian where those voices are installed
 * (e.g. hi-in / en-in voices on Samsung/Pixel). Per-persona pitch/rate/gender
 * come from [VoiceHint].
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

    // Utterance id of the FINAL chunk of the current (possibly multi-part) reply.
    @Volatile private var lastChunkId: String? = null

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
                        if (utteranceId == lastChunkId) {
                            _isSpeaking.value = false
                            _activeUtteranceId.value = null
                        }
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
     * default otherwise. [voiceHint] controls pitch / speech-rate / gender so
     * spoken replies match the active persona.
     */
    fun speak(
        text: String,
        language: SupportedLanguage,
        voiceHint: VoiceHint? = null,
    ): String {
        val spoken = sanitizeForSpeech(text)
        if (spoken.isBlank()) return ""
        val id = UUID.randomUUID().toString()
        ensureInitialized {
            val locale = ttsLocaleFor(language)
            val supported = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (supported >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = locale
            } else {
                DebugLogger.log("TTS", "Language ${locale.language} unavailable — using device default")
            }
            val hint = voiceHint ?: VoiceHint(VoiceGender.NEUTRAL, 1.0f, 1.0f)
            tts?.setPitch(hint.pitch)
            tts?.setSpeechRate(hint.rate)
            if (hint.gender != VoiceGender.NEUTRAL) {
                pickVoiceByGender(locale, hint.gender)?.let { v ->
                    tts?.voice = v
                    DebugLogger.log("TTS", "Voice → ${v.name}  (${hint.gender})  pitch=${hint.pitch}  rate=${hint.rate}")
                }
            }
            val maxLen = runCatching { TextToSpeech.getMaxSpeechInputLength() }
                .getOrDefault(4000)
                .coerceIn(500, 4000) - 100
            val chunks = splitTextForTts(spoken, maxLen)
            if (chunks.isEmpty()) return@ensureInitialized
            lastChunkId = "${id}_${chunks.lastIndex}"
            chunks.forEachIndexed { i, chunk ->
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val chunkId = "${id}_$i"
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, chunkId)
                }
                tts?.speak(chunk, mode, params, chunkId)
            }
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
     * Map the Saarthi language to a Locale the TTS engine understands. English
     * uses en-IN so an Indian-accented voice is preferred; Indic scripts use the
     * script-only locale, which falls back to the device default if missing.
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

    /**
     * Pick the best on-device voice for [locale] matching [gender] — region-first,
     * quality-second. The region sort is what makes en-IN win over en-US: without
     * it the highest-quality "en" voice (often en-us) was chosen, overriding the
     * en-IN locale and making English sound American.
     */
    private fun pickVoiceByGender(locale: Locale, gender: VoiceGender): android.speech.tts.Voice? {
        val engine = tts ?: return null
        val wantMale = gender == VoiceGender.MALE
        val wantCountry = locale.country.uppercase()

        val candidates = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
            .sortedWith(
                compareByDescending<android.speech.tts.Voice> {
                    wantCountry.isNotEmpty() && it.locale.country.equals(wantCountry, ignoreCase = true)
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

    /**
     * Turn a markdown/rich chat reply into plain prose the TTS engine reads
     * naturally. Removes code fences, emphasis, citations, bullets, emoji.
     */
    private fun sanitizeForSpeech(raw: String): String {
        var t = raw
        t = t.replace(Regex("```[\\s\\S]*?```"), " ")
        t = t.replace(Regex("`+"), "")
        t = t.replace(Regex("\\\\(?:text|mathrm|mathbf|mathit|operatorname)\\s*\\{([^{}]*)\\}"), "$1")
        t = t.replace(Regex("\\\\frac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}"), "$1 over $2")
        t = t.replace(Regex("[_^]\\{([^{}]*)\\}"), "$1")
        t = t.replace(Regex("[_^]([0-9A-Za-z])"), "$1")
        t = t.replace(Regex("\\\\[A-Za-z]+"), " ")
        t = t.replace("$", " ").replace("{", " ").replace("}", " ").replace("^", " ")
        t = t.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        t = t.replace(Regex("\\[\\s*\\d+(?:\\s*,\\s*\\d+)*\\s*\\]"), "")
        t = t.replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
        t = t.replace(Regex("[*_#~>|]+"), " ")
        t = t.replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2190}-\\x{21FF}\\x{FE00}-\\x{FE0F}\\x{200D}]"), "")
        t = t.replace(Regex("\\p{So}"), "")
        t = t.replace(Regex("\\n{2,}"), ". ").replace('\n', ' ')
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        t = t.replace(Regex("\\s+([.,!?;:।])"), "$1")
        return t.trim()
    }
}

/**
 * Split already-sanitized speech text into chunks no longer than [maxLen],
 * preferring sentence boundaries so the TTS engine never receives an over-cap
 * utterance. Top-level `internal` so it is unit-testable without Android TTS.
 */
internal fun splitTextForTts(text: String, maxLen: Int): List<String> {
    val t = text.trim()
    if (t.isEmpty()) return emptyList()
    if (t.length <= maxLen) return listOf(t)

    val out = ArrayList<String>()
    val sb = StringBuilder()
    fun flush() { if (sb.isNotBlank()) out.add(sb.toString().trim()); sb.setLength(0) }

    val sentences = t.split(Regex("(?<=[.!?।])\\s+"))
    for (raw in sentences) {
        val s = raw.trim()
        if (s.isEmpty()) continue
        when {
            sb.isNotEmpty() && sb.length + 1 + s.length > maxLen -> {
                flush()
                appendSentenceOrSplit(s, maxLen, sb, out)
            }
            else -> appendSentenceOrSplit(s, maxLen, sb, out)
        }
    }
    flush()
    return out
}

private fun appendSentenceOrSplit(
    s: String,
    maxLen: Int,
    sb: StringBuilder,
    out: ArrayList<String>,
) {
    if (s.length <= maxLen) {
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(s)
        return
    }
    if (sb.isNotBlank()) { out.add(sb.toString().trim()); sb.setLength(0) }
    var rest = s
    while (rest.length > maxLen) {
        var cut = rest.lastIndexOf(' ', maxLen)
        if (cut < maxLen / 2) cut = maxLen
        out.add(rest.substring(0, cut).trim())
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) sb.append(rest)
}
