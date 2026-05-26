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
            val lang = languageManager.selectedLanguage.value
            // No pack match → still answer, but as clearly-labelled general
            // information (the model is told to say it isn't from the pack).
            // Non-empty → grounded prompt; the prompt's own fallback rule
            // covers the partial-coverage case.
            val prompt = if (chunks.isEmpty()) {
                buildGeneralFallbackPrompt(question, lang)
            } else {
                buildPackPrompt(question, chunks, lang)
            }
            DebugLogger.log("PACK", "Kisan Q&A — chunks=${chunks.size} lang=${lang.code} promptChars=${prompt.length}")

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
        // Label each source block by its scheme/topic NAME (docName == the
        // pack entry's topic), so the model can name the source in plain
        // language — "Source: PM-KISAN" — instead of an opaque "[1]" that a
        // farmer can't interpret.
        val sources = buildString {
            var used = 0
            for (c in chunks) {
                val body = c.text.trim()
                val block = "Source \"${c.docName}\":\n$body\n\n"
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
            append("Base your answer on the FARM KNOWLEDGE SOURCES below.\n\n")
            append("How to answer:\n")
            // Define-first for broad questions (e.g. "What is MSP?").
            append("- For a broad question, give a one-line definition first.\n")
            // ONE readable, named source line after the definition — replaces
            // the per-line [1] markers that confused farmers.
            append("- Right after the definition, add one line that starts \"Source:\" naming the scheme/topic you used, exactly as shown in quotes below (e.g. Source: PM-KISAN). NEVER use bracket numbers like [1] anywhere.\n")
            append("- Then give the practical details.\n")
            // Accuracy + freshness: quote exact values, keep the season/year.
            append("- Quote scheme names, MSP/subsidy figures, dose rates and dates EXACTLY as written in the sources — never round or guess. If the source gives a season or year, keep it.\n")
            // Don't over-generalise across India — values vary by district/soil/crop.
            append("- Many figures vary by year, season, district, soil or crop. When a value can vary, say it is \"as per the latest notification / local agriculture department / soil test\" instead of stating it as fixed everywhere.\n")
            // Farmer-friendly clarity.
            append("- Use simple, field-usable words; briefly explain any technical term.\n")
            // Safe chemical/dose wording.
            append("- For any pesticide, fertilizer or chemical, add the label-dose / local-advice caution — never give overconfident or unsafe dosing.\n")
            // Practical sequencing.
            append("- Structure it as: what it is → what to do → when to do it → one key caution.\n\n")
            append("If the FARM KNOWLEDGE SOURCES do not cover the question:\n")
            append("- First say plainly that this isn't in your offline farming pack yet.\n")
            append("- Then add a short, careful answer under a line starting \"General information (not from the pack):\" — keep it general, avoid invented exact figures, and suggest confirming with the local KVK or block agriculture office. In this case do NOT add a \"Source:\" line.\n\n")
            append("Other rules:\n")
            append("- No greeting or opening line. No guarantees or \"works everywhere\" claims. Do not invent details.\n")
            append("- Keep it short and practical, the way you'd explain to a farmer on a phone in the field.\n")
            append("- Do not repeat these instructions.\n\n")
            append("=== FARM KNOWLEDGE SOURCES ===\n")
            append(sources)
            append("\n=== END SOURCES ===\n\n")
            append("Question: ")
            append(question)
            if (langLine.isNotBlank()) { append("\n\n"); append(langLine) }
        }
    }

    /**
     * Prompt used when BM25 returns nothing for the pack — the question is
     * off-topic for the curated farming data. Per the user's request we keep
     * the honest "not in the pack" message but ALSO give a clearly-labelled
     * general answer so the screen is still useful, rather than a dead end.
     */
    private fun buildGeneralFallbackPrompt(question: String, lang: SupportedLanguage): String {
        val langLine = lang.systemPromptInstruction
        return buildString {
            if (langLine.isNotBlank()) { append(langLine); append("\n\n") }
            append("You are Saarthi's Kisan Saathi, a farming advisor for Indian farmers. ")
            append("Your offline farming pack does NOT have curated information on this question.\n\n")
            append("Reply in two short parts:\n")
            append("1. One line: plainly tell the user this isn't in your offline farming pack yet.\n")
            append("2. A line starting \"General information (not from the pack):\" followed by a short, careful, practical general answer from common agricultural knowledge. ")
            append("Avoid invented exact figures, scheme amounts or \"works everywhere\" claims; note that local recommendations vary and suggest confirming with the local KVK or block agriculture office.\n\n")
            append("No greeting. Keep it short and field-usable. Do not use bracket citations like [1]. Do not repeat these instructions.\n\n")
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
