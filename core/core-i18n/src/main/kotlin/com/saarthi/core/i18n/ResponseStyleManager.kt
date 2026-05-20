package com.saarthi.core.i18n

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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

data class ResponseStyle(
    val length: String = "medium",     // short | medium | long
    val tone: String = "balanced",      // warm | balanced | formal
    val languageMix: String = "mix",    // pure | mix | eng
    val showDisclaimers: Boolean = true,
    val includeExamples: Boolean = true,
)

private val LENGTH_KEY = stringPreferencesKey("rs_length")
private val TONE_KEY = stringPreferencesKey("rs_tone")
private val LANG_MIX_KEY = stringPreferencesKey("rs_lang_mix")
private val DISCLAIMERS_KEY = booleanPreferencesKey("rs_disclaimers")
private val EXAMPLES_KEY = booleanPreferencesKey("rs_examples")

/**
 * Persists the user's response-style preferences. Reads via a hot StateFlow so
 * the UI updates immediately when settings change. The prompt builder will
 * consume these via [ResponseStyle] in a follow-up.
 */
@Singleton
class ResponseStyleManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val style: StateFlow<ResponseStyle> = context.dataStore.data
        .map { prefs ->
            ResponseStyle(
                length = prefs[LENGTH_KEY] ?: "medium",
                tone = prefs[TONE_KEY] ?: "balanced",
                languageMix = prefs[LANG_MIX_KEY] ?: "mix",
                showDisclaimers = prefs[DISCLAIMERS_KEY] ?: true,
                includeExamples = prefs[EXAMPLES_KEY] ?: true,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, ResponseStyle())

    suspend fun setLength(value: String) =
        context.dataStore.edit { it[LENGTH_KEY] = value }

    suspend fun setTone(value: String) =
        context.dataStore.edit { it[TONE_KEY] = value }

    suspend fun setLanguageMix(value: String) =
        context.dataStore.edit { it[LANG_MIX_KEY] = value }

    suspend fun setShowDisclaimers(value: Boolean) =
        context.dataStore.edit { it[DISCLAIMERS_KEY] = value }

    suspend fun setIncludeExamples(value: Boolean) =
        context.dataStore.edit { it[EXAMPLES_KEY] = value }
}
