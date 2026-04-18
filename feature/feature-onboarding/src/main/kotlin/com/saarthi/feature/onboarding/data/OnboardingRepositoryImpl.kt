package com.saarthi.feature.onboarding.data

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.feature.onboarding.domain.OnboardingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject

private val Context.onboardingDataStore: DataStore<Preferences>
        by preferencesDataStore("onboarding_prefs")

private val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
private val SAVED_MODEL_PATH    = stringPreferencesKey("saved_model_path")

private val MODEL_EXTENSIONS = setOf(".bin", ".task", ".tflite")
private val MODEL_NAME_HINTS  = listOf("gemma", "llm", "model", "ai", "inference")

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

    override suspend fun saveModelPath(path: String) {
        context.onboardingDataStore.edit { it[SAVED_MODEL_PATH] = path }
    }

    override fun getModelPath(): String? {
        // Return the last user-confirmed path if the file still exists
        val saved = runCatching {
            kotlinx.coroutines.runBlocking {
                context.onboardingDataStore.data.first()[SAVED_MODEL_PATH]
            }
        }.getOrNull()
        if (saved != null && File(saved).exists()) return saved

        // Auto-scan as fallback
        return scanForModels().firstOrNull()
    }

    override fun scanForModels(): List<String> {
        val candidates = mutableListOf<File>()

        val searchRoots = listOfNotNull(
            File(context.filesDir, "models"),
            context.filesDir,
            context.getExternalFilesDir(null),
            context.getExternalFilesDir("models"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory(),
        )

        for (root in searchRoots) {
            if (root == null || !root.canRead()) continue
            // Shallow + one level deep (avoids scanning entire storage tree)
            root.listFiles()?.forEach { f ->
                if (f.isFile && isModelFile(f)) candidates += f
                else if (f.isDirectory) {
                    f.listFiles()?.forEach { sub ->
                        if (sub.isFile && isModelFile(sub)) candidates += sub
                    }
                }
            }
        }

        return candidates.distinctBy { it.absolutePath }.map { it.absolutePath }
    }

    private fun isModelFile(f: File): Boolean {
        val name = f.name.lowercase()
        val hasExt = MODEL_EXTENSIONS.any { name.endsWith(it) }
        val hasHint = MODEL_NAME_HINTS.any { name.contains(it) }
        return hasExt && (hasHint || f.length() > 100_000_000L) // >100 MB is likely a model
    }
}
