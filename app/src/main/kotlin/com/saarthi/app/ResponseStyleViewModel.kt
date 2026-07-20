package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.ReplyLanguageMix
import com.saarthi.core.i18n.ReplyLength
import com.saarthi.core.i18n.ReplyTone
import com.saarthi.core.i18n.ResponseStyle
import com.saarthi.core.i18n.ResponseStyleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ResponseStyleViewModel @Inject constructor(
    private val manager: ResponseStyleManager,
) : ViewModel() {

    val style: StateFlow<ResponseStyle> = manager.style

    fun setLength(value: ReplyLength) = viewModelScope.launch { manager.setLength(value) }
    fun setTone(value: ReplyTone) = viewModelScope.launch { manager.setTone(value) }
    fun setLanguageMix(value: ReplyLanguageMix) = viewModelScope.launch { manager.setLanguageMix(value) }
    fun setIncludeExamples(value: Boolean) = viewModelScope.launch { manager.setIncludeExamples(value) }
}
