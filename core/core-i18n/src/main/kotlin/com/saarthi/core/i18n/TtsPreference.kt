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
 * Persisted preference: should Saarthi automatically read every assistant
 * reply aloud once it finishes streaming? Default off — opt-in feature.
 *
 * The Listen action on each AI bubble is always available; this preference
 * just controls whether speak() fires implicitly on stream-complete.
 */
@Singleton
class TtsPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = booleanPreferencesKey("tts_auto_speak")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val autoSpeakReplies: StateFlow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[key] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun setAutoSpeak(value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }
}
