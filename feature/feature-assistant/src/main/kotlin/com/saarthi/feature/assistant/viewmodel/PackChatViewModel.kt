package com.saarthi.feature.assistant.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.feature.assistant.data.PackChatRepository
import com.saarthi.feature.assistant.domain.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI adapter for a knowledge-pack chat (Kisan today). The conversation, prompt
 * assembly and generation live in the app-scoped [PackChatRepository], so a
 * turn survives leaving this screen; this ViewModel only adds screen concerns
 * (TTS highlight, model banners, the state chip).
 */
@HiltViewModel
class PackChatViewModel @Inject constructor(
    private val repository: PackChatRepository,
    inferenceEngine: InferenceEngine,
    private val languageManager: LanguageManager,
    private val ttsManager: com.saarthi.feature.assistant.data.TtsManager,
    private val kisanPackPreference: com.saarthi.core.i18n.KisanPackPreference,
) : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> = repository.messages

    val isGenerating: StateFlow<Boolean> = repository.isGenerating

    val modelInitializing: StateFlow<Boolean> = inferenceEngine.isInitializingFlow
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false)
    val modelReloading: StateFlow<Boolean> = inferenceEngine.isReloadingAfterReleaseFlow
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false)

    /** Id of the message currently being read aloud, or null. */
    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    /** Selected language — the screen uses it for the localized input hint. */
    val language: StateFlow<SupportedLanguage> = languageManager.selectedLanguage

    /** The user's selected state (empty = unset) — shown as a chip, switchable. */
    val userState: StateFlow<String> = kisanPackPreference.userState
    fun setUserState(state: String) {
        viewModelScope.launch { runCatching { kisanPackPreference.setUserState(state) } }
    }

    init {
        // Clear the speaking highlight when TTS finishes / is stopped.
        ttsManager.isSpeaking
            .onEach { speaking -> if (!speaking) _speakingMessageId.value = null }
            .launchIn(viewModelScope)
        repository.refreshPackMetadata()
    }

    /** Listen / stop on a Kisan answer bubble. */
    fun toggleSpeak(messageId: String, text: String) {
        if (_speakingMessageId.value == messageId) {
            ttsManager.stop()
            return
        }
        _speakingMessageId.value = messageId
        ttsManager.speak(text, languageManager.selectedLanguage.value)
    }

    /** Retry the latest answer — see [PackChatRepository.retry]. */
    fun retry(messageId: String) = repository.retry(messageId)

    fun ask(rawQuestion: String) = repository.ask(rawQuestion)

    /** Wipe the pack conversation — the "manage / start fresh" action. */
    fun clear() = repository.clear()
}
