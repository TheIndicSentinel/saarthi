package com.saarthi.feature.assistant.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.feature.assistant.data.FileContentExtractor
import com.saarthi.feature.assistant.domain.AttachedFile
import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.ChatRepository
import com.saarthi.feature.assistant.domain.ChatSession
import com.saarthi.core.memory.domain.MemoryRepository
import com.saarthi.core.memory.domain.MemoryEntry
import kotlinx.coroutines.flow.Flow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AssistantUiState(
    val inputText: String = "",
    val pendingAttachments: List<AttachedFile> = emptyList(),
    val isStreaming: Boolean = false,
    val isListening: Boolean = false,
    val tokensPerSecond: Float = 0f,
    val modelReady: Boolean = false,
    val activeModelName: String? = null,
    val error: String? = null,
    val showAttachmentSheet: Boolean = false,
    val showClearDialog: Boolean = false,
    val showDrawer: Boolean = false,
    val isSearchMode: Boolean = false,
    val searchQuery: String = "",
)

@HiltViewModel
class AssistantViewModel @Inject constructor(
    application: Application,
    private val chatRepository: ChatRepository,
    private val inferenceEngine: InferenceEngine,
    private val fileExtractor: FileContentExtractor,
    private val languageManager: LanguageManager,
    private val memoryRepository: MemoryRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AssistantUiState(modelReady = inferenceEngine.isReady))
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val allMessages: StateFlow<List<ChatMessage>> = chatRepository.getHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messages: StateFlow<List<ChatMessage>> = combine(
        allMessages,
        _uiState.map { it.searchQuery }.distinctUntilChanged()
    ) { msgs, query ->
        if (query.isBlank()) msgs
        else msgs.filter { it.content.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sessions: StateFlow<List<ChatSession>> = chatRepository.getSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentSessionId: StateFlow<String> = chatRepository.getCurrentSessionId()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "default")

    val currentLanguage: StateFlow<SupportedLanguage> = languageManager.selectedLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, languageManager.selectedLanguage.value)

    val allMemories: Flow<List<MemoryEntry>> = memoryRepository.observeAll()

    private var speechRecognizer: SpeechRecognizer? = null

    init {
        chatRepository.getTokensPerSecond()
            .onEach { tps -> _uiState.update { it.copy(tokensPerSecond = tps) } }
            .launchIn(viewModelScope)

        // Push-based isReady observation via StateFlow — no polling, no wakeup cost.
        inferenceEngine.isReadyFlow
            .onEach { ready -> _uiState.update { it.copy(modelReady = ready) } }
            .launchIn(viewModelScope)

        // Observe active model name changes
        inferenceEngine.activeModelNameFlow
            .onEach { name -> _uiState.update { it.copy(activeModelName = name) } }
            .launchIn(viewModelScope)
    }

    // ── Input ─────────────────────────────────────────────────────────────────
    fun onInputChange(text: String) = _uiState.update { it.copy(inputText = text) }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val attachments = _uiState.value.pendingAttachments
        if ((text.isBlank() && attachments.isEmpty()) || _uiState.value.isStreaming) return

        _uiState.update { it.copy(inputText = "", pendingAttachments = emptyList(), isStreaming = true, error = null) }

        chatRepository.streamResponse(text, attachments)
            .launchIn(viewModelScope)
            .invokeOnCompletion { throwable ->
                _uiState.update { it.copy(isStreaming = false, error = throwable?.message) }
            }
    }

    // ── Attachments ───────────────────────────────────────────────────────────
    fun onAttachmentsPicked(uris: List<Uri>) {
        viewModelScope.launch {
            val files = uris.mapNotNull { uri ->
                runCatching { fileExtractor.extract(uri) }.getOrNull()
            }
            _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + files) }
        }
    }

    fun removeAttachment(file: AttachedFile) =
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments - file) }

    fun toggleAttachmentSheet() =
        _uiState.update { it.copy(showAttachmentSheet = !it.showAttachmentSheet) }

    // ── Voice input ───────────────────────────────────────────────────────────
    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(getApplication())) {
            _uiState.update { it.copy(error = "Voice recognition not available on this device") }
            return
        }
        // Destroy any existing recognizer before creating a new one to prevent leaking
        // the old instance if startListening() is called while already listening.
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(getApplication()).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull() ?: return
                    _uiState.update { it.copy(inputText = spoken, isListening = false) }
                }
                override fun onError(error: Int) =
                    _uiState.update { it.copy(isListening = false, error = "Voice error: $error") }
                override fun onEndOfSpeech() = _uiState.update { it.copy(isListening = false) }
                override fun onReadyForSpeech(p: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(v: Float) = Unit
                override fun onBufferReceived(b: ByteArray?) = Unit
                override fun onPartialResults(p: Bundle?) = Unit
                override fun onEvent(e: Int, b: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            startListening(intent)
        }
        _uiState.update { it.copy(isListening = true) }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _uiState.update { it.copy(isListening = false) }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    fun toggleSearch() = _uiState.update { 
        it.copy(isSearchMode = !it.isSearchMode, searchQuery = if (it.isSearchMode) "" else it.searchQuery)
    }
    fun onSearchQueryChange(query: String) = _uiState.update { it.copy(searchQuery = query) }

    // ── Conversation actions ──────────────────────────────────────────────────
    fun showClearDialog() = _uiState.update { it.copy(showClearDialog = true) }
    fun dismissClearDialog() = _uiState.update { it.copy(showClearDialog = false) }
    fun clearChat() = viewModelScope.launch {
        chatRepository.clearHistory()
        _uiState.update { it.copy(showClearDialog = false) }
    }

    fun deleteMessage(id: String) = viewModelScope.launch { chatRepository.deleteMessage(id) }

    // ── Session management ────────────────────────────────────────────────────
    fun openDrawer() = _uiState.update { it.copy(showDrawer = true) }
    fun closeDrawer() = _uiState.update { it.copy(showDrawer = false) }

    fun newChat() = viewModelScope.launch {
        chatRepository.createSession()
        _uiState.update { it.copy(showDrawer = false) }
    }

    fun switchSession(sessionId: String) = viewModelScope.launch {
        chatRepository.switchSession(sessionId)
        _uiState.update { it.copy(showDrawer = false) }
    }

    fun deleteSession(sessionId: String) = viewModelScope.launch {
        chatRepository.deleteSession(sessionId)
    }

    fun deleteMemory(key: String) = viewModelScope.launch {
        memoryRepository.delete(key)
    }

    override fun onCleared() {
        speechRecognizer?.destroy()
        super.onCleared()
    }
}
