package com.saarthi.core.inference.engine

import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import kotlinx.coroutines.flow.Flow

interface InferenceEngine {
    val isReady: Boolean

    /**
     * Hot flow that emits whenever [isReady] changes.
     * Default implementation emits the current value once; implementations should
     * override with a [kotlinx.coroutines.flow.StateFlow] for push-based observation.
     */
    val isReadyFlow: Flow<Boolean>
        get() = kotlinx.coroutines.flow.flow { emit(isReady) }

    /**
     * True while [initialize] is actively running (model load, GPU/NPU
     * backend selection, first-time shader compilation) — distinct from
     * [isReady]. A chat screen sees `isReady=false` both while the model is
     * genuinely still loading (expected, can take from a few seconds up to
     * several minutes on a first-ever load) and after a real failure; this
     * flag lets callers tell the two apart instead of assuming the worst.
     */
    val isInitializing: Boolean get() = false

    /** Hot flow that emits whenever [isInitializing] changes. */
    val isInitializingFlow: Flow<Boolean>
        get() = kotlinx.coroutines.flow.flow { emit(isInitializing) }

    /** The display name of the currently loaded model, or null if none. */
    val activeModelName: String?

    /**
     * The effective context-window size (maxNumTokens) the loaded model was
     * initialised with — the SAME value passed to the native engine, which
     * varies by tier AND live RAM headroom (e.g. Gemma 4 gets 2048 with
     * healthy RAM but drops to 1536 when headroom is tight; Gemma 3n can be
     * 1024 or 512). The prompt builder MUST size its char budget from this
     * so the assembled prompt never exceeds the model's input-token ceiling
     * — otherwise the native engine rejects it with "Input token ids are too
     * long" and the turn produces no reply. 0 when no model is loaded; the
     * builder treats 0 as "unknown" and falls back to its char constants.
     */
    val maxContextTokens: Int get() = 0

    /** Hot flow that emits whenever the loaded model changes. */
    val activeModelNameFlow: Flow<String?>
        get() = kotlinx.coroutines.flow.flow { emit(activeModelName) }

    /**
     * The sampling temperature the active model would use by default
     * (Google's recommended value for that model family). The user's
     * temperature setting overrides this; the Settings UI reads it to
     * display the "current temperature" before any override is chosen.
     */
    val activeModelDefaultTemperature: Float get() = 1.0f

    /**
     * True while the native inference thread is computing (i.e. after generateResponseAsync is
     * called and before the 'done' callback fires). Distinct from [isReady] and coroutine state.
     *
     * Used by InferenceService and ChatRepositoryImpl to decide whether the Foreground Service
     * may safely be stopped. If true, stopping the FGS removes OS protection from the native
     * GPU thread, which allows Samsung's power watchdog to kill the process mid-inference.
     */
    val isNativeGenerating: Boolean get() = false

    /**
     * True when the next [generateStream] call will start a brand-new conversation
     * (no prior turns in the model's KV cache). The caller should send the system
     * prompt on the first turn only — subsequent turns just send the user's
     * message and let the engine's stateful conversation maintain context, the
     * same way Google AI Edge Gallery's AI Chat does.
     *
     * Becomes false after the first successful generation; reset to true by
     * [resetSession] or after an error that recycles the conversation.
     */
    val isFreshConversation: Boolean get() = true

    suspend fun initialize(config: InferenceConfig)

    /** Streams partial tokens as they are generated. */
    fun generateStream(prompt: String, packType: PackType = PackType.BASE): Flow<String>

    /** One-shot generation — waits for the full response. */
    suspend fun generate(prompt: String, packType: PackType = PackType.BASE): String

    /**
     * Reset the inference session state (KV cache, conversation context).
     * Called when the user starts a new chat or clears history.
     * Implementations should discard any cached conversation state so the next
     * [generateStream] call starts from a clean context.
     */
    suspend fun resetSession() {}

    /**
     * User-initiated cancel: stop whatever the engine is currently doing.
     * Implementations should call the native cancel hook (e.g. LiteRT's
     * `Conversation.cancelProcess()`) so the model halts mid-stream rather
     * than continuing to burn battery + CPU while the UI ignores the rest.
     *
     * Safe to call when nothing is generating — must be a no-op in that case.
     */
    fun cancelGeneration() {}

    fun release()
}
