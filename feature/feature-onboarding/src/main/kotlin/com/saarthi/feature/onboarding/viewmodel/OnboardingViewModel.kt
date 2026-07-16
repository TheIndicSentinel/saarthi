package com.saarthi.feature.onboarding.viewmodel

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.DeviceProfiler
import com.saarthi.core.inference.HuggingFaceTokenManager
import com.saarthi.core.inference.ModelCatalog
import com.saarthi.core.inference.ModelDownloadManager
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.DeviceProfile
import com.saarthi.core.inference.model.DownloadProgress
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.ModelEntry
import com.saarthi.core.inference.model.PackType
import com.saarthi.core.inference.DebugLogger
import com.saarthi.feature.onboarding.domain.OnboardingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val selectedLanguage: SupportedLanguage = SupportedLanguage.HINDI,
    // Device & catalog
    val deviceProfile: DeviceProfile? = null,
    val catalogModels: List<ModelEntry> = emptyList(),
    val downloadProgress: Map<String, DownloadProgress> = emptyMap(),
    // Local file scanning
    val modelCandidates: List<String> = emptyList(),
    val selectedModelPath: String? = null,
    val isScanning: Boolean = false,
    val isModelReady: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val manualPathInput: String = "",
    val needsAllFilesPermission: Boolean = false,
    val downloadedModelIds: Set<String> = emptySet(),
    /** Persisted reason the auto-picked model's download last failed, if any —
     *  survives a process restart, unlike [error] which clears on a fresh attempt. */
    val lastFailureNote: String? = null,
)

