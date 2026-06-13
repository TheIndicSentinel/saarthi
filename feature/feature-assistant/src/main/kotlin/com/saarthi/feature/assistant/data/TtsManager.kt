package com.saarthi.feature.assistant.data

import android.content.Context
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.VoiceGender
import com.saarthi.core.i18n.VoiceHint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public TTS router — the ONLY TTS call site for the rest of the app.
 *
 * Manages two engine slots:
 *
 *  • [systemEngine] ([SystemTtsEngine]) — Android TextToSpeech.
 *    Always present after app start. Used as the default and fallback.
 *
 *  • [neuralEngine] — Piper/sherpa-onnx (Phase 2). Null until a voice pack
 *    is installed and the device is MID+ tier. When non-null and available
 *    for the active language, it takes priority over the system engine.
 *
 * Routing logic:
 *   1. If [neuralEngine] is non-null, available, and supports [language] →
 *      use neural.
 *   2. Otherwise → use [systemEngine].
 *
 * StateFlow management lives here (not in the engines) so the rest of the
 * app has a single observable surface, regardless of which engine fired.
 */
@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _activeUtteranceId = MutableStateFlow<String?>(null)
    val activeUtteranceId: StateFlow<String?> = _activeUtteranceId.asStateFlow()

    // Id of the FINAL chunk of the current multi-part reply. isSpeaking must
    // stay true across all chunks; only the last onDone flips it false.
    @Volatile private var lastChunkId: String? = null

    private val callbacks = TtsCallbacks(
        onStart = { id ->
            _isSpeaking.value = true
            _activeUtteranceId.value = id
        },
        onDone = { id ->
            if (id == lastChunkId) {
                _isSpeaking.value = false
                _activeUtteranceId.value = null
            }
        },
        onError = { id, _ ->
            _isSpeaking.value = false
            if (_activeUtteranceId.value == id) _activeUtteranceId.value = null
        },
    )

    // ── Engine slots ──────────────────────────────────────────────────────────

    private val systemEngine: SystemTtsEngine = SystemTtsEngine(context)

    /**
     * Neural engine slot — null until Phase 2 is installed.
     * Settable so [NeuralTtsEngine] (Phase 2) can be injected at runtime
     * without modifying this class: the voice-pack install flow calls
     * [setNeuralEngine] after downloading and loading the model files.
     */
    @Volatile
    private var neuralEngine: TtsEngine? = null

    /**
     * The languages for which the current [neuralEngine] has a loaded voice
     * model. Empty until Phase 2 provides a neural pack.
     */
    @Volatile
    private var neuralSupportedLanguages: Set<SupportedLanguage> = emptySet()

    /**
     * Called by the voice-pack subsystem (Phase 2) after successfully loading
     * a neural voice model. Passing null clears the neural slot and routes
     * all synthesis back to the system engine.
     */
    fun setNeuralEngine(engine: TtsEngine?, supportedLanguages: Set<SupportedLanguage>) {
        neuralEngine = engine
        neuralSupportedLanguages = supportedLanguages
    }

    // ── Public API (unchanged surface — no callers need updating) ─────────────

    /**
     * Speak [text] in [language]'s locale if available; falls back to device
     * default otherwise. [voiceHint] controls pitch / speech-rate / gender so
     * spoken replies match the active persona.
     *
     * Routes to [neuralEngine] when available for [language]; falls back to
     * [systemEngine] otherwise.
     */
    fun speak(
        text: String,
        language: SupportedLanguage,
        voiceHint: VoiceHint? = null,
    ): String {
        val spoken = sanitizeForSpeech(text)
        if (spoken.isBlank()) return ""
        val id = UUID.randomUUID().toString()

        val hint = voiceHint ?: VoiceHint(VoiceGender.NEUTRAL, 1.0f, 1.0f)
        val locale = ttsLocaleFor(language)

        val maxLen = runCatching { android.speech.tts.TextToSpeech.getMaxSpeechInputLength() }
            .getOrDefault(4000)
            .coerceIn(500, 4000) - 100
        val chunks = splitTextForTts(spoken, maxLen)
        if (chunks.isEmpty()) return ""

        lastChunkId = "${id}_${chunks.lastIndex}"

        val engine = pickEngine(language)
        // SystemTtsEngine needs the dispatcher wired before each speak
        // (lazily, since the TTS object is created asynchronously).
        if (engine is SystemTtsEngine) engine.setCallbackDispatcher(callbacks)
        engine.speakChunks(chunks, locale, hint, id, callbacks)

        return id
    }

    fun stop() {
        systemEngine.stop()
        neuralEngine?.stop()
        _isSpeaking.value = false
        _activeUtteranceId.value = null
    }

    fun release() {
        systemEngine.release()
        neuralEngine?.release()
        _isSpeaking.value = false
        _activeUtteranceId.value = null
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun pickEngine(language: SupportedLanguage): TtsEngine {
        val neural = neuralEngine
        if (neural != null && neural.isAvailable && language in neuralSupportedLanguages) {
            return neural
        }
        return systemEngine
    }

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
     * Turn a markdown/rich reply into plain prose the TTS engine reads
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
