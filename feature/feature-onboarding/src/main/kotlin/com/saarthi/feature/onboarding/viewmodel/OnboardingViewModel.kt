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
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PackType
import com.saarthi.feature.onboarding.domain.OnboardingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val selectedLanguage: SupportedLanguage = SupportedLanguage.ENGLISH,
    val modelCandidates: List<String> = emptyList(),
    val selectedModelPath: String? = null,
    val isScanning: Boolean = false,
    val isModelReady: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val manualPathInput: String = "",
    val testInput: String = "",
    val testResponse: String? = null,
    val isTestLoading: Boolean = false,
    val needsAllFilesPermission: Boolean = false,
)

enum class OnboardingStep { WELCOME, LANGUAGE_SELECT, MODEL_PICK, MODEL_INIT, CHAT_TEST, DONE }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val languageManager: LanguageManager,
    private val inferenceEngine: InferenceEngine,
    private val repository: OnboardingRepository,
) : ViewModel() {

    // When launched from the "Change Model" route, skip straight to model picker
    private val isModelChangeMode: Boolean =
        savedStateHandle.get<Boolean>("modelChange") ?: false

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            step = if (isModelChangeMode) OnboardingStep.MODEL_PICK else OnboardingStep.WELCOME,
        )
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        if (isModelChangeMode) {
            viewModelScope.launch {
                _uiState.update { it.copy(isScanning = true) }
                val found = withContext(Dispatchers.IO) { repository.scanForModels() }
                _uiState.update { it.copy(isScanning = false, modelCandidates = found) }
            }
        }
    }

    // Keeps the ParcelFileDescriptor alive while the model is loading when we
    // can't resolve a direct file path from a content:// URI.
    private var modelPfd: ParcelFileDescriptor? = null

    fun selectLanguage(language: SupportedLanguage) =
        _uiState.update { it.copy(selectedLanguage = language) }

    fun goToLanguageSelect() =
        _uiState.update { it.copy(step = OnboardingStep.LANGUAGE_SELECT) }

    fun proceedToModelPick() {
        _uiState.update { it.copy(step = OnboardingStep.MODEL_PICK, isScanning = true) }
        viewModelScope.launch {
            languageManager.setLanguage(_uiState.value.selectedLanguage)
            val found = withContext(Dispatchers.IO) { repository.scanForModels() }
            _uiState.update { it.copy(isScanning = false, modelCandidates = found) }
        }
    }

    fun selectModel(path: String) =
        _uiState.update { it.copy(selectedModelPath = path, error = null) }

    fun onManualPathChange(text: String) = _uiState.update { it.copy(manualPathInput = text, error = null) }

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
                // Some ROMs don't support per-app intent; fall back to global page
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    fun rescanAfterPermissionGrant() {
        _uiState.update { it.copy(isScanning = true, needsAllFilesPermission = false) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { repository.scanForModels() }
            _uiState.update { it.copy(isScanning = false, modelCandidates = found) }
        }
    }

    fun onModelUriPicked(context: Context, uri: Uri) {
        // Try to take persistable permission (no-op if not supported, safe to ignore)
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        // 1. Try to get a real file-system path first
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

        // 2. Fall back to /proc/self/fd/<n> — keeps the FD open until model is loaded
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

    fun confirmModelAndInit() {
        val path = _uiState.value.selectedModelPath
            ?: _uiState.value.modelCandidates.firstOrNull()
            ?: run {
                _uiState.update { it.copy(error = "Please select a model file first.") }
                return
            }
        _uiState.update { it.copy(step = OnboardingStep.MODEL_INIT, isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                inferenceEngine.initialize(InferenceConfig(modelPath = path))
            }.onSuccess {
                // Close PFD now — model is loaded into memory
                modelPfd?.close()
                modelPfd = null
                // Save only real file paths (not ephemeral /proc/self/fd paths)
                if (!path.startsWith("/proc/self/fd/")) {
                    repository.saveModelPath(path)
                }
                _uiState.update { it.copy(isLoading = false, isModelReady = true, step = OnboardingStep.CHAT_TEST) }
            }.onFailure { e ->
                _uiState.update { it.copy(step = OnboardingStep.MODEL_PICK, isLoading = false, error = "Model load failed: ${e.message}") }
            }
        }
    }

    // ── Chat test ─────────────────────────────────────────────────────────────

    fun onTestInputChange(text: String) = _uiState.update { it.copy(testInput = text) }

    fun sendTestMessage() {
        val msg = _uiState.value.testInput.trim().ifEmpty { return }
        _uiState.update { it.copy(isTestLoading = true, testResponse = null) }
        viewModelScope.launch {
            val prompt = "You are Saarthi, a helpful AI assistant. Answer briefly.\nUser: $msg\nAssistant:"
            val response = runCatching {
                inferenceEngine.generate(prompt, PackType.BASE)
            }.getOrElse { "Error: ${it.message}" }
            _uiState.update { it.copy(isTestLoading = false, testResponse = response) }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            repository.completeOnboarding(_uiState.value.selectedLanguage)
            _uiState.update { it.copy(step = OnboardingStep.DONE) }
        }
    }

    // Legacy entry point
    fun proceedToModelInit() = confirmModelAndInit()

    // ── URI → real path ───────────────────────────────────────────────────────

    private fun resolveRealPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path

        if (uri.scheme != "content") return null

        // DocumentsContract-based resolution
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            when (uri.authority) {
                "com.android.externalstorage.documents" -> {
                    // primary:Download/file.bin
                    val parts = docId.split(":")
                    if (parts.size >= 2 && parts[0].equals("primary", ignoreCase = true)) {
                        return "${Environment.getExternalStorageDirectory()}/${parts[1]}"
                    }
                }
                "com.android.providers.downloads.documents" -> {
                    if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
                    if (docId.startsWith("/")) return docId
                    // msf:<id> or plain numeric id
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
                        // Fallback: public downloads provider
                        val altUri = android.content.ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"), numId
                        )
                        queryDataColumn(context, altUri)?.let { return it }
                    }
                }
            }
        }

        // Generic MediaStore DATA column
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
