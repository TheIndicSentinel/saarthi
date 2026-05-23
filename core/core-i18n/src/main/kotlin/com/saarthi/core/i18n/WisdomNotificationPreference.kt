package com.saarthi.core.i18n

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted preference: should Saarthi post a daily wisdom notification?
 *
 * Default ON — the setting is on the Settings screen as opt-out. The
 * scheduling/cancelling side-effect lives in the app module's
 * `WisdomNotificationScheduler` (this class stores state only, no
 * scheduling logic, so core-i18n stays free of Android AlarmManager).
 */
@Singleton
class WisdomNotificationPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = booleanPreferencesKey("daily_wisdom_enabled")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val enabled: StateFlow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[key] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    suspend fun setEnabled(value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }
}
