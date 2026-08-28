package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.inference.HuggingFaceTokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HuggingFaceTokenSettingsViewModel @Inject constructor(
    private val hfTokenManager: HuggingFaceTokenManager,
) : ViewModel() {
    val hasToken: StateFlow<Boolean> = hfTokenManager.savedToken
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun save(token: String) {
        viewModelScope.launch { hfTokenManager.setToken(token) }
    }

    fun clear() {
        viewModelScope.launch { hfTokenManager.setToken("") }
    }
}
