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

// Own DataStore file, distinct from HuggingFaceTokenManager's "saarthi_downloads" —
// DataStore enforces a single instance per file per process; a second
// `by preferencesDataStore(...)` delegate pointed at the same file name would crash.
private val Context.downloadFailureDataStore: DataStore<Preferences> by preferencesDataStore("saarthi_download_failures")
private val MODEL_ID_KEY = stringPreferencesKey("last_failed_model_id")
private val REASON_KEY = stringPreferencesKey("last_failure_reason")

/**
 * Persists the reason a model download last failed, so it survives a process
 * restart. [ModelDownloadManager]'s in-memory [com.saarthi.core.inference.model.DownloadProgress.Failed]
 * state is lost if the app is backgrounded and the process is killed before
 * the user reopens it — this gives the next onboarding session something to
 * show instead of a silent, unexplained restart.
 *
 * Single record, not a history — only the most recent failure matters for
 * the resume-detection flow this feeds.
 */
@Singleton
class DownloadFailureStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** (modelId, reason), or null when there's no recorded failure. */
    val lastFailure: Flow<Pair<String, String>?> = context.downloadFailureDataStore.data
        .map { prefs ->
            val modelId = prefs[MODEL_ID_KEY]
            val reason = prefs[REASON_KEY]
            if (modelId != null && reason != null) modelId to reason else null
        }

    suspend fun recordFailure(modelId: String, reason: String) {
        context.downloadFailureDataStore.edit {
            it[MODEL_ID_KEY] = modelId
            it[REASON_KEY] = reason
        }
    }

    suspend fun clear() {
        context.downloadFailureDataStore.edit {
            it.remove(MODEL_ID_KEY)
            it.remove(REASON_KEY)
        }
    }
}
