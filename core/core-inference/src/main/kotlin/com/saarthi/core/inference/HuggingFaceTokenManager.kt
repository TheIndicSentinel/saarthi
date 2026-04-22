package com.saarthi.core.inference

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.hfDataStore: DataStore<Preferences> by preferencesDataStore("saarthi_downloads")
private val HF_TOKEN_KEY = stringPreferencesKey("hf_token")

/**
 * Persists a HuggingFace access token so authenticated models (e.g. Gemma family)
 * can be downloaded without the user re-entering their token on every launch.
 *
 * Token is free — get one at huggingface.co/settings/tokens (read-only scope is enough).
 */
@Singleton
class HuggingFaceTokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val token: Flow<String> = context.hfDataStore.data
        .map { prefs -> prefs[HF_TOKEN_KEY] ?: "" }

    suspend fun setToken(token: String) {
        context.hfDataStore.edit { it[HF_TOKEN_KEY] = token.trim() }
    }
}
