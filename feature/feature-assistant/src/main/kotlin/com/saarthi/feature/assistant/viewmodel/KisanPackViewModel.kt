package com.saarthi.feature.assistant.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.KisanPackPreference
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.prompt.SystemPromptProvider
import com.saarthi.feature.assistant.data.KisanPackInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Kisan-pack landing screen.
 *
 * Three things the screen needs to know:
 *  • the installed pack (version, source, entries) — for rendering
 *    the topic list, the source-attribution chip and the refresh status.
 *  • whether the live model is capable of using the pack (STANDARD+
 *    tier) — to show a "switch model for richer answers" hint when
 *    the user is currently on Gemma 1B.
 *  • a lightweight loading flag for the initial pack read.
 *
 * No HTTP / install logic lives here — that's `KisanPackInstaller` and
 * `PackUpdateWorker`. This VM is read-only over what those produce.
 */
@HiltViewModel
class KisanPackViewModel @Inject constructor(
    private val installer: KisanPackInstaller,
    private val preference: KisanPackPreference,
    private val inferenceEngine: InferenceEngine,
    private val systemPromptProvider: SystemPromptProvider,
    languageManager: LanguageManager,
) : ViewModel() {

    /** Selected language — the screen localizes its labels off this. */
    val language: StateFlow<SupportedLanguage> = languageManager.selectedLanguage

    data class UiState(
        val loading: Boolean = true,
        val pack: KisanPackInstaller.InstalledPack? = null,
        /** False on Gemma 1B (COMPACT) — pack chunks won't merge into RAG. */
        val packSupportedOnCurrentModel: Boolean = true,
        val activeModelName: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Convenience shortcut for the screen to show "Pack v1" header. */
    val installedVersion: StateFlow<Int> = preference.installedVersion

    init {
        // Push the live model tier into the UI state so the "model
        // capable?" hint reflects the current selection in realtime.
        inferenceEngine.activeModelNameFlow
            .map { name ->
                val tier = systemPromptProvider.tierFor(name)
                Pair(name, tier != SystemPromptProvider.ModelTier.COMPACT)
            }
            .onEach { (name, capable) ->
                _ui.update { it.copy(activeModelName = name, packSupportedOnCurrentModel = capable) }
            }
            .launchIn(viewModelScope)

        // Initial pack load.
        loadPack()
    }

    /** Re-read the pack from disk — used by the screen's refresh button. */
    fun refresh() {
        loadPack()
    }

    private fun loadPack() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val pack = runCatching { installer.loadInstalledPack() }.getOrNull()
            _ui.update { it.copy(loading = false, pack = pack) }
        }
    }
}
