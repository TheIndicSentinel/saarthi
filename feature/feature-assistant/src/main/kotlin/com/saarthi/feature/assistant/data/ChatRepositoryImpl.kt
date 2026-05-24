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

    // ── RAG persistence ──────────────────────────────────────────────────────
    // Document chunks now live in Room (`rag_chunks`) via [RagDocumentRepository].
    // The old in-memory sessionDocuments map dropped extracted text on every
    // process restart — the user had to re-attach the same PDF after every
    // app swipe. Persistence + per-query BM25 retrieval is the production
    // path. We keep a tiny in-process cache of "have we already indexed this
    // file under this session?" so the per-send DAO upsert is a cheap no-op
    // after the first turn that attached the file.
    private val indexedDocsByUri: MutableMap<String, MutableSet<String>> = mutableMapOf()

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
        ragRepository.deleteForSession(sessionId)
        indexedDocsByUri.remove(sessionId)
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
        ragRepository.deleteForSession(sessionId)
        indexedDocsByUri.remove(sessionId)
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
            // closer to 3.5:1 instead of the usual 4:1 for prose. Drop the
            // ceiling by 30% whenever docs are active so RAG content stays
            // well under the native KV cache cap. The cached `indexedDocsByUri`
            // set is the synchronous gate — populated by streamResponse() the
            // moment an attachment lands, so the budget tightens on the very
            // first send and stays tight for the rest of the session.
            val hasDocs = indexedDocsByUri[_currentSessionId.value]?.isNotEmpty() == true
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
        val sessionId = _currentSessionId.value

        // ── RAG (BM25, persisted) ────────────────────────────────────────
        // The session's indexed chunks live in Room. Score every chunk
        // against this turn's query so each follow-up gets a fresh slice
        // of the document — "give overview", "what tech is needed",
        // "what's the salary" all pull different chunks of the same PDF.
        // Files attached on THIS turn are surfaced as error/unindexable
        // notes (binary, oversize) so the model knows they exist even
        // when there is no text to retrieve.
        val retrieved = runCatching { ragRepository.search(sessionId, userMessage) }.getOrDefault(emptyList())
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
            val identity = "Saarthi is a friendly, private AI assistant made for users in India. " +
                "Saarthi runs entirely on this phone, so every chat stays on the device. " +
                "Saarthi replies in a warm, conversational tone — short, clear, and helpful. " +
                "If Saarthi is not sure of a fact, Saarthi simply says \"I'm not sure about that\" instead of guessing."

            val example =
                "User: Hi, who are you?\n" +
                "Saarthi: Hi! I'm Saarthi, your private AI assistant living right on your phone. How can I help you today?"

            val recap = buildPriorTurnsRecap().let { r -> if (r.isNotBlank()) "\n\n$r" else "" }
            // Compute the budget left for RAG after identity / example /
            // recap / user / scaffolding — pass it to the block builder
            // so chunks are dropped at boundaries rather than sliced
            // mid-text by the final trimPrompt safety net.
            val scaffolding = identity.length + example.length + recap.length +
                userMessage.length + 40   // "\n\n…\n\nUser: …\nSaarthi:" markup
            val ragBudget = (MAX_PROMPT_CHARS_COMPACT - scaffolding - 80).coerceAtLeast(0)
            val fileContext = buildRagPromptBlock(retrieved, unreadableThisTurn, tier, ragBudget)
            val ragPart = if (fileContext.isNotEmpty()) "\n\n$fileContext" else ""
            val fullPrompt = "$identity\n\n$example$recap$ragPart\n\nUser: $userMessage\nSaarthi:"
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
            // When docs are pinned, the RAG chunks ARE the topic context —
            // recap becomes redundant and was eating ~150–250 c that the
            // chunks need. Production log 03:47:37–03:52:25 showed
            // systemChars≈3700 vs ragBudget≈1700: system prompt was 2×
            // larger than the actual evidence the model is supposed to use.
            // Dropping the recap on doc-pinned turns lets one more chunk
            // fit and gives the model a clearer "your only source is the
            // excerpts" signal.
            val docsPinned = retrieved.isNotEmpty() || unreadableThisTurn.isNotEmpty()
            val priorTurns = if (docsPinned) "" else buildPriorTurnsRecap()
            val systemInstructions = buildSystemPrompt(memoryContext, priorTurns)
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
                160                                                   // safety margin
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

        val noMatchLine = "I don't see that directly in your documents"
        val rulesHeader = if (tier == SystemPromptProvider.ModelTier.COMPACT) {
            "Attached excerpts — base your answer on these. Cite [N]. If the exact answer isn't here, say so and summarise the closest related content from the excerpts.\n\n"
        } else {
            // Softer than the previous hard refusal rule + a worked
            // citation example. Gemma 4 follows `[N, p.X]` on its first
            // sentence and then drops to bare `[N]` — the in-prompt
            // example below shows the exact pattern we want repeated so
            // the model has something to mirror instead of inferring.
            "ATTACHED EXCERPTS — base every claim on these excerpts ONLY. " +
                "Cite [N] for every claim (use [N, p.X] when the excerpt shows 'Page X'). " +
                "Repeat the full [N] or [N, p.X] form on EVERY claim — never drop to a bare number later in the reply. " +
                "If the exact answer isn't in the excerpts, begin with \"$noMatchLine\" and then summarise the closest related content from the excerpts with citations. " +
                "Never invent facts that aren't in the excerpts. " +
                "Quote numbers, names, and dates verbatim.\n\n" +
                "Example of the citation style required throughout the reply:\n" +
                "  \"Under [1, p.3], a Data Fiduciary must obtain consent. Significant Data Fiduciaries also appoint a DPO [2, p.4]. Penalties go up to ₹250 crore [3, p.6].\"\n\n"
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
                // Stable provenance per chunk so the model can cite at
                // doc-name + position + page granularity, not just [N].
                //   • OUTLINE_CHUNK_INDEX sentinel (-1) → "outline"
                //   • Page range pulled from inline `--- Page X ---`
                //     markers that extractPdfText injects during OCR.
                val ref = buildString {
                    if (chunk.chunkIndex < 0) {
                        append("outline")
                    } else {
                        append("part ").append(chunk.chunkIndex + 1)
                    }
                    extractPageRange(text)?.let { append(" · ").append(it) }
                }
                val header = "[${i + 1}] ${chunk.docName} · $ref\n"
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
            val lastUser = complete.lastOrNull { it.role == MessageRole.USER } ?: return ""
            return "(Earlier in this chat, the user asked: \"${lastUser.content.take(120)}\")"
        }
        // STANDARD / LARGE — user questions only, three most recent.
        val questionsToKeep = 3
        val perMsgChars = 300
        // Defensive: pass each message through stripForDisplay. Normally clean
        // text is stored (parse().cleanText is called on completion), but
        // crash / truncated-stream paths can leave a half-formed
        // `[SAARTHI_MEMORY key="…"` fragment in history. Without this, that
        // fragment goes back into the next FRESH system prompt and the model
        // may treat it as a fresh marker instruction.
        val recentUserMessages = complete
            .filter { it.role == MessageRole.USER }
            .takeLast(questionsToKeep)
        if (recentUserMessages.isEmpty()) return ""
        return buildString {
            append("Recent questions the user asked earlier in this chat (topic context only — answer the NEW question below in a fresh way, do not restate or quote your previous replies):\n")
            recentUserMessages.forEach { msg ->
                val sanitized = ResponseMarkerParser.stripForDisplay(msg.content, streaming = false)
                val body = sanitized.take(perMsgChars)
                append("- \"$body\"\n")
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
        // English — direct request verbs.
        "remind me", "remind us", "reminder for", "reminder to", "reminder at",
        "set a reminder", "set reminder", "schedule a reminder", "schedule reminder",
        "alert me", "notify me", "ping me",
        "wake me", "wake me up",
        "set an alarm", "set alarm", "alarm at", "alarm for",
        // Hindi (Latin + Devanagari)
        "yaad dila", "yaad rakh", "yaad dilana", "yaad dilao", "yaad karwa",
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
