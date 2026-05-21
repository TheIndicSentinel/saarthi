package com.saarthi.core.i18n

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * Persisted theme preference — DARK (default) or LIGHT. Read as a hot
 * StateFlow so the UI flips instantly when toggled.
 *
 * The actual palette swap happens in `SaarthiTheme` (core-ui) — this manager
 * just owns the persisted bit.
 */
@Singleton
class ThemePreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("theme_mode")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val mode: StateFlow<String> = context.dataStore.data
        .map { prefs -> prefs[key] ?: "DARK" }
        .stateIn(scope, SharingStarted.Eagerly, "DARK")

    suspend fun setMode(value: String) {
        context.dataStore.edit { it[key] = value }
    }
}
