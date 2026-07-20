package com.saarthi.core.i18n

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

enum class ReplyLength { SHORT, MEDIUM, LONG }
enum class ReplyTone { WARM, BALANCED, FORMAL }

/**
 * Code-switching preference, relative to whatever output language is
 * already in effect (SupportedLanguage.systemPromptInstruction elsewhere
 * decides THAT) — never a hardcoded language. See
 * ResponseStyleInstructionCompiler, which is the only place these values
 * become prompt text.
 */
enum class ReplyLanguageMix { PURE, MIX, ENGLISH }

data class ResponseStyle(
    val length: ReplyLength = ReplyLength.MEDIUM,
    val tone: ReplyTone = ReplyTone.BALANCED,
    val languageMix: ReplyLanguageMix = ReplyLanguageMix.MIX,
    val includeExamples: Boolean = true,
    // No showDisclaimers field — deliberately. Safety/medical/legal
    // disclaimer framing is handled entirely by SystemPromptProvider's own
    // baseline instruction (fires only for personalized medical diagnosis,
    // specific legal advice, or tailored investment recommendations — never
    // for general explanations), and nothing in Settings can suppress it.
    // See ResponseStyleInstructionCompiler's kdoc.
)

private val LENGTH_KEY = stringPreferencesKey("rs_length")
private val TONE_KEY = stringPreferencesKey("rs_tone")
private val LANG_MIX_KEY = stringPreferencesKey("rs_lang_mix")
private val EXAMPLES_KEY = booleanPreferencesKey("rs_examples")

// rs_disclaimers (booleanPreferencesKey) is intentionally never read anymore
// — a value left over from an older install is simply ignored, not migrated,
// since there is no replacement setting for it to migrate INTO.

private fun parseLength(raw: String?): ReplyLength = when (raw) {
    "short" -> ReplyLength.SHORT
    "long" -> ReplyLength.LONG
    else -> ReplyLength.MEDIUM // covers "medium" and any invalid/legacy value
}

private fun lengthKey(value: ReplyLength): String = when (value) {
    ReplyLength.SHORT -> "short"
    ReplyLength.MEDIUM -> "medium"
    ReplyLength.LONG -> "long"
}

private fun parseTone(raw: String?): ReplyTone = when (raw) {
    "warm" -> ReplyTone.WARM
    "formal" -> ReplyTone.FORMAL
    else -> ReplyTone.BALANCED
}

private fun toneKey(value: ReplyTone): String = when (value) {
    ReplyTone.WARM -> "warm"
    ReplyTone.BALANCED -> "balanced"
    ReplyTone.FORMAL -> "formal"
}

private fun parseLanguageMix(raw: String?): ReplyLanguageMix = when (raw) {
    "pure" -> ReplyLanguageMix.PURE
    "eng" -> ReplyLanguageMix.ENGLISH
    else -> ReplyLanguageMix.MIX
}

private fun languageMixKey(value: ReplyLanguageMix): String = when (value) {
    ReplyLanguageMix.PURE -> "pure"
    ReplyLanguageMix.MIX -> "mix"
    ReplyLanguageMix.ENGLISH -> "eng"
}

/**
 * Persists the user's response-style preferences. Reads via a hot StateFlow so
 * the UI updates immediately when settings change.
 *
 * Public API is fully typed (enums, not raw strings) — an invalid or legacy
 * persisted value safely falls back to its default via the parse* functions
 * above rather than propagating an unrecognized string into prompt logic.
 */
@Singleton
class ResponseStyleManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val style: StateFlow<ResponseStyle> = context.dataStore.data
        .map { prefs ->
            ResponseStyle(
                length = parseLength(prefs[LENGTH_KEY]),
                tone = parseTone(prefs[TONE_KEY]),
                languageMix = parseLanguageMix(prefs[LANG_MIX_KEY]),
                includeExamples = prefs[EXAMPLES_KEY] ?: true,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, ResponseStyle())

    suspend fun setLength(value: ReplyLength) =
        context.dataStore.edit { it[LENGTH_KEY] = lengthKey(value) }

    suspend fun setTone(value: ReplyTone) =
        context.dataStore.edit { it[TONE_KEY] = toneKey(value) }

    suspend fun setLanguageMix(value: ReplyLanguageMix) =
        context.dataStore.edit { it[LANG_MIX_KEY] = languageMixKey(value) }

    suspend fun setIncludeExamples(value: Boolean) =
        context.dataStore.edit { it[EXAMPLES_KEY] = value }
}
