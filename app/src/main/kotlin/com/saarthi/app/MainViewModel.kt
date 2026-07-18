package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.DeviceProfiler
import com.saarthi.core.inference.ModelCatalog
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.InferenceConfig
import com.saarthi.core.inference.model.PromptTier
import com.saarthi.feature.onboarding.domain.OnboardingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AppStartState {
    object Loading : AppStartState()
    object GoToOnboarding : AppStartState()
    object GoToHome : AppStartState()
    data class ModelError(val message: String) : AppStartState()
}

/**
 * Pronoun / copula / label tokens that must never be shown as the greeting
 * name — they slip into model-stored name values from self-intros in ANY
 * language ("mae arjun", "mera naam Arjun hai", "उपयोगकर्ता का नाम अर्जुन").
 * Covers Latin/romanised + the native scripts of all supported languages, so
 * the greeting stays accurate regardless of which language captured the name.
 * Lowercased for comparison (a no-op for Indic scripts, which have no case).
 */
private val NAME_FILLERS = setOf(
    // English + romanised Hindi/Marathi
    "mae", "main", "mai", "mera", "meri", "mere", "mujhe", "naam", "nam",
    "naav", "nav", "majhe", "majha", "aahe", "ahe",
    "hoon", "hu", "hun", "hain", "hai", "my", "name", "is", "am", "the",
    "call", "me", "myself", "this", "user", "users",
    // Devanagari (Hindi/Marathi)
    "उपयोगकर्ता", "यूज़र", "नाम", "मेरा", "मेरी", "मेरे", "मुझे", "है", "हैं", "हूँ", "हूं",
    "का", "की", "के", "मैं", "नाव", "माझे", "माझं", "आहे", "मी",
    // Telugu / Tamil / Bengali / Kannada / Gujarati / Punjabi / Odia
    "పేరు", "నా", "பெயர்", "என்", "எனது", "নাম", "আমার",
    "ಹೆಸರು", "ನನ್ನ", "નામ", "મારું", "મારુ", "ਨਾਮ", "ਮੇਰਾ", "ନାମ", "ମୋର", "ମୋ",
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val onboardingRepository: OnboardingRepository,
    private val inferenceEngine: InferenceEngine,
    private val modelCatalog: ModelCatalog,
    private val languageManager: LanguageManager,
    private val deviceProfiler: DeviceProfiler,
    private val memoryRepository: com.saarthi.core.memory.domain.MemoryRepository,
) : ViewModel() {

    private val _startState = MutableStateFlow<AppStartState>(AppStartState.Loading)
    val startState: StateFlow<AppStartState> = _startState.asStateFlow()

    val currentLanguage: StateFlow<SupportedLanguage> = languageManager.selectedLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, SupportedLanguage.HINDI)

    /**
     * The user's name from the cross-chat USER_SCOPE profile memory (learned in
     * conversation — see ChatRepositoryImpl name extraction / [SAARTHI_MEMORY]).
     * Drives the personalized home greeting. Null until the user shares a name.
     */
    val userName: StateFlow<String?> = memoryRepository
        .observeBySession(com.saarthi.core.memory.domain.MemoryRepository.USER_SCOPE)
        .map { entries ->
            // Resolve the greeting name robustly. A model [SAARTHI_MEMORY] marker
            // sometimes persists a garbled name like "Arjun.mae" (the user typed
            // Hinglish "mae arjun" = "I am Arjun" and the model kept the pronoun),
            // or a 2-char Devanagari truncation "अर" for "अर्जुन". So for each
            // name-stem key: split on ANY non-letter (space, dot, comma…), drop
            // pronoun/copula filler tokens (mae/main/hai/naam…), take the first
            // real name token (≥3 letters), and prefer the MOST COMPLETE one.
            val resolved = entries
                .filter { com.saarthi.core.memory.domain.MemoryRepository.isNameKey(it.key) }
                .mapNotNull { e ->
                    e.value.trim()
                        // Split on non-letters BUT keep combining marks (\p{M})
                        // AND zero-width joiner/non-joiner (U+200D/U+200C):
                        // Devanagari halant/matras (अर्जुन's ् and ु) are Mn,
                        // not L — splitting on [^\p{L}] shredded "अर्जुन" into
                        // "अर/ज/न" fragments and the greeting showed no name
                        // even though the fact was stored (field log:
                        // write value="अर्जुन" → resolved=(none)). ZWJ/ZWNJ are
                        // Cf (format), not L or M, but several Indic-script
                        // keyboards (Devanagari, Bengali, Malayalam conjuncts)
                        // insert them mid-word, which would still fracture the
                        // name without this — keep them attached too.
                        .split(Regex("[^\\p{L}\\p{M}\\u200C\\u200D]+"))
                        .firstOrNull { it.length >= 3 && it.lowercase() !in NAME_FILLERS }
                        ?.replaceFirstChar { c -> c.uppercase() }
                }
                .maxByOrNull { it.length }
            // File-visible so a "greeting lost the name" report is diagnosable:
            // shows what the resolver saw and what it picked, alongside the
            // [MEMORY] write trail.
            com.saarthi.core.inference.DebugLogger.log(
                "MEMORY",
                "greeting name resolved=${resolved ?: "(none)"} from ${entries.size} USER-scope fact(s)",
            )
            resolved
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

            // Pass maxTokens=0 so LiteRTInferenceEngine picks the tier-aware default
            // based on promptTier/model size (Gemma 4 → 2048, mid → 1024,
            // 1B / Compact → 512). See LiteRTInferenceEngine.effectiveMaxTokens.
            // temperature/topK/promptTier come from the catalog entry itself
            // (data-driven — see ModelEntry) rather than the engine re-deriving
            // them by matching the model's name/path; the InferenceConfig
            // defaults only apply when catalogEntry is null (a model that
            // doesn't match any current catalog entry).
            val profile = deviceProfiler.profile()
            val config = InferenceConfig(
                modelPath  = modelPath,
                modelName  = catalogEntry?.displayName,
                maxTokens  = 0,
                nThreads   = profile.recommendedThreads,
                temperature = catalogEntry?.defaultTemperature ?: 0.8f,
                topK       = catalogEntry?.topK ?: 40,
                promptTier = catalogEntry?.promptTier ?: PromptTier.STANDARD,
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
