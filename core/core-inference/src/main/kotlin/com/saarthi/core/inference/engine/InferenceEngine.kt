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

    /** The display name of the currently loaded model, or null if none. */
    val activeModelName: String?

    /** Hot flow that emits whenever the loaded model changes. */
    val activeModelNameFlow: Flow<String?>
        get() = kotlinx.coroutines.flow.flow { emit(activeModelName) }

    /**
     * True while the native inference thread is computing (i.e. after generateResponseAsync is
     * called and before the 'done' callback fires). Distinct from [isReady] and coroutine state.
     *
     * Used by InferenceService and ChatRepositoryImpl to decide whether the Foreground Service
     * may safely be stopped. If true, stopping the FGS removes OS protection from the native
     * GPU thread, which allows Samsung's power watchdog to kill the process mid-inference.
     */
    val isNativeGenerating: Boolean get() = false

    suspend fun initialize(config: InferenceConfig)

    /** Streams partial tokens as they are generated. */
    fun generateStream(prompt: String, packType: PackType = PackType.BASE): Flow<String>

    /** One-shot generation — waits for the full response. */
    suspend fun generate(prompt: String, packType: PackType = PackType.BASE): String

    /**
     * Load a LoRA adapter on top of the current base model.
     * Engines that don't support LoRA (e.g. MediaPipe) silently ignore this call.
     * [scale] controls blending strength — 1.0 = full adapter, 0.5 = half-blended.
     */
    suspend fun loadLoraAdapter(adapterPath: String, scale: Float = 1.0f) {}

    /**
     * Remove the active LoRA adapter and revert to base-model behaviour.
     * No-op on engines that don't support LoRA.
     */
    fun clearLoraAdapter() {}

    fun release()
}
