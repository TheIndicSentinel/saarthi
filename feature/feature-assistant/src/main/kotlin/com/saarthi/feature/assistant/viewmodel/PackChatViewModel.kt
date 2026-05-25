package com.saarthi.feature.assistant.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.PackId
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.InferenceService
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.PackType
import com.saarthi.core.memory.db.ConversationDao
import com.saarthi.core.memory.db.ConversationEntity
import com.saarthi.feature.assistant.data.RagDocumentRepository
import com.saarthi.feature.assistant.data.RetrievedChunk
import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * Self-contained Q&A engine for a knowledge pack (Kisan today).
 *
 * Deliberately INDEPENDENT of the main chat:
 *   • Reuses ONLY the shared low-level [InferenceEngine] (the model) and
 *     [RagDocumentRepository] BM25 scoped to the pack's sentinel
 *     sessionId.
 *   • Reads / writes its OWN persisted conversation under a DEDICATED
 *     sessionId ([PACK_CHAT_SESSION]) that is never a chat-session row —
 *     so it survives navigation + app restart, is manageable (clear),
 *     and yet never appears in the main chat's session list nor bleeds
 *     persona / context into normal chats.
 *   • Honours the user's selected language: the pack's curated content
 *     stays English, but the model is instructed to ANSWER in the
 *     selected language (standard cross-lingual RAG — Gemma reads the
 *     English source and replies in Hindi / Tamil / etc.).
 *
 * Generalisable: only [packSessionId], [PACK_CHAT_SESSION] and the
 * prompt preamble are pack-specific.
 */
