package com.saarthi.core.i18n

import android.content.Context
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("saarthi_prefs")

private val LANGUAGE_KEY = stringPreferencesKey("selected_language")

@Singleton
class LanguageManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val selectedLanguage: Flow<SupportedLanguage> = context.dataStore.data
        .map { prefs -> SupportedLanguage.fromCode(prefs[LANGUAGE_KEY] ?: SupportedLanguage.ENGLISH.code) }

    suspend fun setLanguage(language: SupportedLanguage) {
        context.dataStore.edit { it[LANGUAGE_KEY] = language.code }
        // Apply via AppCompat — works without restart on API 33+
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.create(Locale(language.code))
        )
    }

    // Inject language preference into prompt for multilingual Gemma responses
    fun buildLanguageInstruction(language: SupportedLanguage): String =
        "Please respond in ${language.nativeName} (${language.englishName})."
}