enum class OnboardingStep {
    SPLASH,
    WELCOME,
    LANGUAGE_SELECT,
    PRIVACY,
    MODEL_PICK,
    DOWNLOADING,
    MODEL_INIT,
    CHAT_TEST,
    DONE,
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val languageManager: LanguageManager,
    private val inferenceEngine: InferenceEngine,
    private val repository: OnboardingRepository,
    private val deviceProfiler: DeviceProfiler,
    private val modelCatalog: ModelCatalog,
    private val downloadManager: ModelDownloadManager,
    private val hfTokenManager: HuggingFaceTokenManager,
    private val funnel: com.saarthi.core.inference.FunnelTracker,
    private val failureStore: com.saarthi.core.inference.DownloadFailureStore,
) : ViewModel() {

    private val isModelChangeMode: Boolean =
        savedStateHandle.get<Boolean>("modelChange") ?: false

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            step = if (isModelChangeMode) OnboardingStep.MODEL_PICK else OnboardingStep.SPLASH,
            // Seed with the language the user already picked. Otherwise
            // re-entering onboarding for a model change (or any subsequent
            // run) would default the in-memory selection to HINDI, and
            // completeOnboarding() would clobber the saved language.
            selectedLanguage = languageManager.selectedLanguage.value,
        )
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** Current saved HuggingFace token (empty string = not set). */
    val savedHfToken: StateFlow<String> = hfTokenManager.token
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun saveHfToken(token: String) {
        viewModelScope.launch { hfTokenManager.setToken(token) }
    }

    private var modelPfd: ParcelFileDescriptor? = null

    /** Tracks completed downloads we've already acted on (set selectedModelPath). */
    private val handledCompletions = mutableSetOf<String>()

    /**
     * True when [init] detected an already in-flight/complete auto-model
     * download from a prior process lifetime (killed while backgrounded) and
     * resumed the flow without the user re-answering Splash/Language/Welcome/
     * Privacy. Used by [confirmModelAndInitInternal] to skip the CHAT_TEST
     * confirmation tap on success — nobody was watching setup happen live, so
     * there's no "you're all set!" moment to show; land straight on Home
     * instead, the same way Spotify/Play Store resume a background-completed
     * operation without an extra affirmation screen.
     */
    private var isResumedFlow = false


    init {
        val profile = deviceProfiler.profile()
        val catalog = modelCatalog.recommendedFor(profile)
        // Log what the picker offered vs filtered out — answers "why does this
        // device only see the Compact model?" without guessing.
        com.saarthi.core.inference.DebugLogger.log("CATALOG",
            "tier=${profile.tier}  budget=${profile.safeModelBudgetMb}MB  " +
            "offered=${catalog.size} [${catalog.joinToString { it.id }}]")
        val filtered = modelCatalog.allModels - catalog.toSet()
        if (filtered.isNotEmpty()) {
            com.saarthi.core.inference.DebugLogger.log("CATALOG",
                "filtered_out=${filtered.size} " +
                filtered.joinToString { "${it.id}(needs~${(it.fileSizeBytes / 1_048_576) + 300}MB)" })
        }
        _uiState.update { it.copy(deviceProfile = profile, catalogModels = catalog) }

        // Resume-after-relaunch: if this device's auto-pick model already has
        // bytes on disk (complete, or a genuine partial tmp file), onboarding
        // was already in progress in a prior process lifetime that got killed
        // — most commonly the OS reclaiming the whole process while the app
        // was backgrounded mid-download despite the foreground service. Jump
        // straight back into the download/init screen instead of making the
        // user re-click through Splash → Language → Welcome → Privacy for a
        // choice they already made (field report, Pixel 8, 2026-07-16).
        // Both the resume-detection disk checks below and the funnel-tracking
        // decision that depends on isResumedFlow run in ONE IO-dispatched
        // coroutine, in the same order as before — off the main thread (these
        // were previously synchronous File I/O during ViewModel construction),
        // but with the same internal sequencing so isResumedFlow is always
        // settled before the funnel check reads it.
        viewModelScope.launch(Dispatchers.IO) {
            if (!isModelChangeMode) {
                val autoModel = modelCatalog.autoPick(profile)
                if (autoModel != null) {
                    val alreadyComplete = downloadManager.isDownloaded(autoModel)
                    val hasPartial = !alreadyComplete &&
                        downloadManager.tmpPathFor(autoModel).let { it.exists() && it.length() > 1_000_000L }
                    if (alreadyComplete || hasPartial) {
                        isResumedFlow = true
                        com.saarthi.core.inference.DebugLogger.log("ONBOARDING",
                            "Resuming in-progress auto-model flow after relaunch: " +
                            "${autoModel.id}  complete=$alreadyComplete")
                        _uiState.update {
                            it.copy(
                                step = OnboardingStep.DOWNLOADING,
                                selectedModelPath = downloadManager.localPathFor(autoModel).absolutePath,
                            )
                        }
                        if (alreadyComplete) {
                            confirmModelAndInit()
                        } else {
                            // reattachActiveDownloads() further below resumes the
                            // actual byte transfer; this just waits for it to finish.
                            awaitDownloadThenInit(autoModel)
                        }
                    } else {
                        // Nothing to resume for this model — surface whether the
                        // last attempt failed, so a fresh auto-download doesn't
                        // silently retry with zero context if it fails again the
                        // same way (e.g. the tmp file was cleaned up after an
                        // ENOSPC failure, or the user deleted a corrupt partial).
                        val failure = failureStore.lastFailure.first()
                        if (failure != null && failure.first == autoModel.id) {
                            _uiState.update { it.copy(lastFailureNote = failure.second) }
                        }
                    }
                }
            }

            // Funnel: a genuine first-run onboarding started (not a model-change
            // re-entry, and not a resumed flow re-firing the same session's start event).
            if (!isModelChangeMode && !isResumedFlow) {
                funnel.track(com.saarthi.core.inference.FunnelEvent.ONBOARDING_STARTED)
            }
        }

        // Mirror app-lifetime download progress into UI state.
        viewModelScope.launch {
            // PRE-POPULATE handledCompletions with all already-downloaded models
            // BEFORE collecting allProgress — sequenced in THIS coroutine, not a
            // parallel launch. restoreCompletedStates() re-emits Completed for
            // every file on disk; when the pre-population raced the collector
            // (the old parallel-launch layout), restored events slipped through
            // as "new" completions on every screen open — re-firing the
            // MODEL_DOWNLOAD_COMPLETED funnel event (inflated metrics, seen 6×
            // in one device log) and re-triggering auto-select, which could load
            // a second model into RAM while one is active (OOM kill).
            withContext(Dispatchers.IO) {
                modelCatalog.allModels.forEach { model ->
                    if (downloadManager.isDownloaded(model)) {
                        handledCompletions += model.id
                    }
                }
            }
            downloadManager.allProgress.collect { progressMap ->
                _uiState.update { it.copy(downloadProgress = progressMap) }
                // Only react to NEWLY completed downloads (not restored-from-disk ones).
                val newlyCompleted = progressMap.entries.filter { (modelId, progress) ->
                    progress is DownloadProgress.Completed && modelId !in handledCompletions
                }
                newlyCompleted.forEach { (modelId, progress) ->
                    handledCompletions += modelId
                    funnel.track(com.saarthi.core.inference.FunnelEvent.MODEL_DOWNLOAD_COMPLETED)
                    val path = (progress as DownloadProgress.Completed).filePath
                    DebugLogger.log("DOWNLOAD", "Success: $path")
                    _uiState.update {
                        val currentPath = it.selectedModelPath
                        it.copy(
                            selectedModelPath = currentPath ?: path,
                            modelCandidates = (listOf(path) + it.modelCandidates).distinct(),
                            error = null,
                        )
                    }
                    val s = _uiState.value
                    if (s.step == OnboardingStep.MODEL_PICK && s.selectedModelPath == path) {
                        DebugLogger.log("DOWNLOAD", "Model auto-selected: ${path.substringAfterLast('/')}")
                    }
                }
                if (newlyCompleted.isNotEmpty()) {
                    withContext(Dispatchers.IO) { refreshDownloadedModels() }
                }
            }
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                refreshDownloadedModels()
                // restoreCompletedStates: silently populates UI badges for already-downloaded
                // models WITHOUT triggering the newlyCompleted auto-select path above.
                downloadManager.restoreCompletedStates(modelCatalog.allModels)
                // reattachActiveDownloads: only polls models genuinely still downloading.
                downloadManager.reattachActiveDownloads(modelCatalog.allModels)
                // Pre-populate modelCandidates with every already-downloaded model file.
                // Without this, models that completed while the app was crashed never
                // land in modelCandidates: they're pre-added to handledCompletions (to
                // prevent OOM auto-load), so the allProgress collector skips them in the
                // newlyCompleted path. The user would see the "downloaded" badge but the
                // model wouldn't appear in the picker until a manual rescan.
                val downloadedPaths = modelCatalog.allModels
                    .filter { downloadManager.isDownloaded(it) }
                    .map { downloadManager.localPathFor(it).absolutePath }
                if (downloadedPaths.isNotEmpty()) {
                    _uiState.update { state ->
                        state.copy(
                            modelCandidates = (downloadedPaths + state.modelCandidates).distinct()
                        )
                    }
                }
            }

            if (isModelChangeMode) {
                _uiState.update { it.copy(isScanning = true) }
                val found = scanExcludingActive()
                _uiState.update { it.copy(isScanning = false, modelCandidates = found) }
            }
        }
    }

    fun selectLanguage(language: SupportedLanguage) =
        _uiState.update { it.copy(selectedLanguage = language) }

    fun goToWelcome() =
        _uiState.update { it.copy(step = OnboardingStep.WELCOME) }

    fun goToLanguageSelect() =
        _uiState.update { it.copy(step = OnboardingStep.LANGUAGE_SELECT) }

    fun goToPrivacy() =
        _uiState.update { it.copy(step = OnboardingStep.PRIVACY) }

    fun goBackTo(step: OnboardingStep) =
        _uiState.update { it.copy(step = step) }

    fun proceedToModelPick() {
        _uiState.update { it.copy(step = OnboardingStep.MODEL_PICK, isScanning = true) }
        viewModelScope.launch {
            languageManager.setLanguage(_uiState.value.selectedLanguage)
            val found = withContext(Dispatchers.IO) { scanExcludingActive() }
            _uiState.update { it.copy(isScanning = false, modelCandidates = found) }
        }
    }

    fun selectModel(path: String) =
        _uiState.update { it.copy(selectedModelPath = path, error = null) }

    fun onManualPathChange(text: String) =
        _uiState.update { it.copy(manualPathInput = text, error = null) }

    fun selectModelByManualPath() {
        val path = _uiState.value.manualPathInput.trim().ifEmpty {
            _uiState.update { it.copy(error = "Enter a file path first.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) {
                _uiState.update { it.copy(error = "File not found at: $path") }
                return@launch
            }
            if (!file.canRead()) {
                _uiState.update { it.copy(error = "Cannot read file at: $path — try granting All Files Access.") }
                return@launch
            }
            _uiState.update {
                it.copy(
                    selectedModelPath = path,
                    modelCandidates = (listOf(path) + it.modelCandidates).distinct(),
                    error = null,
                )
            }
        }
    }

    fun checkAndRequestAllFilesAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                _uiState.update { it.copy(needsAllFilesPermission = true) }
                return false
            }
        }
        _uiState.update { it.copy(needsAllFilesPermission = false) }
        return true
    }

    fun openAllFilesAccessSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }.onFailure {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    fun rescanAfterPermissionGrant() {
        _uiState.update { it.copy(isScanning = true, needsAllFilesPermission = false) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { scanExcludingActive() }
            _uiState.update { it.copy(isScanning = false, modelCandidates = found) }
        }
    }

    fun onModelUriPicked(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            val realPath = resolveRealPath(context, uri)
            if (realPath != null && File(realPath).exists()) {
                _uiState.update {
                    it.copy(
                        selectedModelPath = realPath,
                        modelCandidates = (listOf(realPath) + it.modelCandidates).distinct(),
                        error = null,
                    )
                }
                return@launch
            }

            var openError = "openFileDescriptor returned null"
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                openError = "${e.javaClass.simpleName}: ${e.message}"
                null
            }

            if (pfd != null) {
                modelPfd?.close()
                modelPfd = pfd
                val fdPath = "/proc/self/fd/${pfd.fd}"
                _uiState.update {
                    it.copy(
                        selectedModelPath = fdPath,
                        modelCandidates = (listOf(fdPath) + it.modelCandidates).distinct(),
                        error = null,
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(error = "Could not open file ($openError). Try using 'Enter path manually' below, or grant All Files Access and rescan.")
            }
        }
    }

    // ── Scan (excludes actively downloading + incomplete files) ──────────────

    private suspend fun scanExcludingActive(): List<String> {
        val activePaths = downloadManager.activeDownloadingPaths()
        return repository.scanForModels().filter { path ->
            if (path in activePaths) return@filter false
            val file = File(path)
            val catalogEntry = modelCatalog.allModels.find {
                downloadManager.localPathFor(it).absolutePath == path
            }
            val expectedBytes = catalogEntry?.fileSizeBytes ?: 0L
            downloadManager.isFileComplete(file, expectedBytes)
        }
    }

    // ── Catalog download ──────────────────────────────────────────────────────

    fun downloadModel(model: ModelEntry) {
        funnel.track(com.saarthi.core.inference.FunnelEvent.MODEL_DOWNLOAD_STARTED)
        downloadManager.startDownload(model)
    }

    fun restartDownload(model: ModelEntry) {
        downloadManager.restartDownload(model)
    }

    fun cancelDownload(model: ModelEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadManager.cancelDownload(model)
            refreshDownloadedModels()
        }
    }


    fun deleteModel(model: ModelEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = downloadManager.localPathFor(model)
            DebugLogger.log("DELETE", "Deleting ${file.absolutePath}  exists=${file.exists()}  size=${file.length() / 1_048_576}MB")
            downloadManager.cancelDownload(model) // MUST cancel active download so it doesn't auto-complete
            file.delete()
            _uiState.update {
                it.copy(
                    downloadProgress = it.downloadProgress - model.id,
                    downloadedModelIds = it.downloadedModelIds - model.id,
                    selectedModelPath = if (it.selectedModelPath?.endsWith(model.fileName) == true) null else it.selectedModelPath,
                    modelCandidates = it.modelCandidates.filterNot { p -> p.endsWith(model.fileName) },
                    error = null,
                )
            }
        }
    }

    private fun refreshDownloadedModels() {
        val ids = modelCatalog.allModels
            .filter { downloadManager.isDownloaded(it) }
            .map { it.id }
            .toSet()
        _uiState.update { it.copy(downloadedModelIds = ids) }
    }

    /**
     * Mark a catalog model as the user's current pick — even if it isn't
     * downloaded yet. Sets selectedModelPath to where the file *would* live
     * so proceedFromModelPick() can pick it up. Does NOT validate file
     * integrity (use selectDownloadedModel for that).
     */
    fun highlightModel(model: ModelEntry) {
        val path = downloadManager.localPathFor(model).absolutePath
        _uiState.update {
            it.copy(selectedModelPath = path, error = null)
        }
    }

    fun selectDownloadedModel(model: ModelEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = downloadManager.localPathFor(model)
            if (downloadManager.isFileComplete(file, model.fileSizeBytes)) {
                _uiState.update {
                    it.copy(
                        selectedModelPath = file.absolutePath,
                        modelCandidates = (listOf(file.absolutePath) + it.modelCandidates).distinct(),
                        error = null,
                    )
                }
            } else {
                val sizeMb = if (file.exists()) file.length() / 1_048_576 else 0
                val expectedMb = model.fileSizeBytes / 1_048_576
                _uiState.update {
                    it.copy(error = "Download incomplete: ${sizeMb}MB of ${expectedMb}MB. Please wait or re-download.")
                }
            }
        }
    }

    // ── Model init ────────────────────────────────────────────────────────────

    fun confirmModelAndInit() {
        // The whole body (pre-check file I/O + engine init) runs in one IO-
        // dispatched coroutine now — previously the pre-checks (isFileComplete,
        // deviceProfiler.profile()'s StatFs call) ran synchronously on whatever
        // thread called this (often main). inferenceEngine.initialize() already
        // internally hops to its own dispatcher regardless of caller context, so
        // this is a pure thread-placement fix, not a behavior change: every call
        // site already treated this as fire-and-forget / state-flow-observed.
        viewModelScope.launch(Dispatchers.IO) {
            confirmModelAndInitInternal()
        }
    }

    private suspend fun confirmModelAndInitInternal() {
        val path = _uiState.value.selectedModelPath
            ?: _uiState.value.modelCandidates.firstOrNull()
            ?: run {
                _uiState.update { it.copy(error = "Please select or download a model first.") }
                return
            }

        val catalogEntry = modelCatalog.allModels.find {
            downloadManager.localPathFor(it).absolutePath == path || it.fileName == path.substringAfterLast("/")
        }

        // Reject partial downloads before attempting native init
        if (!path.startsWith("/proc/self/fd/")) {
            val file = File(path)
            val expectedBytes = catalogEntry?.fileSizeBytes ?: 0L
            if (!downloadManager.isFileComplete(file, expectedBytes)) {
                val sizeMb = if (file.exists()) file.length() / 1_048_576 else 0
                val expectedMb = expectedBytes / 1_048_576
                val hint = if (expectedMb > 0)
                    "The file is ${sizeMb}MB but ${expectedMb}MB is expected — it is still downloading or corrupted."
                else
                    "The file appears incomplete or corrupted."
                _uiState.update { it.copy(error = "Cannot load model: $hint\n\nWait for the download to finish, or delete and re-download.") }
                return
            }
        }

        // Pass maxTokens=0 so LiteRTInferenceEngine picks the tier-aware default
        // (Gemma 4 → 2048, Gemma 3n / 2 → 1024, Gemma 1B / Compact → 512). Forcing
        // a value here used to short-circuit that logic and starve Gemma 4 turns
        // of response budget (the "incomplete reply" bug). Auto-recovery in the
        // engine still steps down to 256/64 on repeated CPU crashes.
        val profile = deviceProfiler.profile()
        val config = InferenceConfig(
            modelPath   = path,
            modelName   = catalogEntry?.displayName,
            maxTokens   = 0,
            nThreads    = profile.recommendedThreads,
        )

        _uiState.update { it.copy(step = OnboardingStep.MODEL_INIT, isLoading = true, error = null) }
        DebugLogger.log("VMODEL", "Starting model initialization: $path")

        runCatching {
            inferenceEngine.initialize(config)
        }.onSuccess {
            DebugLogger.log("VMODEL", "Inference engine initialized successfully")
            modelPfd?.close()
            modelPfd = null
            if (!path.startsWith("/proc/self/fd/")) {
                repository.saveModelPath(path)
            }
            if (isResumedFlow) {
                // Setup finished while the app was backgrounded/killed —
                // skip the CHAT_TEST confirmation tap (see isResumedFlow
                // kdoc) and go straight to Home.
                _uiState.update { it.copy(isLoading = false, isModelReady = true) }
                completeOnboarding()
            } else {
                _uiState.update { it.copy(isLoading = false, isModelReady = true, step = OnboardingStep.CHAT_TEST) }
            }
        }.onFailure { e ->
            val errorMsg = when {
                e is OutOfMemoryError ->
                    "Not enough RAM to load this model.\n\nClose background apps and try again, or choose a smaller model."
                e.message?.contains("RAM", ignoreCase = true) == true -> e.message!!
                e.message?.isNotBlank() == true -> "Model load failed: ${e.message}"
                else -> "Model load failed (${e.javaClass.simpleName}).\n\nThis model may be too large for your device. Try a smaller model."
            }
            DebugLogger.log("VMODEL", "Init failed: $errorMsg")
            _uiState.update { it.copy(step = OnboardingStep.MODEL_PICK, isLoading = false, error = errorMsg) }
        }
    }

    /**
     * Called when the user taps "Download & Continue" / "Continue" on Onb4.
     * - If a downloaded model is selected → go straight to MODEL_INIT.
     * - Else, start downloading the first recommended model and transition to
     *   DOWNLOADING. When that finishes, the [allProgress] collector will set
     *   selectedModelPath; we observe that and auto-init.
     */
    fun proceedFromModelPick() {
        val s = _uiState.value
        val selectedEntry = s.catalogModels.firstOrNull {
            s.selectedModelPath?.endsWith(it.fileName) == true
        }
        when {
            selectedEntry != null && selectedEntry.id in s.downloadedModelIds -> {
                confirmModelAndInit()
            }
            else -> {
                val model = selectedEntry ?: s.catalogModels.firstOrNull() ?: return
                startDownloadAndAutoInit(model)
            }
        }
    }

    /**
     * First-run onboarding entry point that skips the model picker entirely:
     * auto-selects the catalog's device-appropriate "Recommended" model and
     * starts downloading it immediately. Falls back to the normal picker if
     * no safe model exists for this device (e.g. MINIMAL tier) so the user
     * is never stuck with nothing — and the picker (reached via "back" on
     * the downloading screen, or Settings' "Change Model") still lets anyone
     * override the auto-pick manually.
     */
    fun proceedWithAutoModel() {
        // Must persist the selected language BEFORE starting the download —
        // proceedToModelPick() always did this, but this path skipped it
        // entirely, so LanguageManager stayed on its HINDI seed for the
        // whole download regardless of what the user picked, and the
        // download notification (built from languageManager.selectedLanguage)
        // showed Hindi no matter the selection (field report, 2026-07-16).
        viewModelScope.launch {
            languageManager.setLanguage(_uiState.value.selectedLanguage)
            val model = modelCatalog.autoPick(deviceProfiler.profile())
            if (model == null) {
                proceedToModelPick()
                return@launch
            }
            // Already fully downloaded — most likely the process was killed
            // mid-download while backgrounded and the user reopened the app
            // before onboarding was marked complete, restarting this flow
            // from the top. Skip straight to init instead of re-issuing a
            // download the server has nothing left to give (field report,
            // Pixel 8, 2026-07-16 — surfaced as an HTTP 416 loop that reset
            // the progress UI to 0% on every reopen).
            if (model.id in _uiState.value.downloadedModelIds) {
                _uiState.update {
                    it.copy(
                        step = OnboardingStep.DOWNLOADING,
                        selectedModelPath = downloadManager.localPathFor(model).absolutePath,
                        error = null,
                    )
                }
                confirmModelAndInit()
                return@launch
            }
            startDownloadAndAutoInit(model)
        }
    }

    /** Shared by [proceedFromModelPick] and [proceedWithAutoModel]. */
    private fun startDownloadAndAutoInit(model: ModelEntry) {
        _uiState.update { it.copy(step = OnboardingStep.DOWNLOADING, error = null, lastFailureNote = null) }
        downloadManager.startDownload(model)
        awaitDownloadThenInit(model)
    }

    /**
     * Waits for [model]'s download to reach Completed (via the [allProgress]
     * collector in [init] updating selectedModelPath/downloadedModelIds),
     * then auto-confirms and loads it. Split out from [startDownloadAndAutoInit]
     * so the init{}-level resume-after-relaunch path can observe an already
     * in-flight download (resumed by [ModelDownloadManager.reattachActiveDownloads])
     * without re-issuing its own startDownload() call.
     */
    private fun awaitDownloadThenInit(model: ModelEntry) {
        viewModelScope.launch {
            uiState.first { st ->
                st.step != OnboardingStep.DOWNLOADING ||
                    (st.selectedModelPath?.endsWith(model.fileName) == true &&
                        model.id in st.downloadedModelIds)
            }
            val st = _uiState.value
            if (st.step == OnboardingStep.DOWNLOADING &&
                st.selectedModelPath?.endsWith(model.fileName) == true
            ) {
                confirmModelAndInit()
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            // Defensive: prefer the currently-saved language over the in-memory
            // selection, so a model change flow that never touched the language
            // step cannot clobber the user's onboarding choice.
            val resolved = if (isModelChangeMode) languageManager.selectedLanguage.value
                           else _uiState.value.selectedLanguage
            repository.completeOnboarding(resolved)
            if (!isModelChangeMode) funnel.track(com.saarthi.core.inference.FunnelEvent.ONBOARDING_COMPLETED)
            _uiState.update { it.copy(step = OnboardingStep.DONE) }
        }
    }

    fun proceedToModelInit() = confirmModelAndInit()

    // ── URI → real path ───────────────────────────────────────────────────────

    private fun resolveRealPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null

        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            when (uri.authority) {
                "com.android.externalstorage.documents" -> {
                    val parts = docId.split(":")
                    if (parts.size >= 2 && parts[0].equals("primary", ignoreCase = true)) {
                        return "${Environment.getExternalStorageDirectory()}/${parts[1]}"
                    }
                }
                "com.android.providers.downloads.documents" -> {
                    if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
                    if (docId.startsWith("/")) return docId
                    val numId = docId.removePrefix("msf:").toLongOrNull()
                    if (numId != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            queryDataColumn(
                                context,
                                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL),
                                "${MediaStore.MediaColumns._ID} = ?",
                                arrayOf(numId.toString()),
                            )?.let { return it }
                        }
                        val altUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"), numId
                        )
                        queryDataColumn(context, altUri)?.let { return it }
                    }
                }
            }
        }

        return queryDataColumn(context, uri)
    }

    private fun queryDataColumn(
        context: Context,
        uri: Uri,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            selection, selectionArgs, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val col = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (col >= 0) cursor.getString(col) else null
            } else null
        }
    }.getOrNull()

    override fun onCleared() {
        modelPfd?.close()
        super.onCleared()
    }
}
