package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.inference.GenerationPreference
import com.saarthi.core.inference.engine.InferenceEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the "Response creativity" (sampling temperature) control on the
 * Response style screen.
 *
 * While the user hasn't overridden anything ([isAuto]), the slider shows the
 * active model's recommended temperature so it reflects what's actually in
 * effect — "by default shows current temperature". Picking a preset or moving
 * the slider writes an explicit value that the engine applies to normal chat
 * on the next turn; document/pack answers stay at their own accuracy-focused
 * temperature.
 */
@HiltViewModel
class GenerationSettingsViewModel @Inject constructor(
    private val generationPreference: GenerationPreference,
    private val inferenceEngine: InferenceEngine,
) : ViewModel() {

    /** The active model's recommended temperature — the AUTO baseline shown on the slider. */
    val modelDefault: Float get() = inferenceEngine.activeModelDefaultTemperature

    /** True when no explicit override is set — the slider tracks [modelDefault]. */
    val isAuto: StateFlow<Boolean> = generationPreference.temperature
        .map { it < 0f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The temperature currently in effect (model default while AUTO). */
    val temperature: StateFlow<Float> = generationPreference.temperature
        .map { if (it >= 0f) it else inferenceEngine.activeModelDefaultTemperature }
        .stateIn(viewModelScope, SharingStarted.Eagerly, inferenceEngine.activeModelDefaultTemperature)

    fun setTemperature(value: Float) {
        viewModelScope.launch { generationPreference.setTemperature(value) }
    }

    fun resetToAuto() {
        viewModelScope.launch { generationPreference.resetToAuto() }
    }
}
