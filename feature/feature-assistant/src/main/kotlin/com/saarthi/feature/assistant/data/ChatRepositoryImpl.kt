package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.PackType
import com.saarthi.core.memory.db.ConversationDao
import com.saarthi.core.memory.db.ConversationEntity
import com.saarthi.core.memory.domain.MemoryRepository
import com.saarthi.feature.assistant.domain.AttachedFile
import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.ChatRepository
import com.saarthi.feature.assistant.domain.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_HISTORY_TURNS = 6     // 3 user + 3 assistant = 6 messages in context
private const val MAX_PROMPT_CHARS = 3_200  // ~800 tokens — leaves room for response

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val inferenceEngine: InferenceEngine,
    private val memoryRepository: MemoryRepository,
    private val conversationDao: ConversationDao,
    private val fileContentExtractor: FileContentExtractor,
    private val languageManager: LanguageManager,
) : ChatRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _history = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _tokensPerSecond = MutableStateFlow(0f)

    init {
        // Restore persisted conversation on startup
        scope.launch {
            val saved = conversationDao.getAll()
            if (saved.isNotEmpty()) {
                _history.value = saved.map { it.toChatMessage() }
            }
        }
    }

    override fun getHistory(): Flow<List<ChatMessage>> = _history.asStateFlow()
    override fun getTokensPerSecond(): Flow<Float> = _tokensPerSecond.asStateFlow()

    override fun streamResponse(userMessage: String, attachments: List<AttachedFile>): Flow<String> {
        val userMsg = ChatMessage(
            content = userMessage,
            role = MessageRole.USER,
            attachments = attachments,
        )
        _history.update { it + userMsg }
        scope.launch { conversationDao.insert(userMsg.toEntity()) }

        val streamingId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(
            id = streamingId,
            content = "",
            role = MessageRole.ASSISTANT,
            isStreaming = true,
        )
        _history.update { it + placeholder }

        val accumulated = StringBuilder()
        val startTime = System.currentTimeMillis()
        var tokenCount = 0

        val prompt = buildPrompt(userMessage, attachments)

        return inferenceEngine.generateStream(prompt, PackType.BASE)
            .onEach { token ->
                accumulated.append(token)
                tokenCount++
                val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                if (elapsed > 0) _tokensPerSecond.value = tokenCount / elapsed
                _history.update { history ->
                    history.map { msg ->
                        if (msg.id == streamingId)
                            msg.copy(content = accumulated.toString(), tokenCount = tokenCount)
                        else msg
                    }
                }
            }
            .onCompletion {
                _tokensPerSecond.value = 0f
                val finalMsg = ChatMessage(
                    id = streamingId,
                    content = accumulated.toString(),
                    role = MessageRole.ASSISTANT,
                    isStreaming = false,
                    tokenCount = tokenCount,
                )
                _history.update { history ->
                    history.map { msg -> if (msg.id == streamingId) finalMsg else msg }
                }
                scope.launch { conversationDao.insert(finalMsg.toEntity()) }
            }
    }

    override suspend fun clearHistory() {
        _history.update { emptyList() }
        conversationDao.deleteAll()
    }

    override suspend fun deleteMessage(id: String) {
        _history.update { it.filterNot { msg -> msg.id == id } }
        conversationDao.deleteById(id)
    }

    // ── Prompt builder ────────────────────────────────────────────────────────
    // Uses Gemma 2 IT chat template for best instruction-following quality.
    // System context is injected into the first user turn (Gemma 2 has no system turn).
    private fun buildPrompt(userMessage: String, attachments: List<AttachedFile>): String {
        // Retrieve persisted memory facts (runs on IO coroutine context via suspend in scope)
        // We use runBlocking-style here because buildPrompt is called inside a Flow builder
        val memoryContext = runCatching {
            kotlinx.coroutines.runBlocking { memoryRepository.buildContextSummary() }
        }.getOrDefault("")

        val systemInstructions = buildSystemPrompt(memoryContext)

        // Recent history — exclude current user msg and streaming placeholder (last 2)
        val history = _history.value.let { all ->
            val withoutCurrent = all.dropLast(2)  // drop: current user msg + streaming placeholder
            withoutCurrent
                .filter { it.content.isNotBlank() && !it.isStreaming }
                .takeLast(MAX_HISTORY_TURNS)
        }

        // File context using keyword-aware RAG chunking
        val fileContext = if (attachments.isNotEmpty())
            fileContentExtractor.buildRagContext(attachments, userMessage)
        else ""

        return buildString {
            // First turn: embed system instructions in user turn (Gemma 2 format)
            if (history.isEmpty()) {
                append("<start_of_turn>user\n")
                append(systemInstructions)
                append("\n\n")
                if (fileContext.isNotEmpty()) {
                    append(fileContext)
                    append("\n")
                }
                append(userMessage)
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            } else {
                // Inject system into first historical user turn
                val firstUser = history.first()
                append("<start_of_turn>user\n")
                append(systemInstructions)
                append("\n\n")
                append(firstUser.content)
                append("<end_of_turn>\n")

                // Replay remaining history
                history.drop(1).forEach { msg ->
                    val role = if (msg.role == MessageRole.USER) "user" else "model"
                    append("<start_of_turn>$role\n")
                    append(msg.content)
                    append("<end_of_turn>\n")
                }

                // Current user message
                append("<start_of_turn>user\n")
                if (fileContext.isNotEmpty()) {
                    append(fileContext)
                    append("\n")
                }
                append(userMessage)
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            }
        }.let { prompt ->
            // Hard safety guard: if prompt exceeds budget, trim oldest history turns
            if (prompt.length > MAX_PROMPT_CHARS * 2) trimPrompt(prompt) else prompt
        }
    }

    private fun buildSystemPrompt(memoryContext: String): String = buildString {
        appendLine("You are Saarthi (सारथी), an intelligent personal AI assistant designed for India.")
        appendLine("You run completely offline on the user's device — no internet, full privacy.")
        appendLine("Be helpful, concise, and accurate. Support Hindi and English naturally.")
        appendLine("For analysis tasks, be structured: use bullet points or numbered steps.")
        appendLine("If you don't know something, say so clearly rather than guessing.")
        if (memoryContext.isNotEmpty()) {
            appendLine()
            append(memoryContext)
        }
    }.trimEnd()

    private fun trimPrompt(prompt: String): String {
        // Remove oldest turns (between the first and second <start_of_turn>) to fit budget
        val turns = prompt.split("<start_of_turn>").filter { it.isNotBlank() }
        val trimmed = turns.takeLast(5)
        return trimmed.joinToString("<start_of_turn>", prefix = "<start_of_turn>")
    }

    // ── Entity mapping ────────────────────────────────────────────────────────
    private fun ChatMessage.toEntity() = ConversationEntity(
        id = id,
        content = content,
        role = role.name,
        timestamp = timestamp,
        tokenCount = tokenCount,
    )

    private fun ConversationEntity.toChatMessage() = ChatMessage(
        id = id,
        content = content,
        role = MessageRole.valueOf(role),
        timestamp = timestamp,
        tokenCount = tokenCount,
        isStreaming = false,
    )
}
