package com.saarthi.core.i18n

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore("saarthi_prefs")

private val LANGUAGE_KEY = stringPreferencesKey("selected_language")

@Singleton
class LanguageManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Application-lifetime scope — starts collecting DataStore immediately on injection
    // so ChatRepositoryImpl and ViewModels see the correct language on first read,
    // not the HINDI default that they would get from a cold Flow.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val selectedLanguage: StateFlow<SupportedLanguage> = context.dataStore.data
        .map { prefs -> SupportedLanguage.fromCode(prefs[LANGUAGE_KEY] ?: SupportedLanguage.HINDI.code) }
        .stateIn(scope, SharingStarted.Eagerly, SupportedLanguage.HINDI)

    /**
     * One-shot, directly-awaited read of the persisted language. Unlike
     * [selectedLanguage].value, this never returns the HINDI seed on a cold
     * process — a BroadcastReceiver or Worker started fresh by AlarmManager /
     * WorkManager can read [selectedLanguage].value microseconds after Hilt
     * constructs this singleton, before the DataStore's first emission has
     * landed, silently showing the wrong language in a notification even
     * though the correct one is on disk. This suspends until the real value
     * is available instead of racing the cached StateFlow.
     */
    suspend fun awaitSelectedLanguage(): SupportedLanguage =
        context.dataStore.data
            .map { prefs -> SupportedLanguage.fromCode(prefs[LANGUAGE_KEY] ?: SupportedLanguage.HINDI.code) }
            .first()

    suspend fun setLanguage(language: SupportedLanguage) {
        context.dataStore.edit { it[LANGUAGE_KEY] = language.code }
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.create(Locale(language.code))
        )
    }

    fun buildLanguageInstruction(language: SupportedLanguage): String =
        "Please respond in ${language.nativeName} (${language.englishName})."
}
