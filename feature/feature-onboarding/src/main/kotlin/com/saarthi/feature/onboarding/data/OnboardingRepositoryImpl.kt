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
import java.io.File
import javax.inject.Inject

private val Context.onboardingDataStore: DataStore<Preferences>
        by preferencesDataStore("onboarding_prefs")

private val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")

// Expected model location: app's files dir / models / gemma.bin
// Users can sideload via adb or the app can copy from assets on first run.
private const val MODEL_SUBDIR = "models"
private const val MODEL_FILE   = "gemma-2-2b-it-gpu.bin"

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
        // 1. Check app-internal models dir (copied from assets or sideloaded)
        val internalModel = File(context.filesDir, "$MODEL_SUBDIR/$MODEL_FILE")
        if (internalModel.exists()) return internalModel.absolutePath

        // 2. Fallback: check external storage (for development / sideloading)
        val externalModel = File(context.getExternalFilesDir(null), MODEL_FILE)
        if (externalModel.exists()) return externalModel.absolutePath

        return null
    }
}
