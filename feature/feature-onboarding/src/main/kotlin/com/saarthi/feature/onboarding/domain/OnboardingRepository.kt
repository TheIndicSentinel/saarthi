package com.saarthi.feature.onboarding.domain

import com.saarthi.core.i18n.SupportedLanguage
import kotlinx.coroutines.flow.Flow

interface OnboardingRepository {
    fun isOnboardingComplete(): Flow<Boolean>
    suspend fun completeOnboarding(selectedLanguage: SupportedLanguage)
    suspend fun getModelPath(): String?
    suspend fun scanForModels(): List<String>
    suspend fun saveModelPath(path: String)
    suspend fun clearModelPath()
}
