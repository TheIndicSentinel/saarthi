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

    // Utterance id of the FINAL chunk of the current (possibly multi-part)
    // reply. A long reply is split into several queued utterances; isSpeaking
    // must stay true across all of them and only flip false when this last
    // chunk's onDone fires. Without this, a long answer either silently failed
    // to speak (over the engine's ~4000-char limit) or the chip reset after
    // the first chunk.
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
                        // Only the FINAL chunk of a multi-part reply ends the
                        // speaking state — intermediate chunks keep it true so
                        // the Listen→Stop chip stays correct across the whole
                        // answer.
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
     * default otherwise. [voiceHint] controls pitch / speech-rate / preferred
     * voice gender — when non-null, the manager picks the closest matching
     * on-device voice and applies pitch + rate so spoken replies match the
     * active persona (Pandit ji = low male, Dadi Maa = warm female, etc.).
     *
     * Returns the utteranceId so callers can correlate progress events with
     * a specific message bubble.
     */
    fun speak(
        text: String,
        language: SupportedLanguage,
        voiceHint: com.saarthi.core.i18n.VoiceHint? = null,
    ): String {
        // Strip markdown / code / symbols so the engine doesn't read out
        // "asterisk", "hash", "[1]", bullet dashes or emoji — which sounded
        // like noise mid-sentence. Indic combining marks are preserved.
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
            // Apply the persona's voice hint (or neutral defaults). pitch /
            // rate are applied unconditionally; voice selection only when
            // the device exposes at least one matching voice.
            val hint = voiceHint ?: com.saarthi.core.i18n.VoiceHint(
                com.saarthi.core.i18n.VoiceGender.NEUTRAL, 1.0f, 1.0f,
            )
            tts?.setPitch(hint.pitch)
            tts?.setSpeechRate(hint.rate)
            if (hint.gender != com.saarthi.core.i18n.VoiceGender.NEUTRAL) {
                pickVoiceByGender(locale, hint.gender)?.let { v ->
                    tts?.voice = v
                    DebugLogger.log("TTS", "Voice → ${v.name}  (${hint.gender})  pitch=${hint.pitch}  rate=${hint.rate}")
                }
            }
            // Long replies exceed the engine's hard input cap
            // (getMaxSpeechInputLength, ~4000 chars) — a single speak() call
            // over that limit reads NOTHING. Split into sentence-bounded chunks
            // and queue them: first FLUSH (replaces any in-progress reply),
            // the rest ADD so they play back-to-back as one continuous answer.
            val maxLen = runCatching { TextToSpeech.getMaxSpeechInputLength() }
                .getOrDefault(4000)
                .coerceIn(500, 4000) - 100   // headroom under the cap
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

    /**
     * Turn a markdown/rich chat reply into plain prose the TTS engine can read
     * naturally. Removes code fences, emphasis/heading/table punctuation,
     * `[1]`-style citations, bullet markers and emoji; turns links into their
     * label and blank lines into sentence pauses. Letters, digits, ordinary
     * punctuation, ₹/% and Indic combining marks are all kept.
     */
    private fun sanitizeForSpeech(raw: String): String {
        var t = raw
        t = t.replace(Regex("```[\\s\\S]*?```"), " ")            // drop code blocks
        t = t.replace(Regex("`+"), "")                             // inline code ticks
        // LaTeX / math markup the models emit (e.g. $\text{O}_2$, \frac{a}{b}).
        // Convert to readable plain text BEFORE the emphasis strip below
        // (which would otherwise eat the _ / ^ we rely on here).
        t = t.replace(Regex("\\\\(?:text|mathrm|mathbf|mathit|operatorname)\\s*\\{([^{}]*)\\}"), "$1")
        t = t.replace(Regex("\\\\frac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}"), "$1 over $2")
        t = t.replace(Regex("[_^]\\{([^{}]*)\\}"), "$1")
        t = t.replace(Regex("[_^]([0-9A-Za-z])"), "$1")          // O_2 → O2
        t = t.replace(Regex("\\\\[A-Za-z]+"), " ")                // stray \commands
        t = t.replace("$", " ").replace("{", " ").replace("}", " ").replace("^", " ")
        t = t.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")  // [label](url) → label
        t = t.replace(Regex("\\[\\s*\\d+(?:\\s*,\\s*\\d+)*\\s*\\]"), "") // [1] / [1, 2] citations
        t = t.replace(Regex("(?m)^\\s*[-*•]\\s+"), "")            // list bullets
        t = t.replace(Regex("[*_#~>|]+"), " ")                    // emphasis / heading / quote / table
        // Emoji & pictographic symbols (NOT Indic marks, which are Mn/Mc).
        t = t.replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2190}-\\x{21FF}\\x{FE00}-\\x{FE0F}\\x{200D}]"), "")
        t = t.replace(Regex("\\p{So}"), "")
        t = t.replace(Regex("\\n{2,}"), ". ").replace('\n', ' ')  // paragraph → pause
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        t = t.replace(Regex("\\s+([.,!?;:।])"), "$1")             // tidy space before punctuation
        return t.trim()
    }

    /**
     * Find the best on-device voice for [locale] matching the requested
     * gender. Three-tier match strategy:
     *
     *   1. Strict feature/name tag for the WANTED gender → pick it.
     *   2. Any voice that is NOT clearly the opposite gender → pick it.
     *   3. No match at all → return null so the system default plays.
     *
     * The previous version fell back to `candidates.firstOrNull()` on no
     * gender match — on most Android devices the first voice in the
     * list is FEMALE (e.g. `en-us-x-tpf-local`), which is why Pandit ji,
     * Kathakar, and Coach Singh were all playing in a female voice
     * despite their `VoiceHint(gender = MALE, …)` data. Tier 2's
     * anti-pattern filter prevents that flip.
     *
     * Within each tier we sort by `Voice.quality` DESC so the best-
     * sounding local voice wins — Android exposes VERY_HIGH/HIGH/NORMAL
     * /LOW/VERY_LOW and the picker now actually prefers higher tiers.
     * That's the only practical lever we have for "less robotic" on an
     * offline-first app (network-connected voices are intentionally
     * excluded — they require connectivity we don't promise).
     */
    private fun pickVoiceByGender(
        locale: Locale,
        gender: com.saarthi.core.i18n.VoiceGender,
    ): android.speech.tts.Voice? {
        val engine = tts ?: return null
        val wantMale = gender == com.saarthi.core.i18n.VoiceGender.MALE

        // Filter to local voices for this language; sort by quality DESC.
        val candidates = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter {
                it.locale.language == locale.language && !it.isNetworkConnectionRequired
            }
            .sortedByDescending { runCatching { it.quality }.getOrDefault(0) }
        if (candidates.isEmpty()) return null

        // Indic-aware Google TTS voice-ID hints. en-in / en-us / hi-in
        // gendered voices follow these naming infixes; the regexes also
        // match Samsung TTS naming conventions.
        val malePattern   = Regex("(?:^|[-_#])(male|iol|iom|tpd|and|ene|mlc|mlh)(?:[-_#]|$)", RegexOption.IGNORE_CASE)
        val femalePattern = Regex("(?:^|[-_#])(female|tpf|tpc|iog|cxx|flc|flh)(?:[-_#]|$)", RegexOption.IGNORE_CASE)
        val wantPattern = if (wantMale) malePattern else femalePattern
        val antiPattern = if (wantMale) femalePattern else malePattern

        fun matchesGender(v: android.speech.tts.Voice, pattern: Regex, label: String): Boolean {
            val features = runCatching { v.features }.getOrNull().orEmpty()
            if (features.any { it.equals(label, ignoreCase = true) }) return true
            return pattern.containsMatchIn(v.name)
        }

        // Tier 1: explicitly the wanted gender.
        val wantedLabel = if (wantMale) "male" else "female"
        candidates.firstOrNull { matchesGender(it, wantPattern, wantedLabel) }?.let { return it }

        // Tier 2: NOT clearly the opposite gender. Saves us from the
        // old "fallback to first voice (usually female)" failure mode.
        val antiLabel = if (wantMale) "female" else "male"
        candidates.firstOrNull { !matchesGender(it, antiPattern, antiLabel) }?.let { return it }

        // Tier 3: no acceptable voice exists locally — keep the system
        // default so we don't silently play the wrong gender.
        DebugLogger.log(
            "TTS",
            "No $wantedLabel voice found among ${candidates.size} candidate(s); keeping system default",
        )
        return null
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

/**
 * Split already-sanitized speech text into chunks no longer than [maxLen],
 * preferring sentence boundaries (`. ! ? ।` and newlines) so the TTS engine
 * never receives an over-cap utterance (which it would drop silently) and the
 * pauses land naturally between sentences. A single sentence longer than
 * [maxLen] is hard-split on whitespace as a last resort.
 *
 * Top-level `internal` so it is unit-testable without the Android TTS engine.
 * Returns a single-element list for the common case (reply already under the
 * cap), so typical short replies are unaffected.
 */
internal fun splitTextForTts(text: String, maxLen: Int): List<String> {
    val t = text.trim()
    if (t.isEmpty()) return emptyList()
    if (t.length <= maxLen) return listOf(t)

    val out = ArrayList<String>()
    val sb = StringBuilder()
    fun flush() { if (sb.isNotBlank()) out.add(sb.toString().trim()); sb.setLength(0) }

    // Split on sentence terminators followed by whitespace; '।' is the
    // Devanagari danda used across Indic scripts.
    val sentences = t.split(Regex("(?<=[.!?।])\\s+"))
    for (raw in sentences) {
        val s = raw.trim()
        if (s.isEmpty()) continue
        when {
            // Adding this sentence would overflow → start a new chunk first.
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

/** Append [s] to [sb], or hard-split it on whitespace when it alone exceeds [maxLen]. */
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
    // Sentence longer than the cap — flush whatever is buffered, then split on
    // whitespace as close to maxLen as possible.
    if (sb.isNotBlank()) { out.add(sb.toString().trim()); sb.setLength(0) }
    var rest = s
    while (rest.length > maxLen) {
        var cut = rest.lastIndexOf(' ', maxLen)
        if (cut < maxLen / 2) cut = maxLen   // no good space — hard cut
        out.add(rest.substring(0, cut).trim())
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) sb.append(rest)
}
