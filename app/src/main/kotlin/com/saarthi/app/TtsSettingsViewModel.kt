package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.EntitlementManager
import com.saarthi.core.i18n.TtsPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TtsSettingsViewModel @Inject constructor(
    private val ttsPreference: TtsPreference,
    private val entitlements: EntitlementManager,
) : ViewModel() {
    val autoSpeak: StateFlow<Boolean> = ttsPreference.autoSpeakReplies

    /** Auto-read-every-reply (hands-free) is a Pro feature; manual Listen stays free. */
    val isPro: StateFlow<Boolean> = entitlements.isPro

    fun toggle() {
        // Defensive: never enable hands-free auto-read for a non-Pro user, even
        // if the UI is bypassed. The Settings row routes free users to the
        // paywall instead of showing the toggle.
        if (!autoSpeak.value && !entitlements.isPro.value) return
        viewModelScope.launch { ttsPreference.setAutoSpeak(!autoSpeak.value) }
    }
}
