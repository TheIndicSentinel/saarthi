package com.saarthi.feature.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the set of voice pack IDs that have been successfully downloaded
 * and validated. Survives process kill and app restart.
 *
 * Uses the same "saarthi_prefs" DataStore instance referenced throughout
 * the app — safe because DataStore is multi-reader thread-safe. A separate
 * delegate here instead of sharing the core-i18n one because that delegate
 * is `internal` to its module.
 */
@Singleton
class VoicePackPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val KEY = stringSetPreferencesKey("installed_voice_packs")

    val installedPackIds: Flow<Set<String>> =
        context.voiceDataStore.data.map { prefs -> prefs[KEY] ?: emptySet() }

    suspend fun markInstalled(packId: String) {
        context.voiceDataStore.edit { prefs ->
            prefs[KEY] = (prefs[KEY] ?: emptySet()) + packId
        }
    }

    suspend fun markRemoved(packId: String) {
        context.voiceDataStore.edit { prefs ->
            prefs[KEY] = (prefs[KEY] ?: emptySet()) - packId
        }
    }

    // ── Voice gender preference ───────────────────────────────────────────────

    private val GENDER_KEY = stringPreferencesKey("voice_gender")

    /** "male" or "female". Defaults to "male" (soothing Pratham voice). */
    val voiceGender: Flow<String> =
        context.voiceDataStore.data.map { prefs -> prefs[GENDER_KEY] ?: "male" }

    suspend fun setVoiceGender(gender: String) {
        context.voiceDataStore.edit { prefs -> prefs[GENDER_KEY] = gender }
    }
}

// DataStore delegates must be top-level properties in the file.
// "saarthi_prefs" matches the store used by LanguageManager / KisanPackPreference,
// so all preference files are in the same store (DataStore coalesces by name).
private val Context.voiceDataStore: DataStore<Preferences> by preferencesDataStore("saarthi_prefs")
