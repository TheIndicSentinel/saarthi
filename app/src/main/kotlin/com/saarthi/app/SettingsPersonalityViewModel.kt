package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.Personality
import com.saarthi.core.i18n.PersonalityPreference
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.prompt.SystemPromptProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Tiny read-only VM so the Settings list row can show the active persona
 * name + emoji, and whether the current model even supports personas,
 * without needing the full picker (that's [com.saarthi.feature.assistant
 * .viewmodel.PersonalityViewModel], used directly by the dedicated Persona
 * page — this VM stays minimal for the summary row only).
 */
@HiltViewModel
class SettingsPersonalityViewModel @Inject constructor(
    personalityPreference: PersonalityPreference,
    inferenceEngine: InferenceEngine,
    systemPromptProvider: SystemPromptProvider,
) : ViewModel() {
    val active: StateFlow<Personality> = personalityPreference.selected

    /** False on Compact tier (1B) — mirrors PersonalityViewModel's own check. */
    val supportedForCurrentModel: StateFlow<Boolean> = inferenceEngine.activeModelNameFlow
        .map { modelName ->
            systemPromptProvider.tierFor(modelName) != SystemPromptProvider.ModelTier.COMPACT
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
}
