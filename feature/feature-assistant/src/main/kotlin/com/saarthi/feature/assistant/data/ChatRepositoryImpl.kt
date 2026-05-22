package com.saarthi.feature.assistant.data

import android.content.Context
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.DeviceProfiler
import com.saarthi.core.inference.InferenceService
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.PackType
import com.saarthi.core.inference.prompt.SystemPromptProvider
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
// Char-budget per model class. Each is sized to the model's actual token
// context, NOT shared via aliases — the previous shared "1280" alias meant a
// budget bump for Gemma 3n silently widened Gemma 1B's ceiling too, even
// though 1B's engine is configured for 512 tokens total. A long user message
// could then overflow the native KV cache and crash or truncate badly.
//
// Sizing rationale (≈4 chars/token):
//   COMPACT  1500 chars ≈ 375 tok  →  fits inside 1B's 512-tok budget with
//                                     ~140 tok left for the reply.
//   STANDARD 5000 chars ≈ 1.25k tok →  fits inside 3n's 2048-tok budget with
//                                      ~800 tok for reply; large enough for
//                                      the 3.1k-char standardPrompt + user +
//                                      recap (previously 3k → truncated user).
//   2048    5500 chars ≈ 1.4k tok  →  Gemma 2 (2048 tok).
//   LARGE   8000 chars ≈ 2k tok    →  Gemma 4 (2048+ tok).
private const val MAX_PROMPT_CHARS_COMPACT  = 1_500   // Gemma 3 1B (512-tok)
private const val MAX_PROMPT_CHARS_STANDARD = 5_000   // Gemma 3n E2B/E4B
private const val MAX_PROMPT_CHARS_2048     = 5_500   // Gemma 2
private const val MAX_PROMPT_CHARS_LARGE    = 8_000   // Gemma 4

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
    private val systemPromptProvider: SystemPromptProvider,
    private val responseStyleManager: com.saarthi.core.i18n.ResponseStyleManager,
    private val personalityPreference: com.saarthi.core.i18n.PersonalityPreference,
) : ChatRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _history = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _tokensPerSecond = MutableStateFlow(0f)
    private val _currentSessionId = MutableStateFlow("default")

    // ── RAG session store ────────────────────────────────────────────────────
    // Documents the user attached to a chat. The previous design only injected
    // file context on the turn the file was attached — every follow-up
    // question lost the document and the model fell back to general knowledge
    // ("attachments=0" in the logs after turn 1). We now keep the extracted
    // text per session and re-score the chunks against EACH new user query so
    // every turn has fresh, query-relevant excerpts.
    //
    // Process-lifetime only — cleared on createSession() / deleteSession() /
    // clearHistory(). No DB persistence in v1; the extracted text is large
    // and the user can re-attach if they reopen the app.
    private val sessionDocuments: MutableMap<String, List<AttachedFile>> = mutableMapOf()

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
        // Reset engine session so the new chat starts with a clean KV cache
        runCatching { inferenceEngine.resetSession() }
        switchSession(id)
        return id
    }

    override suspend fun switchSession(sessionId: String) {
        _currentSessionId.value = sessionId
        val messages = conversationDao.getBySession(sessionId)
        _history.value = messages.map { it.toChatMessage() }
        // Reset engine session to prevent stale KV cache from previous chat
        runCatching { inferenceEngine.resetSession() }
    }

    override suspend fun deleteSession(sessionId: String) {
        // Cascade-delete every artefact tied to this chat. Without this,
        // memories/embeddings from a deleted chat would persist and surface
        // in future chats — that's the "states-in-India answer mentions
        // groceries" bug class users reported.
        conversationDao.deleteBySession(sessionId)
        memoryRepository.deleteForSession(sessionId)
        sessionDocuments.remove(sessionId)  // drop pinned RAG docs for this chat
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
        // Pin any newly-attached files to the session so follow-up turns can
        // re-inject relevant excerpts. Once pinned, we keep them for the life
        // of the session even though pendingAttachments clears in the VM.
        if (attachments.isNotEmpty()) {
            val existing = sessionDocuments[sessionId].orEmpty()
            // Dedupe by uri so re-sending the same file doesn't duplicate it.
            val merged = (existing + attachments).distinctBy { it.uri.toString() }
            sessionDocuments[sessionId] = merged
            DebugLogger.log("RAG", "Pinned ${attachments.size} doc(s) to session=$sessionId — total now ${merged.size}")
        }
        val userMsg = ChatMessage(content = userMessage, role = MessageRole.USER, attachments = attachments)
        _history.update { it + userMsg }
        
        scope.launch {
            withContext(kotlinx.coroutines.NonCancellable) {
                conversationDao.insert(userMsg.toEntity(sessionId))
                
                // Update session title from first user message
                val session = chatSessionDao.getAll().find { it.id == sessionId }
                if (session != null) {
                    val title = if (session.title == "New Chat") graphemeSafeTake(userMessage, 40).trimEnd() else session.title
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
                    // Strip complete marker tags so they never appear in the chat bubble.
                    // streaming=true holds back any in-progress identity leak
                    // ("I am Gem…") until it can be rewritten in full.
                    val visible = ResponseMarkerParser.stripForDisplay(accumulated.toString(), streaming = true)
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

                    // Save extracted memories — scoped to THIS chat session so the
                    // facts can't bleed into another chat's prompt. Industry-standard
                    // per-chat isolation (matches ChatGPT / Claude / Gemini default).
                    parsed.memories.forEach { marker ->
                        scope.launch {
                            memoryRepository.set(
                                sessionId = sessionId,
                                key = marker.key.trim().lowercase().replace(" ", "_"),
                                value = marker.value.trim(),
                                packSource = "USER",
                            )
                        }
                    }

                    // Defensive reminder gate. The system prompt tells the model to
                    // emit reminder markers ONLY when the user explicitly asked, but
                    // Gemma 4 at temperature=1.0 still over-emits — the user reported
                    // notifications firing for casual topics like "GGUF optimization".
                    // We refuse to schedule unless the user's most recent message
                    // actually contains a reminder trigger phrase.
                    if (parsed.reminders.isNotEmpty()) {
                        if (userAskedForReminder(userMessage)) {
                            parsed.reminders.forEach { marker ->
                                val text = marker.text.trim()
                                when {
                                    marker.delayMinutes != null ->
                                        reminderManager.scheduleByDelay(text, marker.delayMinutes)
                                    marker.time != null ->
                                        reminderManager.scheduleReminder(text, marker.time.trim())
                                }
                            }
                        } else {
                            DebugLogger.log(
                                "REMINDER",
                                "Dropping ${parsed.reminders.size} reminder marker(s) — user did not request one (msg=\"${userMessage.take(60)}\")"
                            )
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
        // Clearing history is also "wipe this chat's brain" — drop the
        // session's memories so the next reply starts clean. Without this,
        // the model could still see facts from messages the user just
        // cleared.
        memoryRepository.deleteForSession(sessionId)
        sessionDocuments.remove(sessionId)  // drop pinned RAG docs too
        chatSessionDao.updateTitleAndTimestamp(sessionId, "New Chat", System.currentTimeMillis())
        // Reset engine session so cleared chat starts fresh
        runCatching { inferenceEngine.resetSession() }
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
            val baseBudget = when {
                modelName.contains("Gemma 4", ignoreCase = true) -> MAX_PROMPT_CHARS_LARGE
                modelName.contains("Gemma 2", ignoreCase = true) -> MAX_PROMPT_CHARS_2048
                // 3n must be matched BEFORE generic "Gemma 3" — its E2B/E4B
                // variants have a 2048-token context, much larger than the 1B.
                modelName.contains("3n", ignoreCase = true)      -> MAX_PROMPT_CHARS_STANDARD
                // Everything else (Gemma 3 1B / Compact / unknown small
                // models) gets the tight 1.5 k budget — safe for a 512-tok
                // engine and prevents a future "Gemma 3 ???" model from
                // accidentally inheriting the standard 5 k ceiling.
                else                                              -> MAX_PROMPT_CHARS_COMPACT
            }
            // When the session has documents pinned, the prompt carries dense
            // RAG content (code, tables, lists) whose chars/token ratio is
            // closer to 3.5:1 instead of the usual 4:1 for prose. The old
            // 8000-char ceiling on LARGE tier hit the native KV cache at
            // 2068 tokens (cap is 2048) and the engine rejected the prompt
            // with `Input token ids are too long` — visible in the user log
            // at 18:36:29. Drop the ceiling by 30% whenever docs are active
            // so RAG content stays well under the native limit.
            val hasDocs = sessionDocuments[_currentSessionId.value]?.isNotEmpty() == true
            return if (hasDocs) (baseBudget * 0.7).toInt() else baseBudget
        }

    // DeviceProfiler.profile() makes several system calls (MemoryInfo, thermal,
    // battery). Calling it on every send + every history-trim was wasteful. Cache
    // for 30 s — RAM headroom can fluctuate within a chat (other apps, GC) so a
    // fully static cache would mis-classify, but per-prompt was overkill.
    private var cachedProfile: com.saarthi.core.inference.model.DeviceProfile? = null
    private var cachedProfileAtMs: Long = 0L
    private val profileCacheTtlMs = 30_000L
    private fun deviceProfileCached(): com.saarthi.core.inference.model.DeviceProfile {
        val now = System.currentTimeMillis()
        val cached = cachedProfile
        if (cached != null && now - cachedProfileAtMs < profileCacheTtlMs) return cached
        val fresh = deviceProfiler.profile()
        cachedProfile = fresh
        cachedProfileAtMs = now
        return fresh
    }

    private val maxHistoryTurns: Int
        get() {
            val budget = maxPromptChars
            val profile = deviceProfileCached()

            // Tiered history scaling:
            // 1. If RAM is low (< 4GB available), drop history aggressively to save KV memory.
            // 2. Otherwise, use the model's full safe capacity.
            return if (profile.availableRamMb < 3500) {
                when (budget) {
                    MAX_PROMPT_CHARS_COMPACT  -> 0 // no history — 1B has no room for it on low-RAM
                    MAX_PROMPT_CHARS_STANDARD -> 1 // bare minimum for 3n on low-RAM
                    MAX_PROMPT_CHARS_2048     -> 2
                    else                       -> 3
                }
            } else {
                when (budget) {
                    MAX_PROMPT_CHARS_COMPACT  -> 1 // 1 turn of recap is all 1B can carry
                    MAX_PROMPT_CHARS_STANDARD -> 2
                    MAX_PROMPT_CHARS_2048     -> 4
                    else                       -> MAX_HISTORY_TURNS
                }
            }
        }

    private suspend fun buildPrompt(userMessage: String, attachments: List<AttachedFile>): String {
        val isFresh = inferenceEngine.isFreshConversation
        val tier = systemPromptProvider.tierFor(inferenceEngine.activeModelName)

        // ── Compact (Gemma 3 1B) transcript priming ──────────────────────
        // The model is too small to follow a system-prompt persona — but
        // it DOES follow a transcript-style in-context example because the
        // pattern is concrete and pre-fills its "Assistant turn" position.
        // The whole string gets wrapped in <user>…<end><model> by the chat
        // template, so the model picks up after "Saarthi:" naturally.
        //
        // Only FRESH turns need the priming — the KV cache from turn 1
        // carries the "Saarthi:" pattern into follow-up turns, where we
        // can send just the user message.
        if (tier == com.saarthi.core.inference.prompt.SystemPromptProvider.ModelTier.COMPACT) {
            return if (isFresh) {
                "Below is a chat between User and Saarthi, a friendly Indian AI assistant. " +
                "Saarthi gives short, helpful, friendly answers in a natural conversational tone.\n\n" +
                "User: $userMessage\n" +
                "Saarthi:"
            } else {
                userMessage
            }
        }
        // Use session-pinned docs (set in streamResponse), NOT just this
        // turn's attachments. This is the core RAG fix — every follow-up
        // question gets the document re-scored against the new query, so
        // "give overview", "what tech is needed", "what's the salary" each
        // pull a different relevant slice of the same PDF.
        val sessionDocs = sessionDocuments[_currentSessionId.value].orEmpty()
        val docsForThisTurn: List<AttachedFile> = when {
            sessionDocs.isNotEmpty() -> sessionDocs   // re-inject across turns
            attachments.isNotEmpty() -> attachments   // fallback (shouldn't happen — already pinned)
            else                     -> emptyList()
        }
        val hasDocs = docsForThisTurn.isNotEmpty()
        val fileContext = if (hasDocs)
            fileContentExtractor.buildRagContext(docsForThisTurn, userMessage)
        else ""

        // OFFICIAL APPROACH (matches Google AI Edge Gallery's AI Chat):
        // The Conversation maintains the chat history in its KV cache. Each turn we
        // send ONLY the new user message and the engine wraps it in Gemma's native
        // chat template (<start_of_turn>user … <start_of_turn>model …) automatically.
        //
        // FRESH turn = first message in this Conversation (brand-new chat, switched
        // session, cleared history, or recycled after error). On a FRESH turn we send
        // the full system prompt, plus — if the user is resuming a saved chat — a
        // brief recap of the last 1–2 turns so the model picks up where they left off.
        //
        // CONTINUE turn = the model already has the prior turns in its KV cache; we
        // just send the new user message.
        return if (isFresh) {
            // Memory is scoped to THIS chat — never read another chat's memories.
            val currentSession = _currentSessionId.value
            val memoryContext = runCatching { memoryRepository.buildContextSummary(currentSession) }.getOrDefault("")
            val priorTurns = buildPriorTurnsRecap()
            val systemInstructions = buildSystemPrompt(memoryContext, priorTurns)
            DebugLogger.log("PROMPT", "FRESH turn  systemChars=${systemInstructions.length}  thisTurnAttachments=${attachments.size}  docsPinned=${docsForThisTurn.size}  recapTurns=${priorTurns.isNotEmpty()}")
            buildString {
                // Only emit the system block if non-blank — Compact tier
                // returns empty (see SystemPromptProvider) and leading
                // whitespace before the user message would look like garbage
                // input to the model.
                if (systemInstructions.isNotBlank()) {
                    append(systemInstructions)
                    append("\n\n")
                }
                if (fileContext.isNotEmpty()) { append(fileContext); append("\n") }
                append(userMessage)
            }.let { prompt ->
                val budget = maxPromptChars
                // trimPrompt() preserves complete <start_of_turn> blocks for
                // multi-turn prompts. For FRESH prompts (no turn markers), we
                // also need to guarantee the *user message* survives any
                // truncation — otherwise the model has nothing to answer and
                // ends up reading the system prompt back to itself ("Okay, I
                // understand! I'm ready to be Saarthi…"). Pass userMessage so
                // it can be pinned to the tail of the trimmed prompt.
                val finalPrompt = trimPrompt(prompt, budget, pinnedTail = userMessage)
                DebugLogger.log("PROMPT", "Final FRESH prompt  chars=${finalPrompt.length}  budget=$budget")
                finalPrompt
            }
        } else {
            DebugLogger.log("PROMPT", "CONTINUE turn  attachments=${attachments.size}  (KV cache holds prior history)")
            buildString {
                if (fileContext.isNotEmpty()) { append(fileContext); append("\n") }
                append(userMessage)
            }
        }
    }

    /**
     * Recap of the most recent saved turn(s), giving the model continuity even
     * though the Conversation's KV cache is recycled per turn (see
     * [LiteRTInferenceEngine.generateStream] for why we recycle).
     *
     * Sized per tier:
     *  • COMPACT (1B): just the user's last question (~120 chars). 1B doesn't
     *    benefit much from re-reading its own previous reply, but it does
     *    benefit from knowing what topic the user was on.
     *  • STANDARD / LARGE: last 2 user/assistant pairs in full.
     */
    private fun buildPriorTurnsRecap(): String {
        val complete = buildCompleteHistoryPairs(
            _history.value.filter { it.content.isNotBlank() && !it.isStreaming }.dropLast(1)
        )
        if (complete.isEmpty()) return ""
        val tier = systemPromptProvider.tierFor(inferenceEngine.activeModelName)
        if (tier == SystemPromptProvider.ModelTier.COMPACT) {
            val lastUser = complete.lastOrNull { it.role == MessageRole.USER } ?: return ""
            return "Earlier the user asked: \"${lastUser.content.take(120)}\""
        }
        val pairsToKeep = 2
        val perMsgChars = 400
        val recent = complete.takeLast(pairsToKeep * 2)
        return buildString {
            append("Recap of the user's most recent exchange with you (continue naturally — do not repeat it):\n")
            recent.forEach { msg ->
                val who = if (msg.role == MessageRole.USER) "User" else "You"
                // Defensive: pass each message through stripForDisplay before
                // recapping. Normally clean text is stored (parse().cleanText
                // is called on completion), but crash / truncated-stream paths
                // can leave a half-formed `[SAARTHI_MEMORY key="…"` fragment
                // in history. Without this, that fragment goes straight back
                // into the next FRESH system prompt and the model may treat
                // it as a fresh marker instruction.
                val sanitized = ResponseMarkerParser.stripForDisplay(msg.content, streaming = false)
                val body = sanitized.take(perMsgChars)
                append("$who: $body\n")
            }
        }.trimEnd()
    }

    /**
     * True when the user's message contains an explicit ask for a reminder /
     * alert / alarm. Used as a runtime gate before scheduling, so an over-eager
     * model can't fire a notification for a casual discussion topic.
     *
     * Covers English and the most common Indian-language reminder phrases.
     * Conservative on purpose — false negatives are ok (user can rephrase),
     * false positives are not (unwanted notifications break trust fast).
     */
    private fun userAskedForReminder(userMessage: String): Boolean {
        val msg = userMessage.lowercase()
        return REMINDER_TRIGGER_PHRASES.any { msg.contains(it) }
    }

    private val REMINDER_TRIGGER_PHRASES = listOf(
        // English
        "remind me", "remind us", "set a reminder", "set reminder",
        "alert me", "notify me", "wake me", "ping me",
        "set an alarm", "set alarm",
        // Hindi (Latin + Devanagari)
        "yaad dila", "yaad rakh", "yaad dilana", "yaad dilao",
        "याद दिला", "याद रख", "रिमाइंडर", "अलार्म",
        // Tamil / Telugu / Bengali / Marathi / Kannada / Gujarati / Punjabi / Odia
        // — common transliterated forms users actually type
        "ninaivu padut", "gnabakam unchu", "mone koriye dao",
        "athavan karun", "nenapu ittuko", "yaad rakhjo",
    )

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

    private fun buildSystemPrompt(memoryContext: String, priorTurnsContext: String = ""): String {
        val modelName = inferenceEngine.activeModelName
        val tier = systemPromptProvider.tierFor(modelName)
        if (memoryContext.isNotEmpty()) {
            val memCount = memoryContext.lines().count { it.startsWith("- ") }
            DebugLogger.log("MEMORY", "Injected $memCount user memory facts into prompt  tier=$tier")
        } else {
            DebugLogger.log("MEMORY", "No user memories stored yet")
        }
        val timeContext = buildTimeContext()
        DebugLogger.log("PROMPT", "tier=$tier  model=${modelName ?: "unknown"}  recap=${priorTurnsContext.isNotEmpty()}  lang=${currentLanguage.code}  time=$timeContext")
        // Always pass the language instruction, including for English. Without it
        // the model defaults to whatever it picks up from the user's input or its
        // training mix (we saw English-selected users getting Hindi replies).
        val langLine = currentLanguage.systemPromptInstruction
        val styleSuffix = buildResponseStyleSuffix()
        // Personality Pal: read the user's selected persona; SystemPromptProvider
        // gates COMPACT tier so 1B always gets an empty system block regardless.
        // For STANDARD/LARGE BASE, the override replaces the default Saarthi
        // identity paragraph while every behaviour/tool rule stays intact.
        val persona = personalityPreference.selected.value
        // Default persona uses Saarthi's built-in identity + no extra rules.
        // Non-default personas inject BOTH the identity AND the behavior
        // anchors (end-of-prompt) — that's what actually moves the model's
        // voice turn-by-turn, not the identity paragraph in isolation.
        val isDefaultPersona = persona.id == com.saarthi.core.i18n.PersonalityCatalog.SAARTHI.id
        val personalityOverride = if (isDefaultPersona) "" else persona.systemPersona
        val personalityRules = if (isDefaultPersona) emptyList() else persona.behaviorRules
        return systemPromptProvider.build(
            modelName = modelName,
            pack = PackType.BASE,
            languageInstruction = langLine,
            memoryContext = memoryContext,
            priorTurnsContext = priorTurnsContext,
            timeContext = timeContext,
            responseStyleSuffix = styleSuffix,
            personalityOverride = personalityOverride,
            personalityBehaviorRules = personalityRules,
        )
    }

    /**
     * Render the user's Response Style preferences (set in Settings → Response
     * style) as a short suffix appended to the system prompt. Empty when the
     * user is on defaults, so existing behaviour is preserved.
     */
    private fun buildResponseStyleSuffix(): String {
        val style = responseStyleManager.style.value
        val lines = mutableListOf<String>()
        when (style.length) {
            "short" -> lines += "Keep replies short (1–2 sentences)."
            "long"  -> lines += "Give detailed replies with examples when useful."
            else    -> { /* medium = no extra instruction */ }
        }
        when (style.tone) {
            "warm"   -> lines += "Use a warm, friendly tone."
            "formal" -> lines += "Use a formal, professional tone."
            else     -> { /* balanced = no extra instruction */ }
        }
        when (style.languageMix) {
            "pure" -> lines += "Use pure Hindi (शुद्ध हिन्दी) without English loanwords."
            "eng"  -> lines += "Reply only in English."
            else   -> { /* mix = no extra instruction */ }
        }
        if (!style.showDisclaimers) {
            lines += "Skip safety/medical disclaimers unless the user asks."
        }
        if (!style.includeExamples) {
            lines += "Avoid worked examples; explain concepts without illustrations."
        }
        return lines.joinToString(separator = " ")
    }

    /**
     * Single-line time context surfaced to the model so greetings match the
     * actual time of day (was a real bug: "Good morning" at 9 PM). Format
     * deliberately compact so it doesn't dominate the prompt.
     *
     * Example output: "Current local time is 21:14 on Mon, 20 May 2026 — it
     * is evening (use a time-appropriate greeting if you greet the user)."
     */
    private fun buildTimeContext(): String {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val band = when (hour) {
            in 5..11   -> "morning"
            in 12..16  -> "afternoon"
            in 17..20  -> "evening"
            else       -> "night"
        }
        val timeStr = java.text.SimpleDateFormat("HH:mm 'on' EEE, d MMM yyyy", java.util.Locale.US)
            .format(now.time)
        return "Current local time is $timeStr — it is $band (use a time-appropriate greeting if you greet the user)."
    }

    /**
     * Intelligent Sliding Window: Handles context overflow for the model's compiled KV limits.
     *
     * Strategy (in priority order):
     * 1. Fast path: already within budget, return immediately.
     * 2. Drop middle history turns one-by-one until budget is met.
     * 3. If still over budget (e.g. system prompt alone overflows), hard-truncate
     *    the system turn as a last resort.
     */
    /**
     * String.take() truncates at a UTF-16 code-unit boundary, which splits
     * mid-character on scripts that use combining marks (Devanagari, Tamil,
     * Telugu, Bengali, …). For session titles built from "नमस्ते आज क्या करूँ?"
     * that meant the chip subtitle could end with an orphan virama or vowel
     * mark — rendered as a tofu box on most fonts.
     *
     * BreakIterator walks Unicode grapheme cluster boundaries, so we cut at
     * the last *complete* cluster that fits in [n] code units.
     */
    private fun graphemeSafeTake(s: String, n: Int): String {
        if (s.length <= n) return s
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(s)
        var last = 0
        var cur = it.next()
        while (cur != java.text.BreakIterator.DONE && cur <= n) {
            last = cur
            cur = it.next()
        }
        return s.substring(0, last)
    }

    private fun trimPrompt(prompt: String, budget: Int = 3000, pinnedTail: String = ""): String {
        if (prompt.length <= budget) return prompt

        val marker = "<start_of_turn>"
        val turns = prompt.split(marker).filter { it.isNotBlank() }.map { marker + it }

        if (turns.size < 2) {
            DebugLogger.log("PROMPT", "WARN: single-turn prompt (${prompt.length}c) exceeds budget ($budget) — preserving user tail")
            // Critical: when the FRESH prompt is one big concatenated block
            // (system + "\n\n" + userMessage), the user message lives at the
            // end. take(budget) would chop the tail off and the model would
            // read the system prompt aloud. Instead, KEEP the user tail intact
            // and squeeze the system prefix to whatever room is left.
            //
            // We require at least 32 chars of system prefix; if pinnedTail
            // alone would blow the budget, fall back to keeping the LAST
            // `budget` chars of the prompt (still tail-aligned — guarantees
            // the user message is the most-recent thing the model sees).
            if (pinnedTail.isNotBlank() && prompt.endsWith(pinnedTail)) {
                val tailLen = pinnedTail.length
                val systemRoom = budget - tailLen - 4  // " … " separator
                return if (systemRoom >= 32) {
                    val systemPrefix = prompt.substring(0, prompt.length - tailLen)
                    val trimmedSystem = systemPrefix.take(systemRoom)
                    "$trimmedSystem … \n$pinnedTail"
                } else {
                    // User message alone is larger than budget — keep the tail.
                    prompt.substring(prompt.length - budget)
                }
            }
            return prompt.takeLast(budget)
        }

        val systemTurn  = turns.first()
        val latestTurns = turns.takeLast(minOf(2, turns.size - 1))
        val middleTurns = turns.drop(1).dropLast(latestTurns.size).toMutableList()

        // Phase 1: Try to fit by dropping middle history
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

        // Phase 2: If still over, drop ALL history except system and latest turn
        if (currentPrompt.length > budget) {
            currentPrompt = systemTurn + latestTurns.joinToString("")
        }

        // Phase 3: Final hard truncation if even system + latest user Q is too big.
        // We take the budget but ensure we at least try to keep the roles intact.
        if (currentPrompt.length > budget) {
            DebugLogger.log("PROMPT", "WARN: critical truncation to budget $budget")
            return currentPrompt.take(budget)
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
