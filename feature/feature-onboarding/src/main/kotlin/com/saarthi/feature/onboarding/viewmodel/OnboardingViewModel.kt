package com.saarthi.feature.onboarding.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.feature.onboarding.domain.OnboardingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val selectedLanguage: SupportedLanguage = SupportedLanguage.ENGLISH,
    val isModelReady: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

enum class OnboardingStep { WELCOME, LANGUAGE_SELECT, MODEL_INIT, DONE }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val languageManager: LanguageManager,
    private val inferenceEngine: InferenceEngine,
    private val repository: OnboardingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun selectLanguage(language: SupportedLanguage) {
        _uiState.update { it.copy(selectedLanguage = language) }
    }

    fun proceedToModelInit() {
        _uiState.update { it.copy(step = OnboardingStep.MODEL_INIT, isLoading = true) }
        viewModelScope.launch {
            languageManager.setLanguage(_uiState.value.selectedLanguage)
            runCatching {
                val modelPath = checkNotNull(repository.getModelPath()) { "Model not found on device" }
                inferenceEngine.initialize(InferenceConfig(modelPath = modelPath))
            }.onSuccess {
                _uiState.update { it.copy(isModelReady = true, isLoading = false, step = OnboardingStep.DONE) }
                repository.completeOnboarding(_uiState.value.selectedLanguage)
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun goToLanguageSelect() {
        _uiState.update { it.copy(step = OnboardingStep.LANGUAGE_SELECT) }
    }
}
