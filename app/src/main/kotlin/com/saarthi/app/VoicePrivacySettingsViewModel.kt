package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.VoicePrivacyPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VoicePrivacySettingsViewModel @Inject constructor(
    private val voicePrivacyPreference: VoicePrivacyPreference,
) : ViewModel() {
    val onDeviceVoiceOnly: StateFlow<Boolean> = voicePrivacyPreference.onDeviceVoiceOnly

    fun toggle() {
        viewModelScope.launch {
            voicePrivacyPreference.setOnDeviceVoiceOnly(!onDeviceVoiceOnly.value)
        }
    }
}
