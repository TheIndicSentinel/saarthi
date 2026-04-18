package com.saarthi.feature.onboarding.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.feature.onboarding.domain.OnboardingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import android.os.Environment
import java.io.File
import javax.inject.Inject

private val Context.onboardingDataStore: DataStore<Preferences>
        by preferencesDataStore("onboarding_prefs")

private val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")

private const val MODEL_SUBDIR = "models"
// Accepted filenames — order matters: most specific first
private val MODEL_FILENAMES = listOf(
    "gemma-2-2b-it-gpu.bin",
    "gemma2-2b-it-gpu-int8.bin",
    "gemma-2b-it-gpu-int8.bin",
)

class OnboardingRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val languageManager: LanguageManager,
) : OnboardingRepository {

    override fun isOnboardingComplete(): Flow<Boolean> =
        context.onboardingDataStore.data.map { it[ONBOARDING_COMPLETE] ?: false }

    override suspend fun completeOnboarding(selectedLanguage: SupportedLanguage) {
        languageManager.setLanguage(selectedLanguage)
        context.onboardingDataStore.edit { it[ONBOARDING_COMPLETE] = true }
    }

    override fun getModelPath(): String? {
        val searchDirs = listOfNotNull(
            File(context.filesDir, MODEL_SUBDIR),           // internal app storage
            context.getExternalFilesDir(null),              // Android/data/com.saarthi.app/files/
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        )
        for (dir in searchDirs) {
            for (name in MODEL_FILENAMES) {
                val f = File(dir, name)
                if (f.exists()) return f.absolutePath
            }
        }
        return null
    }
}
