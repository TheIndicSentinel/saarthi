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
 * Drives the Personality Pal picker UI — both chat's own ⋮ → Persona sheet
 * and the dedicated Settings → Persona page.
 *
 * Selection is global and persisted, and [selectAndStartNewChat] always
 * resets the active chat session too — including from Settings. An earlier
 * version had a persist-only path for Settings specifically (no active chat
 * in view there), but ChatRepositoryImpl's current session is
 * singleton-scoped and survives regardless of whether chat is on screen:
 * skipping the reset just meant the NEXT message sent after returning to
 * chat mixed the new persona's system prompt with the old persona's
 * conversation recap — the exact mismatch a session reset exists to
 * prevent, only relocated. Always resetting costs nothing visible from
 * Settings (the reset isn't visible from there either way) and keeps
 * persona/history consistent the moment the user is back in chat.
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
     * KV cache and turn history from scratch, instead of inheriting the
     * previous persona's already-established voice via the recent-turns
     * recap. The current chat is preserved in history, just no longer
     * active. Used by every picker (chat's ⋮ → Persona sheet and the
     * Settings → Persona page) — see the class kdoc for why Settings needs
     * the reset too, even with no active chat in view.
     */
    fun selectAndStartNewChat(id: String) {
        if (id == selected.value.id) return
        viewModelScope.launch {
            personalityPreference.set(id)
            chatRepository.createSession()
        }
    }
}
