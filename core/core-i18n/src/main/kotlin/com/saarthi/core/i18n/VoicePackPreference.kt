package com.saarthi.core.i18n

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the set of downloaded voice pack IDs and the male/female voice
 * preference. Survives process kill and app restart.
 *
 * IMPORTANT: lives in core-i18n and uses the SHARED [Context.dataStore]
 * delegate (the same single "saarthi_prefs" instance used by
 * [LanguageManager], [KisanPackPreference], [EntitlementManager]). A second
 * `preferencesDataStore("saarthi_prefs")` delegate anywhere — even in another
 * module — creates a second active DataStore on the same file and crashes the
 * app on launch with "There are multiple DataStores active for the same file".
 * That is exactly why this class is here and not in feature-assistant.
 */
@Singleton
class VoicePackPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val installedKey = stringSetPreferencesKey("installed_voice_packs")
    private val genderKey = stringPreferencesKey("voice_gender")

    val installedPackIds: Flow<Set<String>> =
        context.dataStore.data.map { prefs -> prefs[installedKey] ?: emptySet() }

    suspend fun markInstalled(packId: String) {
        context.dataStore.edit { prefs ->
            prefs[installedKey] = (prefs[installedKey] ?: emptySet()) + packId
        }
    }

    suspend fun markRemoved(packId: String) {
        context.dataStore.edit { prefs ->
            prefs[installedKey] = (prefs[installedKey] ?: emptySet()) - packId
        }
    }

    /** "male" or "female". Defaults to "male" (the soothing Pratham voice). */
    val voiceGender: Flow<String> =
        context.dataStore.data.map { prefs -> prefs[genderKey] ?: "male" }

    suspend fun setVoiceGender(gender: String) {
        context.dataStore.edit { prefs -> prefs[genderKey] = gender }
    }
}
