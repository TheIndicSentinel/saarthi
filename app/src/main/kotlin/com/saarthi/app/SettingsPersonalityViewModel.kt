package com.saarthi.app

import androidx.lifecycle.ViewModel
import com.saarthi.core.i18n.Personality
import com.saarthi.core.i18n.PersonalityPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Tiny read-only VM so the Settings list row can show the active persona
 * name + emoji without depending on the chat feature module.
 */
@HiltViewModel
class SettingsPersonalityViewModel @Inject constructor(
    personalityPreference: PersonalityPreference,
) : ViewModel() {
    val active: StateFlow<Personality> = personalityPreference.selected
}
