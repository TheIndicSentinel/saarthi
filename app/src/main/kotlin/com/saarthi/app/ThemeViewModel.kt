package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.ThemePreference
import com.saarthi.core.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val themePreference: ThemePreference,
) : ViewModel() {

    val mode: StateFlow<ThemeMode> = themePreference.mode
        .map { runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.DARK) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.DARK)

    fun toggle() {
        val next = if (mode.value == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
        viewModelScope.launch { themePreference.setMode(next.name) }
    }

    fun setMode(target: ThemeMode) {
        viewModelScope.launch { themePreference.setMode(target.name) }
    }
}
