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
 * Privacy preference: when true, voice input may only use the platform
 * on-device speech recognizer. Cloud / standard SpeechRecognizer fallback
 * is blocked (Point 6 — opt-in strict mode).
 *
 * Default **false** so voice keeps working on devices without an on-device
 * speech model; disclosure when the cloud path is used lives in the voice UI.
 */
@Singleton
class VoicePrivacyPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = booleanPreferencesKey("on_device_voice_only")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val onDeviceVoiceOnly: StateFlow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[key] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun setOnDeviceVoiceOnly(value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }
}
