package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.TtsPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TtsSettingsViewModel @Inject constructor(
    private val ttsPreference: TtsPreference,
) : ViewModel() {
    val autoSpeak: StateFlow<Boolean> = ttsPreference.autoSpeakReplies
    fun toggle() {
        viewModelScope.launch { ttsPreference.setAutoSpeak(!autoSpeak.value) }
    }
}
