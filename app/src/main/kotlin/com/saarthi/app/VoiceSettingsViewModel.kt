package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.feature.assistant.data.VoicePackManager
import com.saarthi.core.i18n.VoicePackPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(
    private val manager: VoicePackManager,
    private val pref: VoicePackPreference,
) : ViewModel() {

    val isNeuralSupported: Boolean = manager.isNeuralSupported

    val installedPackIds: StateFlow<Set<String>> = pref.installedPackIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val voiceGender: StateFlow<String> = pref.voiceGender
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "male")

    fun setGender(gender: String) = manager.setGender(gender)
}
