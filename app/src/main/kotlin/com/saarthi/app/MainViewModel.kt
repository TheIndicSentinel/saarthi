package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.DeviceProfiler
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
    private val deviceProfiler: DeviceProfiler,
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

            // Move to Home INSTANTLY if we have a model path.
            // Initialization happens in the background.
            _startState.value = AppStartState.GoToHome

            val catalogEntry = modelCatalog.allModels.find {
                modelPath.endsWith(it.fileName)
            }

            // maxTokens = the KV-cache size MediaPipe pre-allocates AND the model's
            // maximum total sequence (input + output). Must match the model's compiled-in
            // context length for .task models (Gemma 3/3n = 1280, Gemma 2 = 2048).
            // For .litertlm models (Gemma 4 with 128K training context) cap at 2048
            // to avoid massive pre-allocation.
            val maxTokens = (catalogEntry?.contextLength ?: 1280).coerceIn(1280, 2048)
            val profile = deviceProfiler.profile()
            val config = InferenceConfig(
                modelPath  = modelPath,
                modelName  = catalogEntry?.displayName,
                maxTokens  = maxTokens,
                nCtx       = maxTokens,
                nThreads   = profile.recommendedThreads,
                nGpuLayers = catalogEntry?.nGpuLayers    ?: 0,
            )


            // Background initialization
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    inferenceEngine.initialize(config)
                    restoreModelFamily(modelPath)
                }.onFailure { e ->
                    val msg = when {
                        e is OutOfMemoryError ->
                            "Not enough RAM to load the saved model.\n\nClose background apps and retry, or select a smaller model."
                        e.message?.isNotBlank() == true -> e.message!!
                        else -> "Failed to load AI model (${e.javaClass.simpleName})"
                    }
                    com.saarthi.core.inference.DebugLogger.log("MAIN", "Background init failed: $msg")
                }
            }
        }
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
