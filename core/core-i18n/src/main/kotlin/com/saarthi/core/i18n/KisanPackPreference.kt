package com.saarthi.core.i18n

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
 * Persisted state for the Kisan knowledge pack.
 *
 *   • `installedVersion` — version number of the pack snapshot
 *     currently materialised into `rag_chunks` under the
 *     `global_pack_kisan` sessionId. 0 = nothing installed yet.
 *   • `language` — language code of the installed pack (server can
 *     serve different language variants; we track which one is here
 *     to decide if a language switch needs a fresh download).
 *   • `lastUpdateCheckMs` — timestamp of the last successful manifest
 *     fetch. Lets the update worker rate-limit polls beyond the
 *     WorkManager schedule.
 *
 * Memory-only state (currently downloading / installing flags) is
 * deliberately NOT stored here — that belongs in a separate
 * `MutableStateFlow` exposed by the update worker; a preference store
 * for transient state would leak stale "downloading" flags across crashes.
 */
@Singleton
class KisanPackPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val versionKey  = intPreferencesKey("kisan_pack_version")
    private val langKey     = stringPreferencesKey("kisan_pack_language")
    private val checkedKey  = longPreferencesKey("kisan_pack_last_check_ms")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val installedVersion: StateFlow<Int> = context.dataStore.data
        .map { it[versionKey] ?: 0 }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val installedLanguage: StateFlow<String> = context.dataStore.data
        .map { it[langKey] ?: "en" }
        .stateIn(scope, SharingStarted.Eagerly, "en")

    suspend fun recordInstall(version: Int, language: String) {
        context.dataStore.edit {
            it[versionKey] = version
            it[langKey] = language
        }
    }

    val lastUpdateCheckMs: StateFlow<Long> = context.dataStore.data
        .map { it[checkedKey] ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    suspend fun recordUpdateCheck(timestampMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit { it[checkedKey] = timestampMs }
    }
}
