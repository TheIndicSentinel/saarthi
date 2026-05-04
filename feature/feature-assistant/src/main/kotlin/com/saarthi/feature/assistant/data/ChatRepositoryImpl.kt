package com.saarthi.feature.assistant.data

import android.content.Context
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.DeviceProfiler
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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

private const val MAX_HISTORY_TURNS = 6  // reduced adaptively for small-context models
// Prompt budget guard: ensures total prompt chars fit within the model's compiled KV cache.
// Formula: (contextLength - maxOutputTokens) * avgCharsPerToken
// For 1280-ctx models: (1280 - 512) * 3.0 = ~2304 chars
// For 2048-ctx models: (2048 - 512) * 3.0 = ~4608 chars
// We use 3.0 chars/token (conservative) because Gemma tokenizer uses sub-word BPE.
// SM8550 CPU: createConversation() crashes at maxNumTokens ≥ 768. Engine uses 512.
// System prompt ~90 tokens, leaving ~400 tokens for history + user + response.
// At 3.5 chars/token: 400 × 3.5 = 1400 chars total; minus ~300 chars system = 1100 for history+user.
// Use 1000 to be safe (leaves ~286 tokens for history+user, ~226 for response).
private const val MAX_PROMPT_CHARS_1280 = 1_000  // 512-ctx CPU devices (Gemma 3/3n)
private const val MAX_PROMPT_CHARS_2048 = 4_500  // 2048-ctx models (Gemma 2)
private const val MAX_PROMPT_CHARS_LARGE = 8_000 // Large-ctx models (Gemma 4)

