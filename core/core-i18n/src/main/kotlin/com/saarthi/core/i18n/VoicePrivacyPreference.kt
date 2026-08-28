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
 * Unset DataStore key: on-device-only. Cloud / standard SpeechRecognizer
 * fallback is opt-in (turn the Settings toggle off).
 */
const val DEFAULT_ON_DEVICE_VOICE_ONLY: Boolean = true

/**
 * Maps a stored DataStore value to the on-device-only flag.
 * `null` (never set) uses [DEFAULT_ON_DEVICE_VOICE_ONLY]; an explicit
 * `false` is kept so users who allowed phone speech stay that way.
 */
fun resolveOnDeviceVoiceOnlyPreference(stored: Boolean?): Boolean =
    stored ?: DEFAULT_ON_DEVICE_VOICE_ONLY

/**
 * Privacy preference: when true, voice input may only use the platform
 * on-device speech recognizer. Cloud / standard SpeechRecognizer fallback
 * is blocked.
 *
 * Default **true** so voice stays on-device unless the user turns this off
 * in Settings. Existing installs that already stored a value keep it.
 */
@Singleton
class VoicePrivacyPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = booleanPreferencesKey("on_device_voice_only")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val onDeviceVoiceOnly: StateFlow<Boolean> = context.dataStore.data
        .map { prefs -> resolveOnDeviceVoiceOnlyPreference(prefs[key]) }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_ON_DEVICE_VOICE_ONLY)

    suspend fun setOnDeviceVoiceOnly(value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }
}