@HiltViewModel
class PackChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceEngine: InferenceEngine,
    private val ragRepository: RagDocumentRepository,
    private val conversationDao: ConversationDao,
    private val languageManager: LanguageManager,
) : ViewModel() {

    private val packSessionId = PackId.KISAN.sessionId            // RAG chunks
    private val chatSessionId = PACK_CHAT_SESSION                 // persisted messages

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /** Selected language — the screen uses it for the localized input hint. */
    val language: StateFlow<SupportedLanguage> = languageManager.selectedLanguage

    init {
        // Restore the persisted pack conversation so "go back and return"
        // shows the prior chat (the gap the user reported).
        viewModelScope.launch {
            val saved = runCatching { conversationDao.getBySession(chatSessionId) }.getOrDefault(emptyList())
            if (saved.isNotEmpty()) {
                _messages.value = saved.map { it.toChatMessage() }
            }
        }
    }

    fun ask(rawQuestion: String) {
        val question = rawQuestion.trim()
        if (question.isEmpty() || _isGenerating.value) return

        val userMsg = ChatMessage(content = question, role = MessageRole.USER)
        val streamingId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(id = streamingId, content = "", role = MessageRole.ASSISTANT, isStreaming = true)
        _messages.update { it + userMsg + placeholder }
        _isGenerating.value = true

        // Persist the user turn immediately so it survives even if the
        // app is killed mid-generation.
        viewModelScope.launch {
            withContext(NonCancellable) {
                runCatching { conversationDao.insert(userMsg.toEntity(chatSessionId)) }
            }
        }

        viewModelScope.launch {
            if (!inferenceEngine.isReady) {
                finish(streamingId, "Please download and load a model first from Settings → Models, then come back to ask about farming.")
                return@launch
            }

            val chunks = runCatching { ragRepository.search(packSessionId, question, topK = 5) }
                .getOrDefault(emptyList())
            if (chunks.isEmpty()) {
                finish(streamingId, "The Kisan knowledge pack isn't installed yet. Reopen Saarthi to let it install, then try again.")
                return@launch
            }

            val prompt = buildPackPrompt(question, chunks, languageManager.selectedLanguage.value)
            DebugLogger.log("PACK", "Kisan Q&A — chunks=${chunks.size} lang=${languageManager.selectedLanguage.value.code} promptChars=${prompt.length}")

            InferenceService.startGenerating(context)
            val acc = StringBuilder()
            inferenceEngine.generateStream(prompt, PackType.BASE)
                .catch { e ->
                    if (!inferenceEngine.isNativeGenerating) InferenceService.stop(context)
                    finish(streamingId, e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again.")
                }
                .onEach { token ->
                    acc.append(token)
                    updateStreaming(streamingId, acc.toString())
                }
                .onCompletion { throwable ->
                    if (!inferenceEngine.isNativeGenerating) InferenceService.stop(context)
                    if (throwable == null) finish(streamingId, acc.toString().trim())
                }
                .collect {}
        }
    }

    /** Wipe the pack conversation — the "manage / start fresh" action. */
    fun clear() {
        _messages.update { emptyList() }
        viewModelScope.launch {
            withContext(NonCancellable) {
                runCatching { conversationDao.deleteBySession(chatSessionId) }
            }
        }
    }

    // ── Internal ─────────────────────────────────────────────────────

    private fun updateStreaming(id: String, text: String) {
        _messages.update { list ->
            list.map { if (it.id == id) it.copy(content = text, isStreaming = true) else it }
        }
    }

    private fun finish(id: String, finalText: String) {
        val finalMsg = _messages.value.firstOrNull { it.id == id }
            ?.copy(content = finalText, isStreaming = false)
        _messages.update { list -> list.map { if (it.id == id) (finalMsg ?: it) else it } }
        _isGenerating.value = false
        // Persist the completed assistant turn so it reloads on return.
        if (finalMsg != null) {
            viewModelScope.launch {
                withContext(NonCancellable) {
                    runCatching { conversationDao.insert(finalMsg.toEntity(chatSessionId)) }
                }
            }
        }
    }

    private fun buildPackPrompt(
        question: String,
        chunks: List<RetrievedChunk>,
        lang: SupportedLanguage,
    ): String {
        val maxSourceChars = 2_800
        val sources = buildString {
            var used = 0
            for ((i, c) in chunks.withIndex()) {
                val body = c.text.trim()
                val block = "[${i + 1}] ${c.docName}\n$body\n\n"
                if (used + block.length > maxSourceChars) break
                append(block)
                used += block.length
            }
        }.trim()

        // Language directive — same mechanism the main chat uses. Sources
        // remain in English (the curated pack), but the model answers in
        // the user's selected language.
        val langLine = lang.systemPromptInstruction

        return buildString {
            if (langLine.isNotBlank()) { append(langLine); append("\n\n") }
            append("You are Saarthi's Kisan Saathi — a practical farming advisor for Indian farmers. ")
            append("Answer the question using ONLY the FARM KNOWLEDGE SOURCES below. ")
            append("Cite the source number inline as [N] for every fact. ")
            append("Quote scheme names, MSP figures, dose rates and dates exactly as written in the sources. ")
            append("If the sources don't cover the question, reply that you don't have it in your farming knowledge yet and suggest the local KVK or block agriculture office. ")
            append("Keep the answer short and practical, the way you'd explain to a farmer on a phone in the field. ")
            append("Do not repeat these instructions.\n\n")
            append("=== FARM KNOWLEDGE SOURCES (in English) ===\n")
            append(sources)
            append("\n=== END SOURCES ===\n\n")
            append("Question: ")
            append(question)
            if (langLine.isNotBlank()) { append("\n\n"); append(langLine) }
        }
    }

    private fun ChatMessage.toEntity(sessionId: String) = ConversationEntity(
        id = id,
        content = content,
        role = role.name,
        timestamp = timestamp,
        tokenCount = tokenCount,
        sessionId = sessionId,
    )

    private fun ConversationEntity.toChatMessage() = ChatMessage(
        id = id,
        content = content,
        role = if (role == MessageRole.USER.name) MessageRole.USER else MessageRole.ASSISTANT,
        isStreaming = false,
        tokenCount = tokenCount,
        timestamp = timestamp,
    )

    companion object {
        /**
         * Dedicated conversation sessionId for the Kisan pack chat.
         * Distinct from the RAG chunk session (`global_pack_kisan`) and
         * from every main-chat session — and we never create a
         * ChatSessionEntity for it, so it never appears in the main
         * chat's history list.
         */
        private const val PACK_CHAT_SESSION = "pack_chat_kisan"
    }
}
