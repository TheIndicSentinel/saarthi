package com.saarthi.feature.assistant.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.Personality
import com.saarthi.core.i18n.PersonalityCatalog
import com.saarthi.core.i18n.PersonalityPreference
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.prompt.SystemPromptProvider
import com.saarthi.feature.assistant.domain.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Personality Pal picker UI.
 *
 * Selection is global and persisted. Switching personality mid-chat would be
 * confusing (the active conversation already has the previous persona in its
 * KV cache), so [select] also resets the chat session — the next message
 * starts a fresh conversation with the newly-picked persona.
 *
 * The Compact (Gemma 3 1B) tier ignores personality entirely; UI uses
 * [supportedForCurrentModel] to grey out the picker on that tier.
 */
@HiltViewModel
class PersonalityViewModel @Inject constructor(
    private val personalityPreference: PersonalityPreference,
    private val chatRepository: ChatRepository,
    private val inferenceEngine: InferenceEngine,
    private val systemPromptProvider: SystemPromptProvider,
) : ViewModel() {

    val selected: StateFlow<Personality> = personalityPreference.selected

    val all: List<Personality> = PersonalityCatalog.all

    /** False on Compact tier (1B) — the picker is shown but disabled. */
    val supportedForCurrentModel: StateFlow<Boolean> = inferenceEngine.activeModelNameFlow
        .map { modelName ->
            systemPromptProvider.tierFor(modelName) != SystemPromptProvider.ModelTier.COMPACT
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Persist [id] and start a fresh chat so the new persona authors its own
     * KV cache from turn one. The current chat is preserved in history.
     */
    fun select(id: String) {
        if (id == selected.value.id) return
        viewModelScope.launch {
            personalityPreference.set(id)
            // Reset the session — without this the next user message would be
            // appended to a Conversation that already has the previous
            // persona's system prompt baked in, and the persona switch would
            // appear to do nothing until the user manually started a new chat.
            chatRepository.createSession()
        }
    }
}
