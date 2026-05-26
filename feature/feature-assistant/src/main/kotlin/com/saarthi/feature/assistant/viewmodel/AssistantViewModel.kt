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
    /** Voice overlay stays open even after listening ends, so the user can
     *  review the captured text and tap Send. */
    val showVoiceMode: Boolean = false,
    val tokensPerSecond: Float = 0f,
    val modelReady: Boolean = false,
    val activeModelName: String? = null,
    /**
     * False when the active model is the COMPACT tier (Gemma 3 1B etc.) —
     * the 512-tok budget can't carry a system prompt + RAG chunks + user
     * message without the answer becoming "I'm not sure about that".
     * The UI uses this to gate the attach-file button + show a hint that
     * the user should switch to Gemma 4 to use attachments.
     */
    val attachmentsEnabled: Boolean = true,
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
    private val ttsManager: com.saarthi.feature.assistant.data.TtsManager,
    private val ttsPreference: com.saarthi.core.i18n.TtsPreference,
    private val personalityPreference: com.saarthi.core.i18n.PersonalityPreference,
) : AndroidViewModel(application) {

    val isSpeaking: StateFlow<Boolean> = ttsManager.isSpeaking

    /** Which assistant message is currently being read aloud, if any.
     *  The bubble uses this to flip its Listen chip to Stop. */
    private val _speakingMessageId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    // NOTE: TTS observation hooks live in the main `init {}` block at the
    // bottom of the constructor — running them here would NPE because
    // [allMessages] / [currentLanguage] are still null at this point in
    // Kotlin's property-initializer order.

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

        // Observe active model name changes — and derive the attachment
        // gate from it. COMPACT tier (1B-class models) can't reliably
        // handle RAG context; the chat repository's prompt budget would
        // crowd the chunks out anyway, so we disable the attach button.
        inferenceEngine.activeModelNameFlow
            .onEach { name ->
                _uiState.update {
                    it.copy(
                        activeModelName = name,
                        attachmentsEnabled = !isCompactModel(name),
                    )
                }
            }
            .launchIn(viewModelScope)

        // TTS — clear the highlighted message-id as soon as TTS reports it's
        // no longer speaking (natural end, error, or stop()).
        ttsManager.isSpeaking
            .onEach { speaking -> if (!speaking) _speakingMessageId.value = null }
            .launchIn(viewModelScope)

        // TTS — when "Read replies aloud" is on, auto-speak each assistant
        // reply once it finishes streaming. Fires on the *transition* from
        // streaming → done for the latest message. Lives here (not in an
        // earlier init block) because [allMessages] / [currentLanguage] are
        // initialised above; reading them from an earlier init block NPEs.
        // Only auto-speak replies that were actually GENERATED live on this
        // screen — never history loaded when a chat is opened/switched.
        // We mark an id as "live" the moment we see it streaming, and only
        // speak it (once) on its streaming→done transition. This fixes the
        // bug where opening any chat read its last reply aloud repeatedly.
        val streamedLive = mutableSetOf<String>()
        val autoSpoken = mutableSetOf<String>()
        allMessages
            .onEach { msgs ->
                if (!ttsPreference.autoSpeakReplies.value) return@onEach
                msgs.forEach { m ->
                    if (m.role == com.saarthi.feature.assistant.domain.MessageRole.ASSISTANT && m.isStreaming) {
                        streamedLive.add(m.id)
                    }
                }
                val last = msgs.lastOrNull { it.role == com.saarthi.feature.assistant.domain.MessageRole.ASSISTANT }
                    ?: return@onEach
                if (last.isStreaming || last.content.isBlank()) return@onEach
                if (last.id !in streamedLive) return@onEach   // loaded history, not live → don't read
                if (last.id in autoSpoken) return@onEach
                autoSpoken.add(last.id)
                _speakingMessageId.value = last.id
                ttsManager.speak(
                    last.content,
                    currentLanguage.value,
                    personalityPreference.selected.value.voiceHint,
                )
            }
            .launchIn(viewModelScope)
    }

    // ── Input ─────────────────────────────────────────────────────────────────
    fun onInputChange(text: String) = _uiState.update { it.copy(inputText = text) }

    /** Stored so [stopGeneration] can cancel an in-flight generation. */
    private var streamJob: kotlinx.coroutines.Job? = null

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val attachments = _uiState.value.pendingAttachments
        if ((text.isBlank() && attachments.isEmpty()) || _uiState.value.isStreaming) return

        _uiState.update { it.copy(inputText = "", pendingAttachments = emptyList(), isStreaming = true, error = null) }

        streamJob = chatRepository.streamResponse(text, attachments)
            .launchIn(viewModelScope)
            .also { job ->
                job.invokeOnCompletion { throwable ->
                    // A user-initiated Stop cancels the coroutine → don't
                    // surface that as an error. Only genuine failures get a
                    // short, professional message (never a raw stack message).
                    val friendly = when {
                        throwable == null -> null
                        throwable is kotlinx.coroutines.CancellationException -> null
                        else -> "Couldn't finish that response. Please try again."
                    }
                    _uiState.update { it.copy(isStreaming = false, error = friendly) }
                }
            }
    }

    /**
     * User pressed the Stop button in the chat composer. Two-stage cancel:
     *   1. Tell the native engine to halt the in-flight model run via
     *      [InferenceEngine.cancelGeneration] — without this, the model keeps
     *      burning CPU/GPU even though we've stopped collecting tokens.
     *   2. Cancel the coroutine collecting the stream so any pending state
     *      updates are dropped and `isStreaming` flips false immediately.
     */
    fun stopGeneration() {
        if (!_uiState.value.isStreaming) return
        runCatching { inferenceEngine.cancelGeneration() }
        streamJob?.cancel()
        // Defensive: invokeOnCompletion handler will clear isStreaming, but
        // some races (cancel after job already completed) need a fallback.
        _uiState.update { it.copy(isStreaming = false) }
    }

    /** User pressed the Listen chip on an AI bubble. Toggles: speak if not
     *  speaking that message, stop if already speaking it. */
    fun toggleSpeak(messageId: String, text: String) {
        if (_speakingMessageId.value == messageId) {
            ttsManager.stop()
            return
        }
        _speakingMessageId.value = messageId
        ttsManager.speak(text, currentLanguage.value, personalityPreference.selected.value.voiceHint)
    }

    fun stopSpeaking() = ttsManager.stop()

    /**
     * Retry an assistant message: delete the AI response + the user message
     * that triggered it, then resend the user text fresh so the model produces
     * a new reply.
     */
    fun retryResponse(messageId: String) {
        if (_uiState.value.isStreaming) return
        viewModelScope.launch {
            val msgs = allMessages.value
            val idx = msgs.indexOfFirst { it.id == messageId }
            if (idx <= 0) return@launch
            val previousUserMsg = msgs.subList(0, idx).lastOrNull { it.role == com.saarthi.feature.assistant.domain.MessageRole.USER }
                ?: return@launch
            chatRepository.deleteMessage(messageId)
            chatRepository.deleteMessage(previousUserMsg.id)
            _uiState.update { it.copy(inputText = previousUserMsg.content) }
            sendMessage()
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

    /** Open the full-screen voice overlay AND start listening. */
    fun openVoiceMode() {
        _uiState.update { it.copy(showVoiceMode = true, inputText = "") }
        startListening()
    }

    /** Close the voice overlay (and stop listening if still active). */
    fun closeVoiceMode(clearText: Boolean = false) {
        speechRecognizer?.cancel()
        _uiState.update {
            it.copy(
                showVoiceMode = false,
                isListening = false,
                inputText = if (clearText) "" else it.inputText,
            )
        }
    }

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
                    // Auto-send + close the voice overlay if it was the trigger.
                    if (_uiState.value.showVoiceMode && spoken.isNotBlank()) {
                        _uiState.update { it.copy(showVoiceMode = false) }
                        sendMessage()
                    }
                }
                override fun onError(error: Int) =
                    _uiState.update { it.copy(isListening = false, error = "Voice error: $error") }
                override fun onEndOfSpeech() = _uiState.update { it.copy(isListening = false) }
                override fun onReadyForSpeech(p: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(v: Float) = Unit
                override fun onBufferReceived(b: ByteArray?) = Unit
                override fun onPartialResults(p: Bundle?) {
                    val matches = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull().orEmpty()
                    if (partial.isNotBlank()) {
                        _uiState.update { it.copy(inputText = partial) }
                    }
                }
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

    fun deleteMemory(sessionId: String, key: String) = viewModelScope.launch {
        // (sessionId, key) is the composite primary key in v1.0.24 — the same
        // key can exist in multiple chats independently, so the delete must
        // be session-scoped.
        //
        // Defensive: confirm the memory actually lives under the sessionId the
        // caller claims. If the Knowledge panel ever started reusing
        // `currentSessionId` by mistake, the existence check would catch it
        // and log a clear diagnostic instead of silently deleting nothing.
        val exists = runCatching { memoryRepository.get(sessionId, key) }.getOrNull()
        if (exists == null) {
            com.saarthi.core.inference.DebugLogger.log(
                "MEMORY",
                "deleteMemory: no entry for sessionId=$sessionId key=$key — caller likely passed the wrong session",
            )
            return@launch
        }
        memoryRepository.delete(sessionId, key)
    }

    override fun onCleared() {
        speechRecognizer?.destroy()
        ttsManager.stop()
        super.onCleared()
    }

    /**
     * COMPACT-tier detector — mirrors [SystemPromptProvider.tierFor] for
     * the only branch the UI needs to gate on. Kept inline to avoid
     * adding SystemPromptProvider to the constructor for one boolean.
     */
    private fun isCompactModel(name: String?): Boolean {
        val n = (name ?: "").lowercase()
        return n.contains("1b") || n.contains("compact")
    }
}
