package com.saarthi.feature.assistant.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.KisanPackPreference
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.DeviceProfiler
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.DeviceTier
import com.saarthi.core.inference.prompt.SystemPromptProvider
import com.saarthi.feature.assistant.data.KisanPackInstaller
import com.saarthi.feature.assistant.data.PackUpdateChecker
import com.saarthi.feature.assistant.data.PackUpdateOutcome
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
 *  • the in-app refresh tap, which also runs [PackUpdateChecker] when a
 *    manifest URL is configured (same path as PackUpdateWorker).
 */
@HiltViewModel
class KisanPackViewModel @Inject constructor(
    private val installer: KisanPackInstaller,
    private val preference: KisanPackPreference,
    private val inferenceEngine: InferenceEngine,
    private val systemPromptProvider: SystemPromptProvider,
    private val packUpdateChecker: PackUpdateChecker,
    deviceProfiler: DeviceProfiler,
    languageManager: LanguageManager,
) : ViewModel() {

    /**
     * Whether THIS device can run a non-compact model at all. On LOW / MINIMAL
     * tier the catalog only ever offers the Compact 1B (the budget SM-E625F log
     * showed `offered=1, filtered_out=5`). Telling such a user to "switch to
     * Gemma 4 / 3n" is a dead-end — those models physically cannot load here.
     * Derived from total RAM (a fixed hardware spec), so it's computed once.
     */
    private val canRunBetterModel: Boolean =
        runCatching { deviceProfiler.profile().tier.ordinal >= DeviceTier.MID.ordinal }
            .getOrDefault(true)

    /** Selected language — the screen localizes its labels off this. */
    val language: StateFlow<SupportedLanguage> = languageManager.selectedLanguage

    data class UiState(
        val loading: Boolean = true,
        val pack: KisanPackInstaller.InstalledPack? = null,
        /** False on Gemma 1B (COMPACT) — pack chat is browse-only; grounded pack RAG needs a larger model. */
        val packSupportedOnCurrentModel: Boolean = true,
        /** False on LOW/MINIMAL devices that can never run a bigger model. */
        val canRunBetterModel: Boolean = true,
        val activeModelName: String? = null,
        val checkingUpdate: Boolean = false,
        val updateMessage: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Convenience shortcut for the screen to show "Pack v1" header. */
    val installedVersion: StateFlow<Int> = preference.installedVersion

    /** The user's selected state (empty = unset) — for the landing-page picker. */
    val userState: StateFlow<String> = preference.userState
    fun setUserState(state: String) {
        viewModelScope.launch { runCatching { preference.setUserState(state) } }
    }

    init {
        // Push the live model tier into the UI state so the "model
        // capable?" hint reflects the current selection in realtime.
        inferenceEngine.activeModelNameFlow
            .map { name ->
                // Same policy the shared pack-chat engine enforces — packs need
                // STANDARD+; the compact 1B is browse-only (it loops on grounded
                // answers). Centralised so every pack screen agrees.
                Pair(name, systemPromptProvider.supportsPackChat(name))
            }
            .onEach { (name, capable) ->
                _ui.update {
                    it.copy(
                        activeModelName = name,
                        packSupportedOnCurrentModel = capable,
                        canRunBetterModel = canRunBetterModel,
                    )
                }
            }
            .launchIn(viewModelScope)

        // Initial pack load.
        loadPack()
    }

    /** Re-read the pack from disk; if a remote manifest is configured, check for updates first. */
    fun refresh() {
        viewModelScope.launch {
            if (_ui.value.checkingUpdate) return@launch
            if (packUpdateChecker.isRemoteConfigured) {
                _ui.update { it.copy(checkingUpdate = true, updateMessage = null) }
                val outcome = runCatching { packUpdateChecker.checkAndInstall() }
                    .getOrDefault(PackUpdateOutcome.NetworkFailed)
                _ui.update {
                    it.copy(checkingUpdate = false, updateMessage = messageFor(outcome))
                }
            }
            loadPack()
        }
    }

    private fun messageFor(outcome: PackUpdateOutcome): String? = when (outcome) {
        PackUpdateOutcome.UpToDate -> "Already up to date"
        is PackUpdateOutcome.Updated -> "Updated"
        PackUpdateOutcome.NetworkFailed,
        PackUpdateOutcome.KeptCurrent,
        PackUpdateOutcome.AppTooOld -> "Couldn't update"
        PackUpdateOutcome.Unavailable,
        PackUpdateOutcome.Busy -> null
    }

    private fun loadPack() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val pack = runCatching { installer.loadInstalledPack() }.getOrNull()
            _ui.update { it.copy(loading = false, pack = pack) }
        }
    }
}
