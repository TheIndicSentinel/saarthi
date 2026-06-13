package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.VoiceHint
import java.util.Locale

/**
 * Minimal interface for a TTS synthesis backend.
 *
 * [TtsManager] is the public router — callers never touch this interface.
 * Two implementations ship:
 *
 *  • [SystemTtsEngine]  — Android TextToSpeech. Always available. Used as
 *    the default and as fallback when the neural engine is absent/unavailable.
 *
 *  • NeuralTtsEngine (Phase 2) — sherpa-onnx + Piper ONNX models. Only
 *    created when a voice pack has been downloaded and the device is MID+
 *    (≥ 6 GB total RAM). Nil otherwise, causing the router to fall back.
 *
 * Design constraint: StateFlow management ([isSpeaking], [activeUtteranceId])
 * stays in [TtsManager] so the rest of the app has a single observable
 * surface. Engines fire the [TtsCallbacks] and [TtsManager] updates its flows.
 */
interface TtsEngine {

    /**
     * True when this engine can synthesize right now.
     *
     * [SystemTtsEngine] is always available after initialization succeeds.
     * [NeuralTtsEngine] returns false when no voice model is loaded (e.g.
     * between engine free() and the next allocate()).
     */
    val isAvailable: Boolean

    /**
     * Synthesize [chunks] sequentially. Each chunk is already sanitized and
     * guaranteed to fit within the engine's input cap. The engine fires
     * [callbacks] in order: [TtsCallbacks.onStart] when synthesis begins,
     * [TtsCallbacks.onDone] per chunk (or when all chunks finish), and
     * [TtsCallbacks.onError] on failure.
     *
     * [utteranceIdPrefix] is provided by [TtsManager] and uniquely identifies
     * this speak() call; individual chunk IDs are "{prefix}_{index}".
     */
    fun speakChunks(
        chunks: List<String>,
        locale: Locale,
        hint: VoiceHint,
        utteranceIdPrefix: String,
        callbacks: TtsCallbacks,
    )

    /** Stop any in-progress synthesis immediately. */
    fun stop()

    /**
     * Release all resources. After release, [isAvailable] returns false until
     * the engine is re-initialized.
     */
    fun release()
}

/** Progress callbacks fired by a [TtsEngine] into [TtsManager]. */
data class TtsCallbacks(
    val onStart: (utteranceId: String) -> Unit,
    val onDone: (utteranceId: String) -> Unit,
    val onError: (utteranceId: String, errorCode: Int) -> Unit,
)
