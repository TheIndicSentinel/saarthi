package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.DeviceProfiler
import com.saarthi.core.inference.ModelCatalog
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.feature.assistant.data.VoicePackManager
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
    private val languageManager: LanguageManager,
    private val deviceProfiler: DeviceProfiler,
    private val voicePackManager: VoicePackManager,
) : ViewModel() {

    private val _startState = MutableStateFlow<AppStartState>(AppStartState.Loading)
    val startState: StateFlow<AppStartState> = _startState.asStateFlow()

    val currentLanguage: StateFlow<SupportedLanguage> = languageManager.selectedLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, SupportedLanguage.HINDI)

    fun setLanguage(language: com.saarthi.core.i18n.SupportedLanguage) = viewModelScope.launch {
        languageManager.setLanguage(language)
    }

    init {
        // Reload any previously installed voice pack into TtsManager on startup.
        voicePackManager.restoreOnStartup()
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

            // Pass maxTokens=0 so LiteRTInferenceEngine picks the tier-aware default
            // based on the model size / display name (Gemma 4 → 2048, mid → 1024,
            // 1B / Compact → 512). See LiteRTInferenceEngine.effectiveMaxTokens.
            val profile = deviceProfiler.profile()
            val config = InferenceConfig(
                modelPath  = modelPath,
                modelName  = catalogEntry?.displayName,
                maxTokens  = 0,
                nThreads   = profile.recommendedThreads,
            )


            // Background initialization
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    inferenceEngine.initialize(config)
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


    fun retryWithNewModel() {
        _startState.value = AppStartState.GoToOnboarding
    }
}
