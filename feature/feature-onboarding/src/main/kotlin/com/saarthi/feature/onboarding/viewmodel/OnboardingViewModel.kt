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
import com.saarthi.core.inference.PackAdapterManager
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.DeviceProfile
import com.saarthi.core.inference.model.DownloadProgress
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.ModelEntry
import com.saarthi.core.inference.model.PackType
import com.saarthi.core.inference.DebugLogger
import com.saarthi.feature.onboarding.domain.OnboardingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

enum class OnboardingStep { WELCOME, LANGUAGE_SELECT, MODEL_PICK, MODEL_INIT, CHAT_TEST, DONE }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val languageManager: LanguageManager,
    private val inferenceEngine: InferenceEngine,
    private val repository: OnboardingRepository,
    private val deviceProfiler: DeviceProfiler,
    private val modelCatalog: ModelCatalog,
    private val downloadManager: ModelDownloadManager,
    private val packAdapterManager: PackAdapterManager,
    private val hfTokenManager: HuggingFaceTokenManager,
) : ViewModel() {

    private val isModelChangeMode: Boolean =
        savedStateHandle.get<Boolean>("modelChange") ?: false

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            step = if (isModelChangeMode) OnboardingStep.MODEL_PICK else OnboardingStep.WELCOME,
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
    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        val profile = deviceProfiler.profile()
        val catalog = modelCatalog.recommendedFor(profile)
        _uiState.update { it.copy(deviceProfile = profile, catalogModels = catalog) }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                refreshDownloadedModels()
                restoreActiveDownloads()
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

    fun goToLanguageSelect() =
        _uiState.update { it.copy(step = OnboardingStep.LANGUAGE_SELECT) }

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
        val file = File(path)
        if (!file.exists()) {
            _uiState.update { it.copy(error = "File not found at: $path") }
            return
        }
        if (!file.canRead()) {
            _uiState.update { it.copy(error = "Cannot read file at: $path — try granting All Files Access.") }
            return
        }
        _uiState.update {
            it.copy(
                selectedModelPath = path,
                modelCandidates = (listOf(path) + it.modelCandidates).distinct(),
                error = null,
            )
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
            return
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
            return
        }

        _uiState.update {
            it.copy(error = "Could not open file ($openError). Try using 'Enter path manually' below, or grant All Files Access and rescan.")
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
        if (downloadJobs[model.id]?.isActive == true) return

        val job = viewModelScope.launch {
            downloadManager.download(model).collect { progress ->
                _uiState.update {
                    it.copy(downloadProgress = it.downloadProgress + (model.id to progress))
                }
                if (progress is DownloadProgress.Completed) {
                    val path = progress.filePath
                    DebugLogger.log("DOWNLOAD", "Success: $path")
                    
                    _uiState.update {
                        it.copy(
                            selectedModelPath = path,
                            modelCandidates = (listOf(path) + it.modelCandidates).distinct(),
                            error = null
                        )
                    }
                    
                    viewModelScope.launch(Dispatchers.IO) {
                        refreshDownloadedModels()
                    }
                }
            }
        }
        downloadJobs[model.id] = job
    }

    fun cancelDownload(model: ModelEntry) {
        downloadJobs[model.id]?.cancel()
        downloadJobs.remove(model.id)
        downloadManager.cancelDownload(model)
        _uiState.update {
            it.copy(downloadProgress = it.downloadProgress - model.id)
        }
        refreshDownloadedModels()
    }

    fun deleteModel(model: ModelEntry) {
        downloadJobs[model.id]?.cancel()
        downloadJobs.remove(model.id)
        val file = downloadManager.localPathFor(model)
        DebugLogger.log("DELETE", "Deleting ${file.absolutePath}  exists=${file.exists()}  size=${file.length() / 1_048_576}MB")
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

    private fun restoreActiveDownloads() {
        val activePaths = downloadManager.activeDownloadingPaths()
        if (activePaths.isEmpty()) return
        modelCatalog.allModels.forEach { model ->
            val modelPath = downloadManager.localPathFor(model).absolutePath
            if (activePaths.any { it == modelPath }) {
                downloadModel(model)
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

    fun selectDownloadedModel(model: ModelEntry) {
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

    // ── Model init ────────────────────────────────────────────────────────────

    fun confirmModelAndInit() {
        val path = _uiState.value.selectedModelPath
            ?: _uiState.value.modelCandidates.firstOrNull()
            ?: run {
                _uiState.update { it.copy(error = "Please select or download a model first.") }
                return
            }

        val profile = _uiState.value.deviceProfile
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

        val config = InferenceConfig(
            modelPath   = path,
            maxTokens   = 1024,
            nCtx        = catalogEntry?.contextLength ?: 2048,
            nThreads    = (profile?.cpuCores?.coerceAtMost(6)) ?: 4,
            nGpuLayers  = if (profile?.hasVulkan == true) catalogEntry?.nGpuLayers ?: 0 else 0,
        )

        _uiState.update { it.copy(step = OnboardingStep.MODEL_INIT, isLoading = true, error = null) }
        DebugLogger.log("VMODEL", "Starting model initialization: $path")
        
        viewModelScope.launch {
            runCatching {
                inferenceEngine.initialize(config)
            }.onSuccess {
                DebugLogger.log("VMODEL", "Inference engine initialized successfully")
                modelPfd?.close()
                modelPfd = null
                if (!path.startsWith("/proc/self/fd/")) {
                    repository.saveModelPath(path)
                }
                // Register the active model family so PackAdapterManager can match LoRA adapters
                val family = catalogEntry?.modelFamily ?: "unknown"
                packAdapterManager.setActiveModelFamily(family)
                _uiState.update { it.copy(isLoading = false, isModelReady = true, step = OnboardingStep.CHAT_TEST) }
            }.onFailure { e ->
                _uiState.update { it.copy(step = OnboardingStep.MODEL_PICK, isLoading = false, error = "Model load failed: ${e.message}") }
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            repository.completeOnboarding(_uiState.value.selectedLanguage)
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
