package com.saarthi.app.wisdom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.WisdomNotificationPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the "Daily wisdom notifications" toggle on the Settings screen.
 *
 * Owns the side-effect of flipping the preference: when the user turns
 * the toggle on we arm the AlarmManager schedule, when they turn it off
 * we cancel the pending alarm. UI never talks to the scheduler directly
 * so the same flow runs whether the toggle was hit from Settings,
 * onboarding, or any future surface.
 */
@HiltViewModel
class WisdomSettingsViewModel @Inject constructor(
    private val preference: WisdomNotificationPreference,
    private val scheduler: WisdomNotificationScheduler,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = preference.enabled

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            preference.setEnabled(value)
            if (value) scheduler.enable() else scheduler.disable()
        }
    }
}
