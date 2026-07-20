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
 * Selection is global and persisted, but resetting the active chat session
 * is NOT automatic for every caller — only [selectAndStartNewChat] does
 * that. A picker reached FROM an active chat should use it (continuing that
 * chat would mix the previous persona's already-established voice, via the
 * recent-turns recap, into replies from the new one). The Settings page has
 * no active chat to reconcile, so it uses plain [select] — persist only,
 * never silently spawning/switching to an empty session the user hasn't
 * asked for or can't see.
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
     * Persist [id] as the active persona. Does not touch the current chat
     * session — use this from a picker with no active chat in view (the
     * Settings page). Callers switching away from a live chat should use
     * [selectAndStartNewChat] instead, or a stale persona/history mismatch
     * can leak into the next reply.
     */
    fun select(id: String) {
        if (id == selected.value.id) return
        viewModelScope.launch { personalityPreference.set(id) }
    }

    /**
     * [select] + start a fresh chat so the new persona authors its own KV
     * cache and turn history from scratch, instead of inheriting the
     * previous persona's already-established voice via the recent-turns
     * recap. The current chat is preserved in history, just no longer
     * active. Use this from any picker reached FROM an active chat (chat's
     * own ⋮ → Persona sheet) — not from Settings.
     */
    fun selectAndStartNewChat(id: String) {
        if (id == selected.value.id) return
        viewModelScope.launch {
            personalityPreference.set(id)
            chatRepository.createSession()
        }
    }
}
