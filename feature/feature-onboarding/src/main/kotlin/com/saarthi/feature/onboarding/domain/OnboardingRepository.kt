package com.saarthi.feature.onboarding.domain

import com.saarthi.core.i18n.SupportedLanguage
import kotlinx.coroutines.flow.Flow

interface OnboardingRepository {
    fun isOnboardingComplete(): Flow<Boolean>
    suspend fun completeOnboarding(selectedLanguage: SupportedLanguage)
    fun getModelPath(): String?
}
