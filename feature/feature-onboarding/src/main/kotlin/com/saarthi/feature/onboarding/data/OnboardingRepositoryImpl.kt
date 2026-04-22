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

private val MODEL_EXTENSIONS = setOf(".gguf", ".bin", ".task", ".tflite", ".litertlm", ".litert")
private val MODEL_NAME_HINTS  = listOf(
    "gemma", "llm", "model", "ai", "inference",
    "llama", "qwen", "phi", "mistral", "falcon", "stablelm",
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

    override suspend fun saveModelPath(path: String) {
        context.onboardingDataStore.edit { it[SAVED_MODEL_PATH] = path }
    }

    override suspend fun getModelPath(): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val saved = runCatching {
            context.onboardingDataStore.data.first()[SAVED_MODEL_PATH]
        }.getOrNull()
        if (saved != null && File(saved).exists()) return@withContext saved
        scanForModels().firstOrNull()
    }

    override suspend fun scanForModels(): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val candidates = mutableListOf<File>()

        val searchRoots = listOfNotNull(
            context.getExternalFilesDir("models"),
            context.getExternalFilesDir(null),
            File(context.filesDir, "models"),
            context.filesDir,
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        )

        for (root in searchRoots) {
            if (root == null || !root.canRead()) continue
            root.listFiles()?.forEach { f ->
                if (f.isFile && isModelFile(f)) candidates += f
                else if (f.isDirectory) {
                    f.listFiles()?.forEach { sub ->
                        if (sub.isFile && isModelFile(sub)) candidates += sub
                    }
                }
            }
        }

        return candidates.sortedByDescending { it.length() }
            .distinctBy { it.absolutePath }
            .map { it.absolutePath }
    }

    private fun isModelFile(f: File): Boolean {
        val name = f.name.lowercase()
        val hasExt = MODEL_EXTENSIONS.any { name.endsWith(it) }
        val hasHint = MODEL_NAME_HINTS.any { name.contains(it) }
        return hasExt && (hasHint || f.length() > 100_000_000L)
    }
}
