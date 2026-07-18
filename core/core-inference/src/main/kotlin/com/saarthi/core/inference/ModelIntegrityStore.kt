package com.saarthi.core.inference

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// Own DataStore file, distinct from HuggingFaceTokenManager's "saarthi_downloads"
// and DownloadFailureStore's "saarthi_download_failures" — DataStore enforces a
// single instance per file per process; a second `by preferencesDataStore(...)`
// delegate pointed at the same file name would crash.
private val Context.modelIntegrityDataStore: DataStore<Preferences> by preferencesDataStore("saarthi_model_integrity")

/**
 * Caches the outcome of a model file's SHA-256 verification, keyed to the
 * exact (size, lastModified) the file had when checked — so hashing a
 * multi-GB file happens once per file, not on every app launch/reattach.
 * If the file is ever replaced (re-download, restore from a different
 * source), its (size, lastModified) pair changes and the stale cache entry
 * is naturally ignored rather than needing explicit invalidation.
 *
 * A single cached verdict per file (matched or not), not a running log —
 * this is a reattachment-cost optimization, not an audit trail. See
 * [ModelDownloadManager]'s background verification pass, which is the only
 * writer/reader of this store.
 */
@Singleton
class ModelIntegrityStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Returns the cached verdict (true = hash matched) if one exists for
     * this exact file state, or null if nothing's cached yet or the file
     * has changed since it was last checked (different size/mtime).
     */
    suspend fun cachedVerdict(fileName: String, sizeBytes: Long, lastModifiedMs: Long): Boolean? {
        val prefs = context.modelIntegrityDataStore.data.first()
        val storedSize = prefs[longPreferencesKey("integrity_${fileName}_size")] ?: return null
        val storedMtime = prefs[longPreferencesKey("integrity_${fileName}_mtime")] ?: return null
        if (storedSize != sizeBytes || storedMtime != lastModifiedMs) return null
        return prefs[booleanPreferencesKey("integrity_${fileName}_matched")]
    }

    suspend fun recordVerdict(fileName: String, sizeBytes: Long, lastModifiedMs: Long, matched: Boolean) {
        context.modelIntegrityDataStore.edit {
            it[longPreferencesKey("integrity_${fileName}_size")] = sizeBytes
            it[longPreferencesKey("integrity_${fileName}_mtime")] = lastModifiedMs
            it[booleanPreferencesKey("integrity_${fileName}_matched")] = matched
        }
    }
}
