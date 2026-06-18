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

// Hard ceiling on the prior-turns recap block. Keeps total prompt fill well
// under the tier budget so a long chat can't push Gemma 3n into the
// high-fill repetition loops observed in production (19:37:30 / 19:42:01).
private const val RECAP_MAX_CHARS = 280

@Singleton
class ChatRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationDao: ConversationDao,
    private val chatSessionDao: ChatSessionDao,
    private val memoryRepository: MemoryRepository,
    private val languageManager: LanguageManager,
    private val inferenceEngine: InferenceEngine,
    private val reminderManager: ReminderManager,
    private val deviceProfiler: DeviceProfiler,
    private val systemPromptProvider: SystemPromptProvider,
    private val responseStyleManager: com.saarthi.core.i18n.ResponseStyleManager,
    private val personalityPreference: com.saarthi.core.i18n.PersonalityPreference,
    private val ragRepository: RagDocumentRepository,
) : ChatRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _history = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _tokensPerSecond = MutableStateFlow(0f)
    private val _currentSessionId = MutableStateFlow("default")

    // ── Reminder confirmation ────────────────────────────────────────────────
    // Exposed to the UI so a confirmation chip can appear immediately after
    // the model schedules a reminder — currently the scheduling is silent and
    // the user has no in-app feedback that a notification was set.
    private val _lastReminder = kotlinx.coroutines.flow.MutableStateFlow<com.saarthi.feature.assistant.domain.ScheduledReminderInfo?>(null)
    override fun getLastReminder(): kotlinx.coroutines.flow.Flow<com.saarthi.feature.assistant.domain.ScheduledReminderInfo?> = _lastReminder

    // ── RAG persistence ──────────────────────────────────────────────────────
    // Document chunks now live in Room (`rag_chunks`) via [RagDocumentRepository].
    // The old in-memory sessionDocuments map dropped extracted text on every
    // process restart — the user had to re-attach the same PDF after every
    // app swipe. Persistence + per-query BM25 retrieval is the production
    // path. We keep a tiny in-process cache of "have we already indexed this
    // file under this session?" so the per-send DAO upsert is a cheap no-op
    // after the first turn that attached the file.
    private val indexedDocsByUri: MutableMap<String, MutableSet<String>> = mutableMapOf()

    // The doc URIs the user MOST RECENTLY attached in each session — these
    // become the "focus" set: subsequent RAG searches restrict to these
    // until the user attaches a different batch. Without this, attaching
    // a second PDF and asking "Give overview" pulled chunks from the FIRST
    // PDF because they were still indexed under the same session.
    // Memory-only by design — fresh chat / fresh launch resets focus.
    private val focusedDocsBySession: MutableMap<String, Set<String>> = mutableMapOf()

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
        // switchSession() calls resetSession() internally — calling it here too
        // caused the double [SESSION] reset seen in every new-session log entry.
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
        ragRepository.deleteForSession(sessionId)
        indexedDocsByUri.remove(sessionId)
        focusedDocsBySession.remove(sessionId)
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
        // NOTE: actual indexing happens INSIDE the flow's withContext block
        // below — see the comment around buildPrompt. Previously this ran
        // fire-and-forget on `scope.launch`, which meant the very first
        // send after an attach saw `ragChunks=0` because the BM25 search
        // executed before the chunks landed in Room (visible at 02:33:48,
        // 02:34:38, 02:38:05, 02:41:27 in the production log). Doing it
        // inline costs 1–3 s on the first turn for a large PDF and gives
        // the user a correctly-grounded answer instead of a wrong one.
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


        // Build prompt using adaptive budget based on model's context length.
        // Index any newly-attached files FIRST (synchronously, on IO) so
        // BM25 search inside buildPrompt sees the chunks. Idempotent —
        // the per-session in-process cache prevents re-chunking on every
        // turn after the first.
        val prompt = withContext(Dispatchers.IO) {
            if (attachments.isNotEmpty()) {
                val indexed = indexedDocsByUri.getOrPut(sessionId) { mutableSetOf() }
                var newCount = 0
                for (file in attachments) {
                    val key = file.uri.toString()
                    if (key in indexed) continue
                    runCatching { ragRepository.indexIfNeeded(sessionId, file) }
                        .onSuccess { indexed += key; newCount += 1 }
                        .onFailure { DebugLogger.log("RAG", "Index failed for ${file.name}: ${it.message}") }
                }
                if (newCount > 0) DebugLogger.log("RAG", "Indexed $newCount doc(s) inline before retrieval  session=$sessionId")
                // Set this batch as the active focus — every subsequent
                // turn searches only inside these docs until the user
                // attaches a different set or clears the chat.
                focusedDocsBySession[sessionId] = attachments.map { it.uri.toString() }.toSet()
                DebugLogger.log("RAG", "Focus set to ${attachments.size} doc(s) for session=$sessionId")
            }
            buildPrompt(userMessage, attachments)
        }
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

                    // Reliability guard: a small model under device memory
                    // pressure can finish with empty / near-empty output (seen
                    // in the logs as 2–3 tokens at <1 tok/s), which would render
                    // as a BLANK bubble that looks broken — fatal for a paid app.
                    // An error before the first token would ALSO blank the bubble
                    // here, because onCompletion overwrites the message that
                    // .catch set. Resolve a sensible, actionable reply instead of
                    // ever showing an empty bubble.
                    val isCancelled = throwable is kotlinx.coroutines.CancellationException
                    val isError = throwable != null && !isCancelled
                    val partial = _history.value.firstOrNull { it.id == streamingId }?.content.orEmpty()
                    val finalContent = resolveAssistantReply(
                        cleanedText = parsed.cleanText,
                        isCancelled = isCancelled,
                        isError = isError,
                        partialVisible = partial,
                    )
                    val finalMsg = ChatMessage(
                        id = streamingId,
                        content = finalContent,
                        role = MessageRole.ASSISTANT,
                        isStreaming = false,
                        tokenCount = tokenCount,
                    )
                    _history.update { history ->
                        history.map { msg -> if (msg.id == streamingId) finalMsg else msg }
                    }
                    // Persist only a REAL model reply — never the error / empty /
                    // stopped placeholders. Persisting them would pollute chat
                    // history AND the multi-turn transcript fed back into later
                    // prompts ("Saarthi: I couldn't generate a reply…").
                    val isRealReply = parsed.cleanText.isNotBlank() ||
                        (isCancelled && partial.isNotBlank())
                    if (isRealReply) {
                        scope.launch { conversationDao.insert(finalMsg.toEntity(sessionId)) }
                    }

                    // Save extracted memories. Two tiers (industry-standard):
                    //  • Durable identity facts (name, city, profession, …) →
                    //    USER_SCOPE so they follow the user into every chat.
                    //  • Everything else → THIS session, so conversational
                    //    context can't bleed into another chat's prompt.
                    parsed.memories.forEach { marker ->
                        scope.launch {
                            persistMemoryFact(
                                sessionId = sessionId,
                                rawKey = marker.key,
                                value = marker.value,
                            )
                        }
                    }
                    // Implicit extraction: the model often answers a personal
                    // statement ("my name is Arjun", "I'm a teacher") WITHOUT
                    // emitting a [SAARTHI_MEMORY] marker. Mirror what ChatGPT /
                    // Gemini do — scan the user's own message for high-confidence
                    // identity facts and persist them too. Conservative patterns
                    // only (see extractImplicitFacts) to avoid false positives.
                    extractImplicitFacts(userMessage).forEach { (k, v) ->
                        scope.launch { persistMemoryFact(sessionId, k, v) }
                    }

                    // Defensive reminder gate. The system prompt tells the model to
                    // emit reminder markers ONLY when the user explicitly asked, but
                    // Gemma 4 at temperature=1.0 still over-emits — it has fired
                    // markers on ordinary chat with no reminder intent. We refuse to
                    // schedule unless the user's most recent message actually shows
                    // reminder intent (see ReminderRequestDetector).
                    if (parsed.reminders.isNotEmpty()) {
                        if (userAskedForReminder(userMessage)) {
                            parsed.reminders.forEach { marker ->
                                val text = marker.text.trim()
                                when {
                                    marker.delayMinutes != null -> {
                                        reminderManager.scheduleByDelay(text, marker.delayMinutes)
                                        val label = when {
                                            marker.delayMinutes < 60 -> "in ${marker.delayMinutes} min"
                                            marker.delayMinutes % 60 == 0 -> "in ${marker.delayMinutes / 60}h"
                                            else -> "in ${marker.delayMinutes / 60}h ${marker.delayMinutes % 60}min"
                                        }
                                        _lastReminder.value = com.saarthi.feature.assistant.domain.ScheduledReminderInfo(text, label)
                                    }
                                    marker.time != null -> {
                                        val t = marker.time.trim()
                                        reminderManager.scheduleReminder(text, t)
                                        // Convert "HH:MM" → "at H:MM AM/PM" for display
                                        val label = runCatching {
                                            val parts = t.split(":")
                                            val h = parts[0].toInt(); val m = parts[1].toInt()
                                            val amPm = if (h < 12) "AM" else "PM"
                                            val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
                                            "at $h12:${m.toString().padStart(2,'0')} $amPm"
                                        }.getOrDefault("at $t")
                                        _lastReminder.value = com.saarthi.feature.assistant.domain.ScheduledReminderInfo(text, label)
                                    }
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
        ragRepository.deleteForSession(sessionId)
        indexedDocsByUri.remove(sessionId)
        focusedDocsBySession.remove(sessionId)
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
            // RAG content whose chars/token ratio is closer to 3:1 instead of
            // ~4:1 for prose, so we trim the budget to stay under the native
            // KV-cache cap. The cached `indexedDocsByUri` set is the
            // synchronous gate — populated by streamResponse() the moment an
            // attachment lands.
            //
            // Tier-aware multiplier (was a flat 0.7 for every tier). The
            // flat value was correct for LARGE (8000 × 0.7 = 5600 leaves
            // room for system + chunks) but broken for STANDARD: 5000 × 0.7
            // = 3500 c, while Gemma 3n's STANDARD system prompt is ~3769 c —
            // already over budget by itself, leaving zero room for RAG.
            // Visible in the production log at 21:48:16 as
            // `ragBudget=0 ragChars=0`, after which the model responded
            // about the time-context line because it had no doc context.
            val hasDocs = indexedDocsByUri[_currentSessionId.value]?.isNotEmpty() == true
            val charBudget = if (!hasDocs) {
                baseBudget
            } else {
                val docsMultiplier = when (baseBudget) {
                    MAX_PROMPT_CHARS_COMPACT  -> 0.70    // 1500 → 1050 — COMPACT no longer attaches anyway
                    // STANDARD bumped from 0.92 → 0.98: when the user selects
                    // a non-default Personality Pal, the persona's behaviour
                    // rules add ~900 c to the system prompt (~2885 c default
                    // → ~3790 c with persona). At 0.92 (4600 c total) that
                    // left only ~620 c for RAG — not enough to fit even one
                    // chunk plus the rules header. ragChars=0 was visible in
                    // the production log at 23:13:05. 0.98 (4900 c) plus the
                    // smaller safety margin below restores room for one full
                    // chunk even with persona inflation. Still well under
                    // Gemma 3n's 2048-tok input cap (~4150 c at the dense
                    // RAG ratio of 2.7 c/tok the engine actually sees).
                    MAX_PROMPT_CHARS_STANDARD -> 0.98    // 5000 → 4900
                    MAX_PROMPT_CHARS_2048     -> 0.92    // 5500 → 5060 — Gemma 2, bumped for the same reason
                    // Raised from 0.70 (5600c) to 0.90 (7200c): the 5600c
                    // budget left only ~1050c for RAG after the 4423c system
                    // prompt, and after the rulesHeader (~243c) only ~807c
                    // for actual chunk content — barely 1-2 chunks of 500c.
                    // At 0.90 the char budget is 7200c; the token clamp below
                    // then caps it to whatever actually fits the model's
                    // context window (the real binding constraint).
                    else                       -> 0.90    // 8000 → 7200 — LARGE (Gemma 4)
                }
                (baseBudget * docsMultiplier).toInt()
            }
            // ── Token-ceiling clamp (the real safety net) ──────────────
            // The char budgets above are heuristics; the HARD limit is the
            // model's context window (maxNumTokens), which the native engine
            // checks against the INPUT prompt. maxTokens is NOT fixed — it
            // drops with RAM headroom (Gemma 4: 2048 → 1536; Gemma 3n: 1024
            // → 512). When the char budget translated to more tokens than
            // that ceiling, generation failed instantly with "Input token
            // ids are too long: 2051 >= 2048" (E2B) and "1609 >= 1536"
            // (E4B) — every doc turn produced an empty reply. Derive a
            // char budget from the live token ceiling so the assembled
            // prompt can never overrun it, regardless of tier or RAM.
            val ctxTokens = inferenceEngine.maxContextTokens
            if (ctxTokens <= 0) return charBudget   // model not loaded → trust char heuristic
            // Reserve room so the reply has space to generate (a prompt that
            // fills the whole window leaves nothing to decode into and the
            // engine evicts context mid-reply → the repetition loops seen in
            // the log). 256 tokens ≈ a solid phone-sized answer; 16 covers
            // the turn-structure tokens (<start_of_turn> etc.) the engine
            // adds around our text.
            val inputTokenBudget = (ctxTokens - 256 - 16).coerceAtLeast(256)
            // Conservative chars/token: dense content (logs, hex IDs, code)
            // tokenises at ~3.0 c/tok — the densest observed in the field was
            // 3.09. Using 3.0 guarantees inputTokenBudget chars never exceed
            // the token budget even for the worst-case content; ordinary
            // prose (~4 c/tok) simply leaves extra headroom.
            val tokenDerivedCharBudget = (inputTokenBudget * 3.0).toInt()
            return minOf(charBudget, tokenDerivedCharBudget)
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
        val sessionId = _currentSessionId.value

        // ── RAG (BM25, persisted) ────────────────────────────────────────
        // The session's indexed chunks live in Room. Score every chunk
        // against this turn's query so each follow-up gets a fresh slice
        // of the document — "give overview", "what tech is needed",
        // "what's the salary" all pull different chunks of the same PDF.
        // Files attached on THIS turn are surfaced as error/unindexable
        // notes (binary, oversize) so the model knows they exist even
        // when there is no text to retrieve.
        val focusUris = focusedDocsBySession[sessionId]
        // NOTE: knowledge packs (Kisan etc.) are intentionally NOT merged
        // into the main chat here. Packs are a fully separate, modular
        // subsystem with their own Q&A surface (PackChatScreen) — wiring
        // them into the persona-driven main chat caused pack context to
        // bleed into normal conversations. The main chat only ever
        // retrieves the user's OWN attached documents for this session.
        // Last completed user turn — passed to the retriever for two uses:
        // (a) follow-up expansion: "also list X" → BM25("prior X also list X")
        // (b) zero-hit retry: if BM25 finds nothing for the current query,
        //     retry with prior query to surface the same evidence region.
        val priorUserQuery = _history.value
            .filter { it.role == MessageRole.USER && it.content.isNotBlank() && !it.isStreaming }
            .dropLast(1)  // exclude the current turn being built
            .lastOrNull()
            ?.content
            ?.take(200)

        val retrieved = runCatching {
            ragRepository.search(
                sessionId = sessionId,
                query = userMessage,
                restrictToDocUris = focusUris,
                priorQuery = priorUserQuery?.takeIf { it != userMessage && it.length > 8 },
            )
        }.getOrDefault(emptyList())
        val unreadableThisTurn = attachments.filter { it.error != null || (it.extractedText.isNullOrBlank() && !it.isImage) }

        // Per-chunk retrieval log — names + chunk index + BM25 score +
        // page range + a short text preview. Lets us diagnose "model
        // cited wrong source" complaints without having to rerun the
        // session: the log shows exactly which chunks the model was
        // given on each turn.
        if (retrieved.isNotEmpty()) {
            DebugLogger.log("RAG", "retrieved ${retrieved.size} chunk(s) for query=\"${userMessage.take(80)}\"")
            retrieved.forEachIndexed { i, c ->
                val ref = if (c.chunkIndex < 0) "outline" else "part ${c.chunkIndex + 1}"
                val pages = extractPageRange(c.text)?.let { " · $it" } ?: ""
                val preview = c.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60)?.trim() ?: ""
                DebugLogger.log("RAG", "  [${i + 1}] ${c.docName} · $ref$pages  score=${"%.2f".format(c.score)}  preview=\"$preview…\"")
            }
        }

        // ── Compact (Gemma 3 1B) transcript priming ──────────────────────
        // litertlm wraps everything in <start_of_turn>user … <start_of_turn>model,
        // so we prime the 1B model via a "transcript" pattern: a narrative
        // identity paragraph + one demonstration turn + the user's question
        // in "User: …\nSaarthi:" form. The model continues from the "Saarthi:"
        // anchor.
        //
        // Why narrative + demonstration (and NOT instructions / negations):
        //  • 1B-scale models cannot reliably separate system text from user text
        //    — anything written as "Rules: …", "You are NOT …", "Always do X"
        //    gets parroted back in the reply or, worse, the negated concept
        //    leaks (negation often acts as priming on small models).
        //  • A single in-context demonstration turn moves the model's tone more
        //    than any amount of imperative prose; this is the industry-standard
        //    in-context-learning pattern for 1B-class instruction tuning.
        //  • The persona is written in third person so the model treats it as
        //    background description, not as a script to copy verbatim.
        //  • For uncertainty, we *show* the desired behaviour in the example
        //    rather than telling the model what to do.
        //
        // isFreshConversation is always true here because the Conversation is
        // recycled on every onDone() callback — see LiteRTInferenceEngine. The
        // prior-turn recap compensates by injecting topic context so the model
        // is not cold on turn 2+.
        if (tier == com.saarthi.core.inference.prompt.SystemPromptProvider.ModelTier.COMPACT) {
            // 1B parroting fix: the previous FOUR-sentence THIRD-PERSON identity
            // ("Saarthi is a friendly… Saarthi runs… Saarthi replies…") read to
            // the 1B as *content to repeat*, so it frequently echoed the whole
            // paragraph back as its reply. A single SECOND-PERSON IMPERATIVE
            // instruction is far less echo-prone (the model treats a command as
            // something to follow, not recite) and still primes the persona +
            // the don't-guess rule. Kept to one line to minimise the surface
            // the 1B can parrot. (We deliberately don't coerce a non-English
            // output language here — that caused repetition loops; reliable
            // multilingual output is the STANDARD/LARGE models' job.)
            val identity = "You are Saarthi, a private on-device assistant for India. " +
                "Answer the user's question directly in a warm, simple tone — short and clear. " +
                "If you are unsure, say so instead of guessing."
            val recap = buildPriorTurnsRecap().let { r -> if (r.isNotBlank()) "\n\n$r" else "" }
            // Compute the budget left for RAG after identity / recap / user /
            // scaffolding — pass it to the block builder so chunks are dropped
            // at boundaries rather than sliced mid-text by trimPrompt.
            val scaffolding = identity.length + recap.length +
                userMessage.length + 40   // "\n\n…\n\nUser: …\nSaarthi:" markup
            val ragBudget = (MAX_PROMPT_CHARS_COMPACT - scaffolding - 80).coerceAtLeast(0)
            val fileContext = buildRagPromptBlock(retrieved, unreadableThisTurn, tier, ragBudget)
            val ragPart = if (fileContext.isNotEmpty()) "\n\n$fileContext" else ""
            val fullPrompt = "$identity$recap$ragPart\n\nUser: $userMessage\nSaarthi:"
            return trimPrompt(fullPrompt, MAX_PROMPT_CHARS_COMPACT, pinnedTail = "User: $userMessage\nSaarthi:")
        }

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
            // Recap is now kept on doc-pinned turns too. The Conversation's
            // KV cache is recycled after every reply, so EVERY turn is FRESH —
            // the recap is the ONLY thing that carries conversation continuity.
            // It used to be dropped here to save ~150–250c for chunks, back
            // when the full ~4423c BASE prompt left almost no room. But that
            // made document chat non-conversational: with no memory of the
            // prior turn, a follow-up like "explain more" or "now the second
            // one" just re-summarised the whole document every time. Now that
            // the compact grounded prompt (~962c) frees ~3400c, there is ample
            // room for the recent-questions recap, which lets the model treat
            // follow-ups as a continuing conversation instead of restarting.
            val docsPinned = retrieved.isNotEmpty() || unreadableThisTurn.isNotEmpty()
            // Full multi-turn context — user questions AND Saarthi's prior
            // answers — so follow-ups ("explain more", "the second one", "why?")
            // resolve against what was actually said. The KV cache is recycled
            // every turn on this hardware (a second sendMessageAsync on a live
            // Conversation SIGKILLs the process — see LiteRTInferenceEngine), so
            // the prompt is the ONLY carrier of continuity. STANDARD/LARGE get
            // the real transcript; COMPACT (1B) gets nothing (it parrots).
            val priorTurns = buildConversationContext(grounded = docsPinned)
            // On doc-grounded turns swap the full ~4423c persona/tools/reminders
            // prompt for a compact instruction set. The verbose prompt alone is
            // ~1370 tokens — larger than E4B's entire 1536-token window once RAM
            // is tight — so trimPrompt was butchering it and E4B produced no
            // reply at all. The compact version (~160 tokens) both fits the
            // small window AND frees ~1000 tokens for actual document chunks,
            // which is the real lever for answer quality on E2B/3n.
            val systemInstructions = buildSystemPrompt(memoryContext, priorTurns, grounded = docsPinned)
            val budget = maxPromptChars
            // Size the RAG block to fit the remaining budget AFTER the
            // system prompt and the user message have claimed their space.
            // Without this, the verbose Gemma 4 BASE persona (~3700c) +
            // 4 chunks (~2400c) overflowed the with-docs 5600c budget and
            // trimPrompt sliced mid-chunk → repetition loop at 01:38:26
            // and 01:41:26 in the user's log. The block builder now
            // includes chunks greedily and never cuts one in half.
            val systemPlusMargin = systemInstructions.length +
                (if (systemInstructions.isNotBlank()) 2 else 0) +   // "\n\n"
                userMessage.length + 1 +                              // userMessage + 1c
                // Was 160 — too generous on STANDARD (Gemma 3n) where
                // the with-docs budget is tight to begin with; the
                // saved 80 c is exactly enough for one extra chunk.
                80                                                    // safety margin
            val ragBudget = (budget - systemPlusMargin).coerceAtLeast(0)
            val fileContext = buildRagPromptBlock(retrieved, unreadableThisTurn, tier, ragBudget)
            DebugLogger.log("PROMPT", "FRESH turn  systemChars=${systemInstructions.length}  thisTurnAttachments=${attachments.size}  ragChunks=${retrieved.size}  ragBudget=$ragBudget  ragChars=${fileContext.length}  recapTurns=${priorTurns.isNotEmpty()}")
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
                // trimPrompt() is now a safety net rather than the primary
                // cutter — the RAG block was already sized to fit.
                val finalPrompt = trimPrompt(prompt, budget, pinnedTail = userMessage)
                DebugLogger.log("PROMPT", "Final FRESH prompt  chars=${finalPrompt.length}  budget=$budget")
                finalPrompt
            }
        } else {
            // CONTINUE: KV cache already holds the system prompt + prior
            // turns. Only RAG + user message go in this turn. The whole
            // budget is available for RAG, minus the user message.
            val budget = maxPromptChars
            val ragBudget = (budget - userMessage.length - 80).coerceAtLeast(0)
            val fileContext = buildRagPromptBlock(retrieved, unreadableThisTurn, tier, ragBudget)
            DebugLogger.log("PROMPT", "CONTINUE turn  attachments=${attachments.size}  ragBudget=$ragBudget  ragChars=${fileContext.length}  (KV cache holds prior history)")
            buildString {
                if (fileContext.isNotEmpty()) { append(fileContext); append("\n") }
                append(userMessage)
            }
        }
    }

    /**
     * Human-readable document name for use in citations.
     *
     * Strips file extensions (handles double-extensions like ".log.txt"),
     * replaces underscores/hyphens/dashes with spaces, removes leading
     * year-or-timestamp number prefixes (e.g. "2015 " or "20260603 "),
     * and caps at 28 chars on a word boundary.
     *
     * Examples:
     *   "2015_Douglas_Repair-Maintenance-Mobile-Cell-Phones.pdf" → "Douglas Repair Maintenance"
     *   "Python AI Engineer – Technical Screening Test.docx"    → "Python AI Engineer Technical"
     *   "doc20251117695301.pdf"                                 → "doc20251117695301"
     *   "saarthi_debug.log.txt"                                → "saarthi debug"
     *   "Dpdpact.pdf"                                          → "Dpdpact"
     */
    private fun shortDocName(rawName: String): String {
        val knownExts = listOf(".pdf", ".docx", ".doc", ".txt", ".log", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")
        var stem = rawName
        var stripped = true
        while (stripped) {
            val low = stem.lowercase()
            stripped = false
            for (ext in knownExts) {
                if (low.endsWith(ext)) { stem = stem.dropLast(ext.length); stripped = true; break }
            }
        }
        val cleaned = stem
            .replace('_', ' ').replace('-', ' ')
            .replace('–', ' ').replace('—', ' ')   // en-dash, em-dash
            .replace(Regex("[^\\p{L}\\p{N} ]+"), " ")   // anything else → space
            .replace(Regex("^[\\d ]+(?=\\p{L})"), "")   // strip leading digit-only prefix
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        if (cleaned.isBlank()) return stem.take(28)
        if (cleaned.length <= 28) return cleaned
        val truncated = cleaned.take(28).trimEnd()
        val lastSpace = truncated.lastIndexOf(' ')
        return if (lastSpace > 10) truncated.substring(0, lastSpace) else truncated
    }

    /**
     * Inline `--- Page X ---` markers come from `FileContentExtractor.extractPdfText`
     * — when a chunk straddles one or more pages, this surfaces the page
     * range so the citation header can read "pp.5-7" instead of just
     * "part 3". The model sees the page numbers in the chunk text anyway;
     * lifting them into the header makes the cite [N, p.X] pattern
     * trivial to follow.
     */
    private val PAGE_MARKER_REGEX = Regex("---\\s*Page\\s+(\\d+)\\s*---", RegexOption.IGNORE_CASE)
    private fun extractPageRange(text: String): String? {
        val pages = PAGE_MARKER_REGEX.findAll(text)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()
        if (pages.isEmpty()) return null
        val lo = pages.min()
        val hi = pages.max()
        return if (lo == hi) "p.$lo" else "pp.$lo-$hi"
    }

    /**
     * Strict-mode RAG block. Renders BM25-retrieved chunks (and any
     * unreadable attachments) as a numbered source list with hard rules:
     * answer ONLY from excerpts, cite [N], reply with the exact
     * "not in documents" line otherwise.
     *
     * Budget-aware — the caller passes [charBudget] computed as
     * `totalPromptBudget − systemPromptLen − userMessageLen − safety`,
     * and this builder includes chunks greedily until that budget is
     * exhausted. Chunks are NEVER sliced mid-text: dropping a whole
     * chunk from the tail is always preferred over emitting a half-
     * truncated paragraph that puts the model into a repetition loop
     * (observed at 01:38:26 / 01:41:26 in the production log).
     *
     * COMPACT (Gemma 3 1B) gets a one-liner rule header — the 1B model
     * can't carry the full rule list inside its 512-tok budget without
     * crowding out the actual chunks. Larger tiers get a tighter, single-
     * sentence rule header (was 5 lines / ~470c; now ~200c).
     */
    private fun buildRagPromptBlock(
        retrieved: List<com.saarthi.feature.assistant.data.RetrievedChunk>,
        unreadableThisTurn: List<AttachedFile>,
        tier: SystemPromptProvider.ModelTier,
        charBudget: Int,
    ): String {
        if (retrieved.isEmpty() && unreadableThisTurn.isEmpty()) return ""
        // If there is literally no room for even the rule header + one
        // small chunk, drop the block rather than emit a meaningless
        // header-only fragment that wastes tokens.
        if (charBudget < 200) return ""

        // Human-readable citation + smart fallback rules.
        // COMPACT gets a single short line; LARGE/STANDARD gets the full
        // three-bullet version with a worked example so the model has a
        // pattern to mirror rather than infer.
        val rulesHeader = if (tier == SystemPromptProvider.ModelTier.COMPACT) {
            "Attached excerpts — answer from these. After each claim write the source in parentheses: (Doc Name, p.X). " +
                "If the answer isn't in the excerpts, say so briefly, then add a short general answer prefixed 'In general:'.\n\n"
        } else {
            // ~360c — keeps chunks from being crowded out while giving the
            // model a clear citation pattern to mirror (worked example) AND
            // a smart fallback so "not found" turns are still helpful.
            "ATTACHED EXCERPTS — answer from these.\n" +
                "• Cite each claim as (Name, p.X) using the document name from the [N] header below; " +
                "include the page only when shown. Example: 'Consent is required (DPDP Act, p.3).'\n" +
                "• If the answer is not in the excerpts, say so in one sentence, then give a brief " +
                "general answer prefixed 'In general:' so the user still gets useful context.\n" +
                "• Do not invent facts from the excerpts or repeat these instructions.\n\n"
        }

        // Unreadable-file notes are short, high-signal, and small. Reserve
        // their length upfront so we don't drop them in favour of one more
        // chunk — the model needs to know which files were unreadable to
        // explain itself.
        val unreadableBlock = if (unreadableThisTurn.isNotEmpty()) {
            buildString {
                appendLine("Files attached this turn that could NOT be read (do not pretend to know their contents):")
                unreadableThisTurn.forEach { f ->
                    val why = f.error ?: "unsupported format — Saarthi cannot read binary files yet"
                    appendLine("  - ${f.name}: $why")
                }
            }.trimEnd() + "\n\n"
        } else ""

        var remaining = charBudget - rulesHeader.length - unreadableBlock.length
        val chunksBlock = buildString {
            for ((i, chunk) in retrieved.withIndex()) {
                val text = chunk.text.trim()
                // Human-readable header: [N] ShortDocName · p.X
                // The short name (≤28 chars) is what the model uses when
                // it writes the inline citation "(ShortDocName, p.X)".
                // The [N] prefix lets the model count sources without
                // printing the number in its reply.
                val shortName = shortDocName(chunk.docName)
                val pageRef = extractPageRange(text)?.let { " · $it" } ?: ""
                val header = "[${i + 1}] $shortName$pageRef\n"
                val total = header.length + text.length + 2  // +2 for trailing "\n\n"
                if (total > remaining) break  // never half-emit a chunk
                append(header)
                append(text)
                append("\n\n")
                remaining -= total
            }
        }

        // If the budget was so tight that not a single chunk fit, drop
        // the whole RAG block — emitting "ATTACHED EXCERPTS — answer ONLY
        // from these" with no excerpts puts the model into refusal mode
        // on every question. Better to fall back to the general prompt.
        if (chunksBlock.isEmpty() && unreadableBlock.isEmpty()) return ""

        return (rulesHeader + chunksBlock + unreadableBlock).trimEnd()
    }

    /**
     * Recap of the most recent saved turn(s), giving the model continuity even
     * though the Conversation's KV cache is recycled per turn (see
     * [LiteRTInferenceEngine.generateStream] for why we recycle).
     *
     * Sized per tier:
     *  • COMPACT (1B): the user's last question only (~120 chars), framed as
     *    a third-person note so the 1B model treats it as background rather
     *    than as a transcript to continue.
     *  • STANDARD / LARGE: the user's recent QUESTIONS only — NOT the model's
     *    own prior replies. Including the assistant text as "You: …" caused
     *    Gemma 4 to literally echo the entire prior reply at the start of
     *    every next turn (the prompt looked like a transcript and the model
     *    "continued" by reproducing the existing assistant turn before
     *    answering the new question). Keeping only the user side preserves
     *    topic awareness without giving the model a template to copy.
     */
    private fun buildPriorTurnsRecap(): String {
        val complete = buildCompleteHistoryPairs(
            _history.value.filter { it.content.isNotBlank() && !it.isStreaming }.dropLast(1)
        )
        if (complete.isEmpty()) return ""
        val tier = systemPromptProvider.tierFor(inferenceEngine.activeModelName)
        if (tier == SystemPromptProvider.ModelTier.COMPACT) {
            // No recap for the 1B. Quoting the prior user message verbatim made
            // it parrot the user back ("You're doing good!" after "I am doing
            // good") — see user-reported transcript. The 1B's multi-turn
            // coherence is weak with or without this hint; without it each turn
            // is independent and clean. STANDARD/LARGE recap is untouched.
            return ""
        }
        // STANDARD / LARGE — give the model continuity in a BOUNDED size.
        //
        // Tier-aware budget. STANDARD (Gemma 3n) stays at RECAP_MAX_CHARS=280 —
        // a larger window pushed fill past 95% and triggered the repetition loops
        // observed in production (19:37:30, 19:42:01). LARGE (Gemma 4) has an
        // 8000c prompt budget; giving it 500c here is still only 6% of that
        // ceiling and allows 3–4 meaningful questions instead of barely 1.
        val recapBudget = if (tier == SystemPromptProvider.ModelTier.LARGE) 500 else RECAP_MAX_CHARS
        // 85c truncates most real questions mid-sentence. 120c preserves the
        // full text of the vast majority of queries, including Hindi/Telugu ones
        // where the same semantic content is expressed in fewer code units.
        // STANDARD stays at 85c — its tight 4900c budget leaves no headroom.
        val perMsgChars = if (tier == SystemPromptProvider.ModelTier.LARGE) 120 else 85
        // LARGE has budget for one extra tail turn — gives deeper follow-ups
        // ("explain point 3 from earlier") a better anchor.
        val tailCount   = if (tier == SystemPromptProvider.ModelTier.LARGE) 3   else 2

        val allUserMessages = complete.filter { it.role == MessageRole.USER }
        if (allUserMessages.isEmpty()) return ""

        // Origin question + the most-recent tail — deduped, in chronological order.
        val picked = LinkedHashSet<String>()
        allUserMessages.firstOrNull()?.let { picked.add(it.content) }
        allUserMessages.takeLast(tailCount).forEach { picked.add(it.content) }

        val lines = picked.mapNotNull { raw ->
            val sanitized = ResponseMarkerParser.stripForDisplay(raw, streaming = false).trim()
            if (sanitized.isEmpty()) null
            else {
                val body = sanitized.take(perMsgChars).let { if (sanitized.length > perMsgChars) "$it…" else it }
                "- \"$body\""
            }
        }
        if (lines.isEmpty()) return ""

        // Every "I"/"मैं"/"నేను"/"நான்" etc. in the quoted lines = the USER,
        // not the model. Explicit label prevents pronoun-confusion replies
        // ("I am also a teacher") seen across non-English sessions.
        val header = "User's earlier words (all 'I'/'मैं'/'నేను'/'நான்'/'আমি'/'ਮੈਂ' in quotes = the USER, not you — answer the new message below, don't restate prior replies):\n"
        // Hard cap: drop oldest lines until under budget.
        val mutableLines = lines.toMutableList()
        while (mutableLines.size > 1 && (header.length + mutableLines.sumOf { it.length + 1 }) > recapBudget) {
            mutableLines.removeAt(0)
        }
        return (header + mutableLines.joinToString("\n")).trimEnd()
    }

    /**
     * Multi-turn conversation context for STANDARD / LARGE tiers.
     *
     * Unlike [buildPriorTurnsRecap] (user QUESTIONS only), this carries
     * Saarthi's PRIOR ANSWERS too — bounded and clearly framed as reference
     * context. That is what lets follow-ups resolve against what was actually
     * said, instead of the model restarting cold every turn. Because the KV
     * cache is recycled per turn on this hardware, the prompt is the only
     * carrier of continuity, so the answers MUST be re-supplied here.
     *
     * Anti-echo design (the reason assistant turns were originally dropped):
     *  • The current user message is appended OUTSIDE this block (last), so the
     *    model continues from the NEW question, not a dangling assistant turn.
     *  • Assistant turns are truncated → a partial echo is bounded and the text
     *    reads as a compressed note, not a script to reproduce.
     *  • Labels are unambiguous ("User:" = the human, "Saarthi:" = itself); the
     *    earlier regression used "You:" for the assistant, which it continued.
     *  • The header explicitly forbids repeating the block.
     *
     * COMPACT (1B) returns "" — it parrots any transcript regardless of framing.
     */
    private fun buildConversationContext(grounded: Boolean): String {
        val tier = systemPromptProvider.tierFor(inferenceEngine.activeModelName)
        if (tier == SystemPromptProvider.ModelTier.COMPACT) return ""

        val flat = buildCompleteHistoryPairs(
            _history.value.filter { it.content.isNotBlank() && !it.isStreaming }.dropLast(1)
        )
        if (flat.size < 2) return ""

        // Group the flat [U,A,U,A,…] list into (user, assistant) turns, marker
        // tags stripped so [SAARTHI_MEMORY]/[SAARTHI_REMINDER] never leak back in.
        val turns = ArrayList<Pair<String, String>>(flat.size / 2)
        var i = 0
        while (i + 1 < flat.size) {
            val u = ResponseMarkerParser.stripForDisplay(flat[i].content, streaming = false).trim()
            val a = ResponseMarkerParser.stripForDisplay(flat[i + 1].content, streaming = false).trim()
            turns.add(u to a)
            i += 2
        }

        return formatConversationContext(
            turns = turns,
            isLarge = tier == SystemPromptProvider.ModelTier.LARGE,
            grounded = grounded,
        )
    }

    /**
     * Persist one memory fact, routing it to the correct tier:
     *  • durable identity facts (name, city, profession, …) → USER_SCOPE so
     *    they follow the user into every future chat (cross-session profile);
     *  • everything else → the supplied [sessionId] (per-chat context).
     * Centralised so marker-based and implicit extraction share one policy.
     */
    private suspend fun persistMemoryFact(sessionId: String, rawKey: String, value: String) {
        val key = rawKey.trim().lowercase().replace(" ", "_")
        val v = value.trim()
        if (key.isBlank() || v.isBlank()) return
        val target =
            if (MemoryRepository.isUserScopedKey(key)) MemoryRepository.USER_SCOPE else sessionId
        memoryRepository.set(sessionId = target, key = key, value = v, packSource = "USER")
    }

    /**
     * Conservative implicit fact extraction from the USER's own message.
     *
     * Small on-device models frequently fail to emit the [SAARTHI_MEMORY]
     * marker even when the user clearly states a stable fact, so memory never
     * fills. ChatGPT / Gemini extract these heuristically; we mirror that with
     * a SMALL set of high-precision patterns. Precision over recall: a missed
     * fact is harmless (the user can restate), a wrong one is annoying, so the
     * patterns are deliberately strict (anchored, single capture, length-capped).
     *
     * Returns (key, value) pairs; persistence + USER_SCOPE routing is handled
     * by [persistMemoryFact].
     */
    private fun extractImplicitFacts(message: String): List<Pair<String, String>> {
        val msg = message.trim()
        // Raised from 300 to 500 — introductions are commonly embedded inside
        // longer messages ("Hi, my name is Arjun, I'm a farmer from MP, I need
        // help with…") and were being silently skipped at the old limit.
        if (msg.isEmpty() || msg.length > 500) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        // '।' = Devanagari danda (sentence terminator) — strip it like a period.
        fun clean(s: String) = s.trim().trim('.', ',', '!', '"', '\'', '।').trim()
        // Reject captures whose first word is a stopword — "from the office",
        // "called the doctor", "is a bit busy" are not names/places.
        val stopStarts = setOf("the", "a", "an", "my", "this", "that", "here", "there", "not", "just", "so", "very", "really")
        fun firstWordOk(s: String) = s.split(Regex("\\s+")).firstOrNull()?.lowercase() !in stopStarts

        // ── English ──────────────────────────────────────────────────────────
        // name: "my name is X", "I am called X", "call me X"
        Regex("(?i)\\b(?:my name is|i am called|call me|i'm called)\\s+([\\p{L}][\\p{L}\\s.'-]{1,40})")
            .find(msg)?.groupValues?.get(1)?.let { n ->
                val name = clean(n).split(Regex("\\s+")).take(3).joinToString(" ")
                if (name.length in 2..40 && firstWordOk(name)) out += "name" to name
            }
        // profession: "I am a teacher", "I work as an electrician", "I'm an engineer"
        Regex("(?i)\\b(?:i am|i'm|i work as)\\s+(?:a|an)\\s+([\\p{L}][\\p{L}\\s-]{2,30})")
            .find(msg)?.groupValues?.get(1)?.let { p ->
                val prof = clean(p)
                // Guard against "I am a bit tired" style false positives.
                if (prof.length in 3..30 && prof.lowercase() !in NON_PROFESSION_WORDS) out += "profession" to prof
            }
        // location: "I live in X", "I am from X", "I'm based in X"
        Regex("(?i)\\b(?:i live in|i am from|i'm from|i am based in|i'm based in)\\s+([\\p{L}][\\p{L}\\s,'-]{1,40})")
            .find(msg)?.groupValues?.get(1)?.let { c ->
                val city = clean(c).split(Regex("\\s+")).take(3).joinToString(" ")
                if (city.length in 2..40 && firstWordOk(city)) out += "city" to city
            }
        // age: "I am 28 years old", "I'm 28"
        Regex("(?i)\\b(?:i am|i'm)\\s+(\\d{1,3})\\s*(?:years old|yrs old|years|yo)\\b")
            .find(msg)?.groupValues?.get(1)?.let { a ->
                val age = a.toIntOrNull()
                if (age != null && age in 1..120) out += "age" to age.toString()
            }

        // ── Hindi / Devanagari ───────────────────────────────────────────────
        // Only patterns with near-zero false-positive risk are included. These
        // are the top reason "No user memories stored yet" appeared in every
        // session log even after the user clearly stated their name or city.
        //
        // name: "मेरा नाम X है" / "मेरा नाम X" — highest-precision Hindi pattern.
        // \\p{L} matches all Unicode letters including Devanagari correctly.
        Regex("मेरा नाम\\s+([\\p{L}][\\p{L}\\s.'-]{0,38})\\s*(?:है|हैं)?")
            .find(msg)?.groupValues?.get(1)?.let { n ->
                val name = clean(n.trim()).split(Regex("\\s+")).take(3).joinToString(" ")
                if (name.length in 2..40) out += "name" to name
            }
        // city: "मैं X से हूँ" / "मैं X से हूं" — "I am from X"
        Regex("मैं\\s+([\\p{L}][\\p{L}\\s,'-]{1,38})\\s+से\\s+(?:हूँ|हूं)")
            .find(msg)?.groupValues?.get(1)?.let { c ->
                val city = clean(c.trim()).split(Regex("\\s+")).take(3).joinToString(" ")
                if (city.length in 2..40) out += "city" to city
            }
        // city: "मैं X में रहता/रहती हूँ" — "I live in X"
        Regex("मैं\\s+([\\p{L}][\\p{L}\\s,'-]{1,38})\\s+में\\s+(?:रहता|रहती)\\s+(?:हूँ|हूं)")
            .find(msg)?.groupValues?.get(1)?.let { c ->
                val city = clean(c.trim()).split(Regex("\\s+")).take(3).joinToString(" ")
                if (city.length in 2..40) out += "city" to city
            }

        // ── Transliterated Hindi (Latin script) ─────────────────────────────
        // Very common on mobile — users type Hindi words in Roman script.
        // name: "mera naam X hai" / "mera naam X"
        Regex("(?i)\\bmera\\s+naam\\s+([\\p{L}][\\p{L}\\s.'-]{1,40})(?:\\s+hai)?\\b")
            .find(msg)?.groupValues?.get(1)?.let { n ->
                val name = clean(n).split(Regex("\\s+")).take(3).joinToString(" ")
                if (name.length in 2..40 && firstWordOk(name)) out += "name" to name
            }
        // city: "main X se hun/hoon" — "I am from X"
        Regex("(?i)\\bmain\\s+([\\p{L}][\\p{L}\\s,'-]{1,40})\\s+se\\s+(?:hun|hoon|hu)\\b")
            .find(msg)?.groupValues?.get(1)?.let { c ->
                val city = clean(c).split(Regex("\\s+")).take(3).joinToString(" ")
                if (city.length in 2..40 && firstWordOk(city)) out += "city" to city
            }

        // ── Diet / food preference ───────────────────────────────────────────
        // "I'm a vegetarian" → was the exact case in the log that was never
        // captured because the model mis-emitted SAARTHI_REMINDER instead of
        // SAARTHI_MEMORY, and no implicit pattern existed for diet preferences.
        // Pattern requires a first-person verb before the term to avoid catching
        // "find me a vegetarian restaurant" type queries.
        Regex("(?i)\\b(?:i'?m|i am|i eat|i follow|i prefer|i'm a|i am a)\\s+(?:a |an |strictly |purely )?([a-zA-Z-]{3,20})\\b")
            .find(msg)?.groupValues?.get(1)?.let { d ->
                if (d.lowercase() in DIET_TERMS) out += "diet" to d.lowercase()
            }

        // ── Employer ─────────────────────────────────────────────────────────
        // "I work at Infosys", "I'm working for TCS" — distinct from profession
        // (which captures job title; this captures company/organisation name).
        Regex("(?i)\\b(?:i work at|i work for|i'?m working at|i'?m working for|i am working at|i am working for)\\s+([\\p{L}][\\p{L}\\s&.-]{1,30})")
            .find(msg)?.groupValues?.get(1)?.let { e ->
                val employer = clean(e).split(Regex("\\s+")).take(3).joinToString(" ")
                if (employer.length in 2..30 && firstWordOk(employer)) out += "employer" to employer
            }

        return out
    }

    /**
     * True when the user's message contains an explicit ask for a reminder /
     * alert / alarm. Runtime gate before scheduling, so an over-eager model
     * can't fire a notification for a casual topic. Delegates to the pure,
     * unit-tested [ReminderRequestDetector] (see it for the detection rule and
     * why it was broadened — the old narrow list dropped real requests).
     */
    private fun userAskedForReminder(userMessage: String): Boolean =
        ReminderRequestDetector.wasRequested(userMessage)

    // Dietary preference terms matched by the diet extractor above.
    // Kept separate so adding new terms doesn't risk widening the profession regex.
    private val DIET_TERMS = setOf(
        "vegetarian", "vegan", "jain", "eggetarian",
        "non-vegetarian", "nonvegetarian",
    )

    // Words that follow "I am a/an …" but are NOT a profession — guards the
    // implicit profession extractor against "I am a bit tired" style matches.
    private val NON_PROFESSION_WORDS = setOf(
        "bit", "little", "lot", "fan", "big", "huge", "small", "good", "bad",
        "great", "happy", "sad", "tired", "fine", "beginner", "expert", "newbie",
        "student",  // handled as education, not profession
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

    private fun buildSystemPrompt(memoryContext: String, priorTurnsContext: String = "", grounded: Boolean = false): String {
        val modelName = inferenceEngine.activeModelName
        val tier = systemPromptProvider.tierFor(modelName)
        if (memoryContext.isNotEmpty()) {
            val memCount = memoryContext.lines().count { it.startsWith("- ") }
            DebugLogger.log("MEMORY", "Injected $memCount user memory facts into prompt  tier=$tier")
        } else {
            DebugLogger.log("MEMORY", "No user memories stored yet")
        }
        val timeContext = buildTimeContext(currentLanguage)
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
            grounded = grounded,
            maxContextTokens = inferenceEngine.maxContextTokens,
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
    private fun buildTimeContext(language: SupportedLanguage): String {
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
        return "Current local time is $timeStr — it is $band."
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

/**
 * Pure, dependency-free formatter for the multi-turn conversation context block.
 * Extracted as a top-level `internal` function so it is unit-testable without
 * constructing [ChatRepositoryImpl] and its 12 dependencies.
 *
 * @param turns   completed (userText, assistantText) pairs, oldest → newest,
 *                already marker-stripped and trimmed by the caller.
 * @param isLarge true for the LARGE (Gemma 4) tier; false for STANDARD (Gemma 3n).
 *                LARGE carries more thread (8000c budget); STANDARD's tight
 *                ~4900c with-docs budget gets a smaller window to avoid the
 *                high-fill repetition loops.
 * @param grounded true on document-grounded (RAG) turns, where chunks compete
 *                 for the window, so the transcript shrinks further.
 *
 * Returns the formatted block (header + "User:/Saarthi:" lines), or "" when
 * there is nothing to include. The block is sized to fit a tier/grounded budget
 * by dropping the oldest turns; the most recent turn is always kept.
 */
internal fun formatConversationContext(
    turns: List<Pair<String, String>>,
    isLarge: Boolean,
    grounded: Boolean,
): String {
    if (turns.isEmpty()) return ""

    val maxTurns      = if (isLarge) 3 else 2
    val perUserChars  = if (isLarge) 160 else 110
    val perReplyChars = if (isLarge) { if (grounded) 220 else 320 } else { if (grounded) 150 else 200 }
    val blockBudget   = if (isLarge) { if (grounded) 1100 else 1500 } else { if (grounded) 560 else 760 }

    fun trunc(s: String, n: Int): String {
        val c = s.trim()
        return if (c.length > n) c.take(n).trimEnd() + "…" else c
    }

    val header = "Conversation so far (context only — answer the NEW message below and build on this; do not repeat or restate any of it):"

    // Largest recent window of turns that fits the block budget. Always keep at
    // least the most recent turn even if it slightly exceeds (perReplyChars
    // already bounds a single turn).
    var window = maxTurns.coerceAtMost(turns.size)
    while (window >= 1) {
        val lines = ArrayList<String>(window * 2)
        for ((u, a) in turns.takeLast(window)) {
            val uu = trunc(u, perUserChars); if (uu.isNotEmpty()) lines.add("User: $uu")
            val aa = trunc(a, perReplyChars); if (aa.isNotEmpty()) lines.add("Saarthi: $aa")
        }
        if (lines.isEmpty()) return ""
        val block = (header + "\n" + lines.joinToString("\n")).trimEnd()
        if (block.length <= blockBudget || window == 1) return block
        window--
    }
    return ""
}

/**
 * Decide what the assistant bubble should show when a generation finishes,
 * guaranteeing we NEVER render a blank "broken-looking" bubble — the single
 * worst reliability symptom for a paid app.
 *
 * @param cleanedText   the model's reply with markers stripped (may be blank
 *                      when device memory pressure starved generation, or when
 *                      the reply was only a marker / control token).
 * @param isCancelled   the user pressed Stop.
 * @param isError       generation failed (a non-cancellation throwable).
 * @param partialVisible the bubble's current content — holds either the
 *                      streamed-so-far text or the message `.catch` already set.
 *
 * Pure + top-level `internal` so it is unit-testable without the repository.
 */
internal fun resolveAssistantReply(
    cleanedText: String,
    isCancelled: Boolean,
    isError: Boolean,
    partialVisible: String,
): String {
    if (cleanedText.isNotBlank()) return cleanedText
    // No usable generated text below this point.
    if (isError) {
        // Keep whatever .catch surfaced; otherwise a generic, non-scary notice.
        return partialVisible.ifBlank { "Something went wrong generating a reply. Please try again." }
    }
    if (isCancelled) {
        // User stopped — keep any partial text; otherwise a brief note.
        return partialVisible.ifBlank { "Stopped." }
    }
    // Normal completion but the model produced nothing — almost always device
    // memory pressure (2–3 tokens at <1 tok/s in the logs). Give an actionable
    // next step instead of a blank bubble.
    return "I couldn't generate a reply just now. Please try again — if it keeps " +
        "happening on this device, switch to a lighter model in Settings → Models."
}
