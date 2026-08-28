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

private val Context.hfDataStore: DataStore<Preferences> by preferencesDataStore("saarthi_downloads")
private val HF_TOKEN_KEY = stringPreferencesKey("hf_token")

/**
 * Provides the HuggingFace Bearer token used for authenticated model downloads.
 *
 * Priority (highest → lowest):
 *   1. User-saved token (DataStore) — set via developer/advanced settings
 *   2. Embedded app token — stored Base64-encoded in BuildConfig.HF_APP_TOKEN_B64
 *      (set at build time via local.properties / CI) and decoded at use, so the
 *      raw hf_… secret is never a plaintext String constant in the dex. See the
 *      build.gradle.kts note; empty stays empty (no auth header).
 *
 * For end users the token is completely transparent — downloads just work.
 * The app-level token only needs "read" scope; accept each Gemma model licence once
 * at huggingface.co/{repo} with the account that owns the token.
 */
@Singleton
class HuggingFaceTokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** User-saved token (overrides the embedded app token when non-empty). */
    val token: Flow<String> = context.hfDataStore.data
        .map { prefs -> prefs[HF_TOKEN_KEY] ?: "" }

    /**
     * The token actually used for downloads: user token if set, otherwise the
     * build-time embedded app token, otherwise empty (no auth).
     */
    val effectiveToken: Flow<String> = token.map { userToken ->
        userToken.ifEmpty { embeddedAppToken() }
    }

    suspend fun setToken(token: String) {
        context.hfDataStore.edit { it[HF_TOKEN_KEY] = token.trim() }
    }

    /**
     * Decodes the Base64-encoded embedded app token (see build.gradle.kts). The
     * encoding keeps the raw hf_… secret out of the dex as a plaintext String
     * constant; the runtime value is identical to what was configured at build
     * time. Empty (unconfigured) → empty, and any decode failure degrades safely
     * to empty (no Authorization header) rather than sending a corrupt token.
     */
    private fun embeddedAppToken(): String {
        val encoded = BuildConfig.HF_APP_TOKEN_B64
        if (encoded.isEmpty()) return ""
        return runCatching {
            String(
                android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP),
                Charsets.UTF_8,
            )
        }.getOrDefault("")
    }
}