@Singleton
class ChatRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationDao: ConversationDao,
    private val chatSessionDao: ChatSessionDao,
    private val memoryRepository: MemoryRepository,
    private val fileContentExtractor: FileContentExtractor,
    private val languageManager: LanguageManager,
    private val inferenceEngine: InferenceEngine,
    private val reminderManager: ReminderManager,
    private val deviceProfiler: DeviceProfiler,
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
        
        scope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                conversationDao.insert(userMsg.toEntity(sessionId))
                
                // Update session title from first user message
                val session = chatSessionDao.getAll().find { it.id == sessionId }
                if (session != null) {
                    val title = if (session.title == "New Chat") userMessage.take(40).trimEnd() else session.title
                    chatSessionDao.updateTitleAndTimestamp(sessionId, title, System.currentTimeMillis())
                }
            }
        }


        val streamingId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(id = streamingId, content = "", role = MessageRole.ASSISTANT, isStreaming = true)
        _history.update { it + placeholder }

        // Build prompt and run inference fully on IO — avoids blocking the main thread
        // Start foreground service IMMEDIATELY — prevents Android from killing process.
        // Updates the notification from "Loading…" to "Generating response…" if already running.
        InferenceService.startGenerating(context)
        
        return flow {
            // Check readiness first
            if (!inferenceEngine.isReady) {
                val errMsg = "⚠️ Model not ready. If this model keeps crashing, please go back to setup and select a different model."
                InferenceService.stop(context)
                _history.update { history ->
                    history.map { if (it.id == streamingId) it.copy(content = errMsg, isStreaming = false) else it }
                }
                return@flow
            }


        // Build prompt using adaptive budget based on model's context length
        val prompt = withContext(Dispatchers.IO) { buildPrompt(userMessage, attachments) }
        DebugLogger.log("CHAT", "streamResponse start  promptChars=${prompt.length}  session=$sessionId")

        val startTime = System.currentTimeMillis()
        var tokenCount = 0
        val accumulated = StringBuilder()

            inferenceEngine.generateStream(prompt, PackType.BASE)
                .catch { e ->
                    // Only stop FGS if the native inference thread is no longer running.
                    // If isNativeGenerating=true here it means the watchdog timed out (or the
                    // coroutine was cancelled) while the native GPU thread is still computing.
                    // Stopping the FGS in that state removes OS protection from the native
                    // thread — Samsung's power watchdog then kills the process ~40s later.
                    // The native 'done' callback in LiteRTInferenceEngine will call stop() once
                    // the native thread actually finishes.
                    if (!inferenceEngine.isNativeGenerating) InferenceService.stop(context)
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
                    // Same guard as .catch: only stop FGS if native thread has finished.
                    if (!inferenceEngine.isNativeGenerating) InferenceService.stop(context)
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

    private val maxPromptChars: Int
        get() {
            val modelName = inferenceEngine.activeModelName ?: ""
            return when {
                modelName.contains("Gemma 4", ignoreCase = true) -> MAX_PROMPT_CHARS_LARGE
                modelName.contains("Gemma 2", ignoreCase = true) -> MAX_PROMPT_CHARS_2048
                else -> MAX_PROMPT_CHARS_1280  // Gemma 3/3n default
            }
        }

    private val maxHistoryTurns: Int
        get() {
            val budget = maxPromptChars
            val profile = deviceProfiler.profile()
            
            // Tiered history scaling:
            // 1. If RAM is low (< 4GB available), drop history aggressively to save KV memory.
            // 2. Otherwise, use the model's full safe capacity.
            return if (profile.availableRamMb < 3500) {
                when (budget) {
                    MAX_PROMPT_CHARS_1280 -> 1 // bare minimum on low-RAM
                    MAX_PROMPT_CHARS_2048 -> 2
                    else -> 3
                }
            } else {
                when (budget) {
                    MAX_PROMPT_CHARS_1280 -> 2   
                    MAX_PROMPT_CHARS_2048 -> 4   
                    else -> MAX_HISTORY_TURNS    
                }
            }
        }

    private suspend fun buildPrompt(userMessage: String, attachments: List<AttachedFile>): String {
        val memoryContext = runCatching { memoryRepository.buildContextSummary() }.getOrDefault("")
        val systemInstructions = buildSystemPrompt(memoryContext)

        DebugLogger.log("PROMPT", "System prompt built  chars=${systemInstructions.length}  lang=${currentLanguage.code}  memory=${memoryContext.isNotEmpty()}")

        // Filter history: non-empty, non-streaming, drop current user message, keep only
        // complete user→model pairs (orphaned user messages from prior crashes are excluded).
        val rawHistory = _history.value
            .filter { it.content.isNotBlank() && !it.isStreaming }
            .dropLast(1)  // drop the just-added current user message
        val history = buildCompleteHistoryPairs(rawHistory)
            .takeLast(maxHistoryTurns)

        val fileContext = if (attachments.isNotEmpty())
            fileContentExtractor.buildRagContext(attachments, userMessage)
        else ""

        DebugLogger.log("PROMPT", "History turns=${history.size}  attachments=${attachments.size}")

        return buildString {
            if (history.isEmpty()) {
                // Single turn: system instructions + user message in one user turn.
                // Gemma instruct format: <start_of_turn>user\nSYSTEM\n\nUSER<end_of_turn>
                append("<start_of_turn>user\n")
                append(systemInstructions)
                append("\n\n")
                if (fileContext.isNotEmpty()) { append(fileContext); append("\n") }
                append(userMessage)
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            } else {
                // Multi-turn: system instructions are prepended to the FIRST history user
                // turn only. All subsequent turns follow the plain user/model alternation.
                // This matches the Gemma 3 instruct chat template exactly and avoids
                // nested <start_of_turn> tags that confuse the native tokeniser.
                var systemInjected = false
                history.forEach { msg ->
                    val role = if (msg.role == MessageRole.USER) "user" else "model"
                    append("<start_of_turn>$role\n")
                    if (!systemInjected && msg.role == MessageRole.USER) {
                        append(systemInstructions)
                        append("\n\n")
                        systemInjected = true
                    }
                    append(msg.content)
                    append("<end_of_turn>\n")
                }
                // Current user message (system never appears here)
                append("<start_of_turn>user\n")
                if (fileContext.isNotEmpty()) { append(fileContext); append("\n") }
                append(userMessage)
                append("<end_of_turn>\n")
                append("<start_of_turn>model\n")
            }
        }.let { prompt ->
            val budget = maxPromptChars
            val needsTrim = prompt.length > budget
            val finalPrompt = if (needsTrim) trimPrompt(prompt, budget) else prompt
            DebugLogger.log("PROMPT", "Final prompt  chars=${finalPrompt.length}  budget=$budget  trimmed=$needsTrim")
            finalPrompt
        }
    }

    /**
     * Returns only complete user→model pairs from [history].
     * Orphaned user messages (no following model response) are dropped — they appear
     * after a crash where the assistant never finished generating a reply, and including
     * them creates consecutive same-role turns that violate Gemma's chat template.
     */
    private fun buildCompleteHistoryPairs(history: List<ChatMessage>): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        var i = 0
        while (i < history.size) {
            val msg = history[i]
            if (msg.role == MessageRole.USER &&
                i + 1 < history.size &&
                history[i + 1].role == MessageRole.ASSISTANT) {
                result.add(history[i])
                result.add(history[i + 1])
                i += 2
            } else {
                i++  // skip orphaned user message or lone assistant message
            }
        }
        return result
    }

    private fun buildSystemPrompt(memoryContext: String): String = buildString {
        append(PackType.BASE.systemPrompt)
        appendLine()
        appendLine()
        append(currentLanguage.systemPromptInstruction)
        if (memoryContext.isNotEmpty()) {
            appendLine()
            appendLine()
            append(memoryContext)
            val memCount = memoryContext.lines().count { it.startsWith("- ") }
            DebugLogger.log("MEMORY", "Injected $memCount user memory facts into prompt (global, cross-chat)")
        } else {
            DebugLogger.log("MEMORY", "No user memories stored yet")
        }
    }.trimEnd()

    /**
     * Intelligent Sliding Window: Handles context overflow for the model's compiled KV limits.
     *
     * Strategy (in priority order):
     * 1. Fast path: already within budget, return immediately.
     * 2. Drop middle history turns one-by-one until budget is met.
     * 3. If still over budget (e.g. system prompt alone overflows), hard-truncate
     *    the system turn as a last resort.
     *
     * FIXED: removed `if (turns.size <= 3) return prompt` which caused every
     * first message to bypass trimming and return the full over-budget prompt.
     */
    private fun trimPrompt(prompt: String, budget: Int = MAX_PROMPT_CHARS_1280): String {
        if (prompt.length <= budget) return prompt

        val marker = "<start_of_turn>"
        val turns = prompt.split(marker).filter { it.isNotBlank() }.map { marker + it }

        if (turns.size < 2) {
            DebugLogger.log("PROMPT", "WARN: single-turn prompt (${prompt.length}c) exceeds budget ($budget) — hard truncating")
            return prompt.take(budget)
        }

        val systemTurn  = turns.first()
        val latestTurns = turns.takeLast(minOf(2, turns.size - 1))
        val middleTurns = turns.drop(1).dropLast(latestTurns.size).toMutableList()

        var currentPrompt = buildString {
            append(systemTurn)
            middleTurns.forEach { append(it) }
            latestTurns.forEach { append(it) }
        }
        while (currentPrompt.length > budget && middleTurns.isNotEmpty()) {
            middleTurns.removeAt(0)
            currentPrompt = buildString {
                append(systemTurn)
                middleTurns.forEach { append(it) }
                latestTurns.forEach { append(it) }
            }
        }

        // Phase 2: system prompt itself exceeds budget — hard truncate as last resort.
        if (currentPrompt.length > budget) {
            val latestBlock = buildString { latestTurns.forEach { append(it) } }
            val systemBudget = budget - latestBlock.length
            val truncatedSystem = if (systemBudget > 0) systemTurn.take(systemBudget) else ""
            currentPrompt = truncatedSystem + latestBlock
            DebugLogger.log("PROMPT", "WARN: system turn truncated to ${truncatedSystem.length}c to meet budget ($budget)")
        }

        return currentPrompt
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
