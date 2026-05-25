package com.saarthi.feature.assistant.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.PackId
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.InferenceService
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.PackType
import com.saarthi.feature.assistant.data.RagDocumentRepository
import com.saarthi.feature.assistant.data.RetrievedChunk
import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Self-contained Q&A engine for a knowledge pack (Kisan today).
 *
 * Deliberately INDEPENDENT of the main chat:
 *   • Does NOT use ChatRepository, chat sessions, or the persisted
 *     conversation history — its message list lives only here, in memory.
 *   • Does NOT read or write the global Personality preference — so
 *     opening a pack chat can never bleed a persona / context into the
 *     user's normal chat.
 *   • Reuses ONLY the shared low-level [InferenceEngine] (the model
 *     itself) and [RagDocumentRepository] BM25 search scoped to the
 *     pack's own sentinel sessionId.
 *
 * The pack chat and the main chat never run at the same time (one
 * screen on top at a time) and the engine recycles its conversation
 * after every turn, so sharing the engine is safe — no KV-cache bleed.
 *
 * Generalisable: the only pack-specific bits are [packSessionId] and
 * the system-prompt preamble. A future MoneyPackChatViewModel etc.
 * would differ only in those two constants.
 */
@HiltViewModel
class PackChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceEngine: InferenceEngine,
    private val ragRepository: RagDocumentRepository,
) : ViewModel() {

    private val packSessionId = PackId.KISAN.sessionId

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    fun ask(rawQuestion: String) {
        val question = rawQuestion.trim()
        if (question.isEmpty() || _isGenerating.value) return

        val userMsg = ChatMessage(content = question, role = MessageRole.USER)
        val streamingId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(id = streamingId, content = "", role = MessageRole.ASSISTANT, isStreaming = true)
        _messages.update { it + userMsg + placeholder }
        _isGenerating.value = true

        viewModelScope.launch {
            if (!inferenceEngine.isReady) {
                finish(streamingId, "Please download and load a model first from Settings → Models, then come back to ask about farming.")
                return@launch
            }

            // BM25 over the pack's own chunks ONLY — never the user's
            // chat documents. (search reads ragChunkDao.getBySession.)
            val chunks = runCatching { ragRepository.search(packSessionId, question, topK = 5) }
                .getOrDefault(emptyList())
            if (chunks.isEmpty()) {
                finish(streamingId, "The Kisan knowledge pack isn't installed yet. Reopen Saarthi to let it install, then try again.")
                return@launch
            }

            val prompt = buildPackPrompt(question, chunks)
            DebugLogger.log("PACK", "Kisan pack Q&A — chunks=${chunks.size} promptChars=${prompt.length}")

            // FGS so Samsung doesn't kill the process mid-generation.
            // The engine's onDone stops it; we also stop defensively in
            // catch/onCompletion if the native thread already finished.
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

    fun clear() {
        _messages.update { emptyList() }
    }

    // ── Internal ─────────────────────────────────────────────────────

    private fun updateStreaming(id: String, text: String) {
        _messages.update { list ->
            list.map { if (it.id == id) it.copy(content = text, isStreaming = true) else it }
        }
    }

    private fun finish(id: String, finalText: String) {
        _messages.update { list ->
            list.map { if (it.id == id) it.copy(content = finalText, isStreaming = false) else it }
        }
        _isGenerating.value = false
    }

    /**
     * Pack-only prompt. Self-contained — does not touch SystemPromptProvider
     * (which is persona / session aware). The whole instruction set lives
     * here so the pack experience is fully decoupled.
     *
     * Chunk budget: cap the source block at ~2,800 chars so the prompt
     * stays inside Gemma 3n / Gemma 4's input budget. Chunks are added
     * whole until the cap; partial chunks are never emitted.
     */
    private fun buildPackPrompt(question: String, chunks: List<RetrievedChunk>): String {
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

        return buildString {
            append("You are Saarthi's Kisan Saathi — a practical farming advisor for Indian farmers. ")
            append("Answer the question using ONLY the FARM KNOWLEDGE SOURCES below. ")
            append("Cite the source number inline as [N] for every fact. ")
            append("Quote scheme names, MSP figures, dose rates and dates exactly as written. ")
            append("If the sources don't cover the question, reply exactly: ")
            append("\"I don't have that in my farming knowledge yet — please check your local KVK or block agriculture office.\" ")
            append("Keep the answer short and practical, the way you'd explain to a farmer on a phone in the field. ")
            append("Do not repeat these instructions.\n\n")
            append("=== FARM KNOWLEDGE SOURCES ===\n")
            append(sources)
            append("\n=== END SOURCES ===\n\n")
            append("Question: ")
            append(question)
        }
    }
}
