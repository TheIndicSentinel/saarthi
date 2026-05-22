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
 * Persisted "Personality Pal" choice.
 *
 * Global, not per-session — switching personality starts a new chat (see
 * AssistantViewModel.setPersonality), so the chat's KV cache always matches
 * the persona that authored its turns.
 */
@Singleton
class PersonalityPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("personality_id")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Currently-selected [Personality]; defaults to [PersonalityCatalog.SAARTHI]. */
    val selected: StateFlow<Personality> = context.dataStore.data
        .map { prefs -> PersonalityCatalog.byId(prefs[key]) }
        .stateIn(scope, SharingStarted.Eagerly, PersonalityCatalog.SAARTHI)

    suspend fun set(personalityId: String) {
        context.dataStore.edit { it[key] = personalityId }
    }
}
