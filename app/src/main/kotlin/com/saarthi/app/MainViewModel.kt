package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.ModelCatalog
import com.saarthi.core.inference.PackAdapterManager
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.feature.onboarding.domain.OnboardingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AppStartState {
    object Loading : AppStartState()
    object GoToOnboarding : AppStartState()
    object GoToHome : AppStartState()
    data class ModelError(val message: String) : AppStartState()
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val onboardingRepository: OnboardingRepository,
    private val inferenceEngine: InferenceEngine,
    private val modelCatalog: ModelCatalog,
    private val packAdapterManager: PackAdapterManager,
    private val languageManager: LanguageManager,
) : ViewModel() {

    private val _startState = MutableStateFlow<AppStartState>(AppStartState.Loading)
    val startState: StateFlow<AppStartState> = _startState.asStateFlow()

    val currentLanguage: StateFlow<SupportedLanguage> = languageManager.selectedLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, SupportedLanguage.HINDI)

    fun setLanguage(language: com.saarthi.core.i18n.SupportedLanguage) = viewModelScope.launch {
        languageManager.setLanguage(language)
    }

    init {
        viewModelScope.launch {
            val isComplete = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                onboardingRepository.isOnboardingComplete().first()
            }
            
            if (!isComplete) {
                _startState.value = AppStartState.GoToOnboarding
                return@launch
            }

            val modelPath = onboardingRepository.getModelPath()
            if (modelPath == null) {
                _startState.value = AppStartState.GoToOnboarding
                return@launch
            }

            // Legacy MediaPipe models (.task / .litertlm / .bin) are no longer supported.
            // Redirect to model selection rather than crashing with "Another handler".
            if (isLegacyMediaPipePath(modelPath)) {
                com.saarthi.core.inference.DebugLogger.log("MAIN",
                    "Legacy MediaPipe model detected — redirecting to model selection: $modelPath")
                onboardingRepository.clearModelPath()
                _startState.value = AppStartState.GoToOnboarding
                return@launch
            }

            val catalogEntry = modelCatalog.allModels.find {
                modelPath.endsWith(it.fileName)
            }
            val config = InferenceConfig(
                modelPath  = modelPath,
                nCtx       = catalogEntry?.contextLength ?: 2048,
                nThreads   = (Runtime.getRuntime().availableProcessors() / 2).coerceAtMost(4).coerceAtLeast(2),
                nGpuLayers = catalogEntry?.nGpuLayers    ?: 0,
            )

            runCatching {
                inferenceEngine.initialize(config)
                restoreModelFamily(modelPath)
                _startState.value = AppStartState.GoToHome
            }.onFailure { e ->
                val msg = when {
                    e is OutOfMemoryError ->
                        "Not enough RAM to load the saved model.\n\nClose background apps and retry, or select a smaller model."
                    e.message?.isNotBlank() == true -> e.message!!
                    else -> "Failed to load AI model (${e.javaClass.simpleName})"
                }
                com.saarthi.core.inference.DebugLogger.log("MAIN", "Startup init failed: $msg")
                _startState.value = AppStartState.ModelError(msg)
            }
        }
    }

    private fun isLegacyMediaPipePath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".task") || lower.endsWith(".litertlm") ||
               lower.endsWith(".litert") || lower.endsWith(".tflite")
    }

    private fun restoreModelFamily(modelPath: String) {
        val family = modelCatalog.allModels
            .find { modelPath.endsWith(it.fileName) }
            ?.modelFamily ?: "unknown"
        packAdapterManager.setActiveModelFamily(family)
    }

    fun retryWithNewModel() {
        _startState.value = AppStartState.GoToOnboarding
    }
}
