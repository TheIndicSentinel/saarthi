package com.saarthi.core.inference

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.saarthi.core.inference.model.resolveHuggingFaceDownloadToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.hfDataStore: DataStore<Preferences> by preferencesDataStore("saarthi_downloads")
private val HF_TOKEN_KEY = stringPreferencesKey("hf_token")

/**
 * Hugging Face Bearer token used for authenticated model downloads.
 *
 * Only a **user-pasted** token is used (DataStore). The APK does not embed
 * a build-time token — `google/gemma-3n-*` repos need a read-only token the
 * user pastes when they pick that model (onboarding / change-model). Public
 * `litert-community` models download with no Authorization header.
 *
 * Accept each Gemma model licence once at huggingface.co/{repo} with the
 * account that owns the token. Read scope only. Never log the token value.
 */
@Singleton
class HuggingFaceTokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _savedToken = MutableStateFlow("")

    /** User-saved token; empty when unset. Never log this value. */
    val savedToken: StateFlow<String> = _savedToken.asStateFlow()

    /** Alias of [savedToken] for existing collectors. */
    val token: StateFlow<String> get() = savedToken

    /**
     * Token sent as `Authorization: Bearer`. Same as [savedToken] — there is
     * no fallback embedded in the APK.
     */
    val effectiveToken: StateFlow<String> get() = savedToken

    init {
        scope.launch {
            context.hfDataStore.data.collect { prefs ->
                _savedToken.value = resolveHuggingFaceDownloadToken(prefs[HF_TOKEN_KEY] ?: "")
            }
        }
    }

    suspend fun setToken(token: String) {
        val trimmed = resolveHuggingFaceDownloadToken(token)
        context.hfDataStore.edit { it[HF_TOKEN_KEY] = trimmed }
        // Same-process callers (onboarding save → startDownload) must see the
        // new value immediately; DataStore collectors can lag one frame.
        _savedToken.value = trimmed
    }
}
