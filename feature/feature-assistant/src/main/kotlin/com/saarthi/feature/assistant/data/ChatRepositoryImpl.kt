package com.saarthi.feature.assistant.data

import android.content.Context
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.InferenceService
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.PackType
import com.saarthi.core.memory.db.ChatSessionDao
import com.saarthi.core.memory.db.ChatSessionEntity
import com.saarthi.core.memory.db.ConversationDao
import com.saarthi.core.memory.db.ConversationEntity
import com.saarthi.core.memory.domain.MemoryRepository
import com.saarthi.feature.assistant.domain.AttachedFile
import com.saarthi.feature.assistant.domain.ChatMessage
import com.saarthi.feature.assistant.domain.ChatRepository
import com.saarthi.feature.assistant.domain.ChatSession
import com.saarthi.feature.assistant.domain.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import com.saarthi.core.inference.DebugLogger
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

private const val MAX_HISTORY_TURNS = 4
// Prompt budget: Gemma .task models have 1280 compiled KV cache tokens.
// 1 token ≈ 3.5 chars → ~4480 chars. Reserve ~1000 for system prompt + slack.
private const val MAX_PROMPT_CHARS = 3_500

@Singleton
class ChatRepositoryImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val inferenceEngine: InferenceEngine,
    private val memoryRepository: MemoryRepository,
    private val conversationDao: ConversationDao,
    private val chatSessionDao: ChatSessionDao,
    private val fileContentExtractor: FileContentExtractor,
    private val languageManager: LanguageManager,
    private val reminderManager: ReminderManager,
) : ChatRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _history = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _tokensPerSecond = MutableStateFlow(0f)
    private val _currentSessionId = MutableStateFlow("default")

    // LanguageManager.selectedLanguage is now a StateFlow that collects DataStore eagerly.
    // Reading .value gives the current language without any async race condition.
    private val currentLanguage: SupportedLanguage
        get() = languageManager.selectedLanguage.value

    init {
        // Restore or create default session
        scope.launch {
            val sessions = chatSessionDao.getAll()
            val sessionId = if (sessions.isNotEmpty()) {
                sessions.first().id
            } else {
                val defaultSession = ChatSessionEntity(
                    id = "default",
                    title = "New Chat",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
                chatSessionDao.insert(defaultSession)
                "default"
            }
            _currentSessionId.value = sessionId
            val saved = conversationDao.getBySession(sessionId)
            if (saved.isNotEmpty()) _history.value = saved.map { it.toChatMessage() }
        }
    }

    override fun getHistory(): Flow<List<ChatMessage>> = _history.asStateFlow()
    override fun getTokensPerSecond(): Flow<Float> = _tokensPerSecond.asStateFlow()
    override fun getCurrentSessionId(): Flow<String> = _currentSessionId.asStateFlow()

    override fun getSessions(): Flow<List<ChatSession>> =
        chatSessionDao.getAllFlow().map { entities -> entities.map { it.toSession() } }

    override suspend fun createSession(): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        chatSessionDao.insert(ChatSessionEntity(id = id, title = "New Chat", createdAt = now, updatedAt = now))
        switchSession(id)
        return id
    }

    override suspend fun switchSession(sessionId: String) {
        _currentSessionId.value = sessionId
        val messages = conversationDao.getBySession(sessionId)
        _history.value = messages.map { it.toChatMessage() }
    }

    override suspend fun deleteSession(sessionId: String) {
        conversationDao.deleteBySession(sessionId)
        chatSessionDao.deleteById(sessionId)
        if (_currentSessionId.value == sessionId) {
            val remaining = chatSessionDao.getAll()
            if (remaining.isNotEmpty()) {
                switchSession(remaining.first().id)
            } else {
                createSession()
            }
        }
    }

    override fun streamResponse(userMessage: String, attachments: List<AttachedFile>): Flow<String> {
        val sessionId = _currentSessionId.value
        val userMsg = ChatMessage(content = userMessage, role = MessageRole.USER, attachments = attachments)
        _history.update { it + userMsg }
        scope.launch { conversationDao.insert(userMsg.toEntity(sessionId)) }

        // Update session title from first user message
        scope.launch {
            val session = chatSessionDao.getAll().find { it.id == sessionId }
            if (session != null) {
                val title = if (session.title == "New Chat") userMessage.take(40).trimEnd() else session.title
                chatSessionDao.updateTitleAndTimestamp(sessionId, title, System.currentTimeMillis())
            }
        }

        val streamingId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(id = streamingId, content = "", role = MessageRole.ASSISTANT, isStreaming = true)
        _history.update { it + placeholder }

        // Build prompt and run inference fully on IO — avoids blocking the main thread
        // Start foreground service IMMEDIATELY — prevents Android from killing process.
        // Doing this outside the flow builder ensures it's called on the main/caller thread.
        InferenceService.start(appContext)
        
        return flow {
            val prompt = withContext(Dispatchers.IO) { buildPrompt(userMessage, attachments) }
            DebugLogger.log("CHAT", "streamResponse start  promptChars=${prompt.length}  session=$sessionId")
            
            // Critical: Allow Foreground Service and OS priority manager to stabilize before GPU spike
            delay(500)
            
            val startTime = System.currentTimeMillis()
            var tokenCount = 0
            val accumulated = StringBuilder()

            inferenceEngine.generateStream(prompt, PackType.BASE)
                .catch { e ->
                    // Stop foreground service on error
                    InferenceService.stop(appContext)
                    // Surface engine errors as a visible assistant message — never crash the app.
                    val errMsg = e.message?.takeIf { it.isNotBlank() }
                        ?: "Something went wrong. Please try again."
                    DebugLogger.log("CHAT", "streamResponse ERROR: $errMsg")
                    _history.update { history ->
                        history.map { msg ->
                            if (msg.id == streamingId)
                                msg.copy(content = errMsg, isStreaming = false)
                            else msg
                        }
                    }
                }
                .onEach { token ->
                    accumulated.append(token)
                    tokenCount++
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                    if (elapsed > 0) _tokensPerSecond.value = tokenCount / elapsed
                    // Strip complete marker tags so they never appear in the chat bubble
                    val visible = ResponseMarkerParser.stripForDisplay(accumulated.toString())
                    _history.update { history ->
                        history.map { msg ->
                            if (msg.id == streamingId)
                                msg.copy(content = visible, tokenCount = tokenCount)
                            else msg
                        }
                    }
                    emit(token)
                }
                .onCompletion { throwable ->
                    // Stop foreground service when generation is done
                    InferenceService.stop(appContext)
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                    val tps = if (elapsed > 0) tokenCount / elapsed else 0f
                    DebugLogger.log("CHAT", "streamResponse done  tokens=$tokenCount  elapsed=${elapsed.toInt()}s  tps=${"%.1f".format(tps)}  error=${throwable?.message}")
                    _tokensPerSecond.value = 0f

                    // Parse markers out of the raw accumulated text
                    val parsed = ResponseMarkerParser.parse(accumulated.toString())

                    val finalMsg = ChatMessage(
                        id = streamingId,
                        content = parsed.cleanText,
                        role = MessageRole.ASSISTANT,
                        isStreaming = false,
                        tokenCount = tokenCount,
                    )
                    _history.update { history ->
                        history.map { msg -> if (msg.id == streamingId) finalMsg else msg }
                    }
                    scope.launch { conversationDao.insert(finalMsg.toEntity(sessionId)) }

                    // Save extracted memories
                    parsed.memories.forEach { marker ->
                        scope.launch {
                            memoryRepository.set(
                                key = marker.key.trim().lowercase().replace(" ", "_"),
                                value = marker.value.trim(),
                                packSource = "USER",
                            )
                        }
                    }

                    // Schedule extracted reminders — relative (delay_minutes) or absolute (time)
                    parsed.reminders.forEach { marker ->
                        val text = marker.text.trim()
                        when {
                            marker.delayMinutes != null ->
                                reminderManager.scheduleByDelay(text, marker.delayMinutes)
                            marker.time != null ->
                                reminderManager.scheduleReminder(text, marker.time.trim())
                        }
                    }
                }
                .collect {}
        }
    }

    override suspend fun clearHistory() {
        val sessionId = _currentSessionId.value
        _history.update { emptyList() }
        conversationDao.deleteBySession(sessionId)
        chatSessionDao.updateTitleAndTimestamp(sessionId, "New Chat", System.currentTimeMillis())
    }

    override suspend fun deleteMessage(id: String) {
        _history.update { it.filterNot { msg -> msg.id == id } }
        conversationDao.deleteById(id)
    }

    // ── Prompt builder ────────────────────────────────────────────────────────
    // Always called on IO thread from streamResponse.

    private suspend fun buildPrompt(userMessage: String, attachments: List<AttachedFile>): String {
        val memoryContext = runCatching { memoryRepository.buildContextSummary() }.getOrDefault("")
        val systemInstructions = buildSystemPrompt(memoryContext)

        // Exclude the currently-streaming placeholder and any other streaming messages
        // using a content-based filter rather than positional dropLast, which is fragile
        // when messages are deleted or the order changes.
        val history = _history.value
            .filter { it.content.isNotBlank() && !it.isStreaming }
            .dropLast(1)  // exclude the just-added user message (already appended to _history)
            .takeLast(MAX_HISTORY_TURNS)

        val fileContext = if (attachments.isNotEmpty())
            fileContentExtractor.buildRagContext(attachments, userMessage)
        else ""

        return buildString {
            // Strong System Block — Prepending instructions to the very first turn is the most
            // effective way to align Gemma 3 without a dedicated 'system' role.
            val systemPrefix = "<start_of_turn>user\n[SYSTEM_DIRECTIVE]\n$systemInstructions\n[/SYSTEM_DIRECTIVE]\n"

            if (history.isEmpty()) {
                append(systemPrefix)
                if (fileContext.isNotEmpty()) { append(fileContext); append("\n") }
                append(userMessage)
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            } else {
                append(systemPrefix)
                val firstUser = history.first()
                append(firstUser.content)
                append("<end_of_turn>\n")

                history.drop(1).forEach { msg ->
                    val role = if (msg.role == MessageRole.USER) "user" else "model"
                    append("<start_of_turn>$role\n")
                    append(msg.content)
                    append("<end_of_turn>\n")
                }

                // Re-assert language and accuracy constraints for 'Gemma 3n' drift prevention
                append("<start_of_turn>user\n")
                if (fileContext.isNotEmpty()) { append(fileContext); append("\n") }
                append(userMessage)
                append("\n\n(Respond in ${currentLanguage.nativeName} only. Be factual and accurate.)")
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            }
        }.let { prompt ->
            if (prompt.length > MAX_PROMPT_CHARS) trimPrompt(prompt) else prompt
        }
    }

    private fun buildSystemPrompt(memoryContext: String): String = buildString {
        append(PackType.BASE.systemPrompt)
        appendLine()
        appendLine()
        // Critical: tell the model exactly which language to respond in
        append(currentLanguage.systemPromptInstruction)
        if (memoryContext.isNotEmpty()) {
            appendLine()
            appendLine()
            append(memoryContext)
        }
    }.trimEnd()

    private fun trimPrompt(prompt: String): String {
        val turns = prompt.split("<start_of_turn>").filter { it.isNotBlank() }
        if (turns.size <= 3) return prompt // system + user + model — nothing to trim

        // ALWAYS preserve the first turn (contains [SYSTEM_DIRECTIVE]) and the last 2 turns
        // (current user message + model reply marker). Drop middle history turns.
        val systemTurn = turns.first()
        val remainingTurns = turns.drop(1)
        val kept = remainingTurns.takeLast(4) // last 2 pairs of user+model

        return buildString {
            append("<start_of_turn>")
            append(systemTurn)
            kept.forEach { turn ->
                append("<start_of_turn>")
                append(turn)
            }
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

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
        role = MessageRole.valueOf(role),
        timestamp = timestamp,
        tokenCount = tokenCount,
        isStreaming = false,
    )

    private fun ChatSessionEntity.toSession() = ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
