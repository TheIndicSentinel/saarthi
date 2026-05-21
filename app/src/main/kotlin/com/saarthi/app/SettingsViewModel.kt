package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.feature.assistant.domain.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings-side actions that need access to repositories outside the
 * Settings screen's own concerns (e.g. clearing chat history lives in
 * ChatRepository).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun clearAllChatHistory(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            chatRepository.clearHistory()
            _toast.update { "All conversations cleared" }
            onDone()
        }
    }

    fun consumeToast() = _toast.update { null }
}
