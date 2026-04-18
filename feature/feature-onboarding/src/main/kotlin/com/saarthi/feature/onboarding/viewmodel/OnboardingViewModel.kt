package com.saarthi.feature.onboarding.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.InferenceConfig
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
)

enum class OnboardingStep { WELCOME, LANGUAGE_SELECT, MODEL_PICK, MODEL_INIT, DONE }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val languageManager: LanguageManager,
    private val inferenceEngine: InferenceEngine,
    private val repository: OnboardingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

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

    fun onModelUriPicked(context: Context, uri: Uri) {
        val path = resolveRealPath(context, uri)
        if (path != null && File(path).exists()) {
            _uiState.update {
                it.copy(
                    selectedModelPath = path,
                    modelCandidates = (listOf(path) + it.modelCandidates).distinct(),
                    error = null,
                )
            }
        } else {
            _uiState.update { it.copy(error = "Could not read the selected file. Try moving it to the Downloads folder.") }
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
                repository.saveModelPath(path)
                inferenceEngine.initialize(InferenceConfig(modelPath = path))
            }.onSuccess {
                repository.completeOnboarding(_uiState.value.selectedLanguage)
                _uiState.update { it.copy(isModelReady = true, isLoading = false, step = OnboardingStep.DONE) }
            }.onFailure { e ->
                _uiState.update { it.copy(step = OnboardingStep.MODEL_PICK, isLoading = false, error = e.message) }
            }
        }
    }

    // Legacy entry point kept for back-compat — auto-detects and proceeds
    fun proceedToModelInit() = confirmModelAndInit()

    private fun resolveRealPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path

        // Downloads provider raw path (content://com.android.providers.downloads…/raw:…)
        if (uri.authority?.contains("downloads") == true) {
            uri.lastPathSegment?.let { seg ->
                if (seg.startsWith("raw:")) return seg.removePrefix("raw:")
                if (seg.startsWith("/")) return seg
            }
        }

        // MediaStore DATA column (works on most Android versions)
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (col >= 0) cursor.getString(col) else null
                } else null
            }
        }.getOrNull()
    }
}
