package com.saarthi.core.inference

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

private val Context.generationDataStore: DataStore<Preferences> by preferencesDataStore("saarthi_generation")
private val NORMAL_TEMPERATURE_KEY = floatPreferencesKey("normal_temperature")

/**
 * User-tunable generation parameters. Today this is just the sampling
 * **temperature** for normal (non document-grounded) chat — the control the
 * user added under Settings → Response style.
 *
 * [temperature] == [AUTO] means "use the model's recommended default" (the
 * per-model value the engine already used). So introducing this setting does
 * NOT silently change behaviour for users who never touch it — the slider
 * simply *shows* the current model default until they override it. A value
 * `>= 0` is an explicit override the engine applies to the normal-chat
 * sampler only.
 *
 * Document / pack RAG answers keep their own low, accuracy-focused
 * temperature regardless — grounded Q&A must quote sources, not improvise,
 * so it is intentionally never affected by this preference.
 *
 * Exposed as a hot [StateFlow] so the engine picks up a change on the very
 * next turn: [com.saarthi.core.inference.engine.LiteRTInferenceEngine]
 * recreates the litertlm Conversation after every turn and reads
 * [temperature]`.value` when it rebuilds the sampler.
 */
@Singleton
class GenerationPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val temperature: StateFlow<Float> = context.generationDataStore.data
        .map { prefs -> prefs[NORMAL_TEMPERATURE_KEY] ?: AUTO }
        .stateIn(scope, SharingStarted.Eagerly, AUTO)

    suspend fun setTemperature(value: Float) {
        context.generationDataStore.edit { it[NORMAL_TEMPERATURE_KEY] = value.coerceIn(MIN, MAX) }
    }

    /** Revert to the model's recommended default ([AUTO]). */
    suspend fun resetToAuto() {
        context.generationDataStore.edit { it.remove(NORMAL_TEMPERATURE_KEY) }
    }

    companion object {
        /** Sentinel: no user override — defer to the model's recommended temperature. */
        const val AUTO = -1f
        const val MIN = 0f
        const val MAX = 1.5f
    }
}
