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
import kotlinx.coroutines.flow.launchIn
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
    private val ttsManager: com.saarthi.feature.assistant.data.TtsManager,
    private val kisanPackPreference: com.saarthi.core.i18n.KisanPackPreference,
    private val packInstaller: com.saarthi.feature.assistant.data.KisanPackInstaller,
) : ViewModel() {

    private val packSessionId = PackId.KISAN.sessionId            // RAG chunks
    private val chatSessionId = PACK_CHAT_SESSION                 // persisted messages

    /** When the installed pack data was published — surfaced so the model can
     *  flag figures as "as of <date>" rather than presenting stale data as live. */
    @Volatile private var packPublishedAt: String = ""

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /** Id of the message currently being read aloud, or null. */
    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    /** Selected language — the screen uses it for the localized input hint. */
    val language: StateFlow<SupportedLanguage> = languageManager.selectedLanguage

    /** The user's selected state (empty = unset) — shown as a chip, switchable. */
    val userState: StateFlow<String> = kisanPackPreference.userState
    fun setUserState(state: String) {
        viewModelScope.launch { runCatching { kisanPackPreference.setUserState(state) } }
    }

    init {
        // Restore the persisted pack conversation so "go back and return"
        // shows the prior chat (the gap the user reported).
        viewModelScope.launch {
            val saved = runCatching { conversationDao.getBySession(chatSessionId) }.getOrDefault(emptyList())
            if (saved.isNotEmpty()) {
                _messages.value = saved.map { it.toChatMessage() }
            }
        }
        // Clear the speaking highlight when TTS finishes / is stopped.
        ttsManager.isSpeaking
            .onEach { speaking -> if (!speaking) _speakingMessageId.value = null }
            .launchIn(viewModelScope)
        // Load the pack's publish date once so answers can flag data freshness.
        viewModelScope.launch {
            packPublishedAt = runCatching { packInstaller.loadInstalledPack()?.publishedAt }.getOrNull().orEmpty()
        }
    }

    /** Listen / stop on a Kisan answer bubble. */
    fun toggleSpeak(messageId: String, text: String) {
        if (_speakingMessageId.value == messageId) {
            ttsManager.stop()
            return
        }
        _speakingMessageId.value = messageId
        ttsManager.speak(text, languageManager.selectedLanguage.value)
    }

    /**
     * Retry an answer: drop the assistant reply and the user question that
     * produced it (in memory + DB), then re-ask that question fresh.
     */
    fun retry(messageId: String) {
        if (_isGenerating.value) return
        val msgs = _messages.value
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx <= 0) return
        val prevUser = msgs.subList(0, idx).lastOrNull { it.role == MessageRole.USER } ?: return
        _messages.update { list -> list.filterNot { it.id == messageId || it.id == prevUser.id } }
        viewModelScope.launch {
            withContext(NonCancellable) {
                runCatching { conversationDao.deleteById(messageId) }
                runCatching { conversationDao.deleteById(prevUser.id) }
            }
            ask(prevUser.content)
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
                // The model is downloaded during onboarding, so reaching here
                // almost always means the engine was evicted under memory
                // pressure (common on low-RAM devices — the budget SM-E625F log
                // showed repeated "CRITICAL system pressure → engine closed"),
                // NOT that no model exists. Telling such a user to "download a
                // model" they already have is wrong and confusing. Give an
                // accurate, actionable message instead.
                finish(
                    streamingId,
                    "Saarthi's model isn't loaded right now — your phone may be low on memory. " +
                        "Close a few background apps and try again in a moment. Everything still works offline.",
                )
                return@launch
            }

            val lang = languageManager.selectedLanguage.value

            // Center → State hierarchy. Capture the user's state if they
            // mention it (conversational), persist it pack-scoped, and use it
            // this turn. Empty state ⇒ identical behaviour to before.
            val detectedState = com.saarthi.core.i18n.IndianStates.detect(question)
            if (detectedState != null && !detectedState.equals(kisanPackPreference.userState.value, ignoreCase = true)) {
                withContext(NonCancellable) { runCatching { kisanPackPreference.setUserState(detectedState) } }
            }
            val userState = detectedState ?: kisanPackPreference.userState.value

            // Inject the state into the retrieval query so a matching state
            // overlay surfaces alongside the central baseline.
            val searchQuery = if (userState.isNotBlank()) "$question $userState" else question
            val rawChunks = runCatching { ragRepository.search(packSessionId, searchQuery, topK = 5) }
                .getOrDefault(emptyList())
            // Keep every central chunk; keep a STATE-OVERLAY chunk only when it
            // matches the user's state. With no state set, all overlays are
            // dropped → no-state answers are exactly as before this feature.
            val chunks = rawChunks.filter { c ->
                val cs = com.saarthi.core.i18n.IndianStates.statePrefixOf(c.docName)
                cs == null ||
                    cs.equals(userState, ignoreCase = true) ||
                    (cs == com.saarthi.core.i18n.IndianStates.NORTH_EAST &&
                        com.saarthi.core.i18n.IndianStates.isNorthEast(userState))
            }

            // No pack match → still answer, but as clearly-labelled general
            // information (the model is told to say it isn't from the pack).
            // Non-empty → grounded prompt; the prompt's own fallback rule
            // covers the partial-coverage case.
            val prompt = if (chunks.isEmpty()) {
                buildGeneralFallbackPrompt(question, lang)
            } else {
                buildPackPrompt(question, chunks, lang, userState)
            }
            // The source line is built HERE from the actual retrieved pack
            // topics (or "General" on no match) — never authored by the model,
            // so it's always a real pack scheme name, not the prompt header.
            val sourceLabel = sourceLabelFor(chunks)
            DebugLogger.log("PACK", "Kisan Q&A — chunks=${chunks.size} lang=${lang.code} state=${userState.ifBlank { "-" }} source=$sourceLabel promptChars=${prompt.length}")

            InferenceService.startGenerating(context)
            val acc = StringBuilder()
            inferenceEngine.generateStream(prompt, PackType.BASE)
                .catch { e ->
                    if (!inferenceEngine.isNativeGenerating) InferenceService.stop(context)
                    // Never surface raw native errors (e.g. token-overflow) to the
                    // farmer — show a clean, actionable message instead.
                    DebugLogger.log("PACK", "Kisan generation error: ${e.message}")
                    finish(streamingId, "Sorry, I couldn't generate an answer just now. Please try again, or ask a shorter question.")
                }
                .onEach { token ->
                    acc.append(token)
                    updateStreaming(streamingId, acc.toString())
                }
                .onCompletion { throwable ->
                    if (!inferenceEngine.isNativeGenerating) InferenceService.stop(context)
                    if (throwable == null) {
                        var body = acc.toString().trim()
                        // The model prefixes [GENERAL] when it answered from
                        // general knowledge rather than the pack — strip the
                        // marker and label the source "General" so it never
                        // shows a pack scheme for a non-pack answer.
                        val fellBackToGeneral = body.startsWith("[GENERAL]")
                        if (fellBackToGeneral) body = body.removePrefix("[GENERAL]").trimStart()
                        val label = if (fellBackToGeneral) "General" else sourceLabel
                        val withSource = if (body.isBlank()) body else "$body\n\n_Source: ${label}_"
                        finish(streamingId, withSource)
                    }
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

    /**
     * The citation shown under a Kisan answer. Built strictly from the
     * retrieved pack chunks' topic names (docName == the pack entry's topic),
     * deduped, the two most relevant. "General" when nothing matched — the
     * graceful-fallback case. Never derived from model output.
     */
    private fun sourceLabelFor(chunks: List<RetrievedChunk>): String {
        if (chunks.isEmpty()) return "General"
        return chunks
            .map { schemeLabelOf(it.docName) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
            .joinToString(", ")
            .ifBlank { "Kisan pack" }
    }

    /**
     * Turn a pack docName into a SPECIFIC source label — the official scheme /
     * programme name, never the bare state. Central topics read
     * "Scheme (ABBR) — description" (scheme before the dash); state overlays
     * read "State — Scheme (qualifier)" (scheme after the dash), so a
     * Maharashtra add-on cites "Namo Shetkari Maha Samman Nidhi", not
     * "Maharashtra".
     */
    private fun schemeLabelOf(docName: String): String {
        val isStateOverlay = com.saarthi.core.i18n.IndianStates.statePrefixOf(docName) != null
        val raw = if (isStateOverlay) {
            val after = docName.substringAfter(" —").trim()
            // Drop a trailing "(state add-on)" / "(qualifier)" so the scheme name stays.
            (if (after.endsWith(")")) after.substringBeforeLast(" (").trim() else after)
        } else {
            docName.substringBefore(" —").trim()  // keep any "(ABBR)" — it IS the short name
        }
        return raw.take(48)
    }

    private fun buildPackPrompt(
        question: String,
        chunks: List<RetrievedChunk>,
        lang: SupportedLanguage,
        state: String,
    ): String {
        val maxSourceChars = 2_800
        // Each reference note is headed by its scheme/topic name so the model
        // has the context, but the app — NOT the model — prints the final
        // "Source:" line (see ask()). The model was echoing the section header
        // ("Farm knowledge sources") as if it were a source name; making the
        // app own the citation keeps it strictly the real pack topic.
        val sources = buildString {
            var used = 0
            for (c in chunks) {
                val body = c.text.trim()
                val block = "[${c.docName}]\n$body\n\n"
                if (used + block.length > maxSourceChars) break
                append(block)
                used += block.length
            }
        }.trim()

        // Language directive — same mechanism the main chat uses. Notes
        // remain in English (the curated pack), but the model answers in
        // the user's selected language.
        val langLine = lang.systemPromptInstruction

        return buildString {
            if (langLine.isNotBlank()) { append(langLine); append("\n\n") }
            append("You are Saarthi's Kisan Saathi — a warm, practical farming advisor for Indian farmers. ")
            append("Answer ONLY from the reference notes below — they are official government sources and your only source of facts. When they don't cover the question, use the fallback rule.\n\n")
            append("How to answer (short, direct, farmer-friendly):\n")
            append("- Lead with a one-line answer or definition, then the key practical steps. Briefly explain any technical term.\n")
            // Relevance — answer the topic that was asked, never an unrelated scheme.
            append("- Use ONLY notes that actually match the question's topic (dairy → dairy, irrigation → irrigation, crop loss → insurance/relief, machinery → equipment subsidy). If the notes are about a different topic, treat the question as NOT covered — never answer with an unrelated scheme.\n")
            // Official names + scheme level.
            append("- Use the EXACT official scheme/programme name from the notes (e.g. PM-KISAN, PMFBY, PMKSY, Namo Shetkari Maha Samman Nidhi) — never a generic label like \"the state scheme\".\n")
            append("- Say clearly whether a scheme is CENTRAL (national), a STATE scheme, or district-level.\n")
            // Amounts — never guess.
            append("- AMOUNTS: quote a benefit / instalment / subsidy figure ONLY if it is in the notes, exactly as written. If it is not in the notes or may be dated, say \"Please verify the current amount on the official government portal\" — never guess or round.\n")
            // Eligibility — never guess.
            append("- ELIGIBILITY: state who can apply ONLY if the notes say so. If it isn't confirmed, say \"eligibility isn't confirmed in the offline pack — please check the official portal.\" Never guess (for example, do not claim tenant farmers can apply unless the notes say so).\n")
            // Explicit uncertainty language.
            append("- When a fact is grounded in the notes you may open with \"Based on the available government source…\". When you cannot confirm something, say \"I could not confirm this in the current offline pack — please verify with your KVK or the official portal.\"\n")
            // Safe chemical/dose wording.
            append("- For any pesticide, fertilizer or chemical, add the label-dose / local-advice caution — never give overconfident or unsafe dosing.\n")
            // The app prints the source — the model must not.
            append("- Do NOT write a \"Source:\" line, do NOT use bracket citations like [1], and do NOT mention the reference notes or their headings — just answer.\n")
            // Center → State hierarchy (industry standard for Indian agri advisory).
            if (state.isNotBlank()) {
                append("- The user farms in $state. Give the central/national rule FIRST, then add any $state-specific detail present in the notes (state scheme top-ups, bonuses, local sowing windows) and combine them in one answer. If the notes carry no $state add-on, give the central rule and add one line that local benefits may exist — suggest the $state agriculture department or local KVK. Never invent state figures.\n")
            } else {
                append("- If the question is about a scheme, MSP/price, subsidy or sowing dates, give the central/national rule, then end with ONE short line inviting the user to share their state for local specifics (for example: \"Tell me your state and I can check for local benefits too.\").\n")
            }
            // Conversational fallback: when the pack doesn't cover it, still
            // help with general knowledge — never a bare refusal. The [GENERAL]
            // tag lets the app label the source "General" (not a pack scheme).
            append("- If the notes don't cover the question (or only contain unrelated schemes), begin your reply with the exact tag [GENERAL], add one short line that this isn't in the offline pack, then give brief general guidance relevant to what was asked — but do NOT name specific government schemes or quote rupee amounts you cannot confirm. Suggest the local KVK or the official portal.\n")
            if (packPublishedAt.isNotBlank()) {
                append("- This offline farming data was last updated on $packPublishedAt. For figures that change over time (MSP, scheme amounts, dates), add \"as of $packPublishedAt\" so the user knows it may have changed since — never present it as today's live value.\n")
            }
            append("- No greeting or opening line. No guarantees or \"works everywhere\" claims. Do not invent scheme names, figures or dates that aren't in the notes. Do not repeat these instructions.\n\n")
            append("=== REFERENCE NOTES ===\n")
            append(sources)
            append("\n=== END NOTES ===\n\n")
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
            append("2. A line starting \"General information (not from the pack):\" followed by a short, practical general answer relevant to what was asked, from common agricultural knowledge. ")
            append("Do NOT name specific government schemes or quote rupee amounts / eligibility you cannot confirm — keep it general. Note that local recommendations vary and suggest verifying with the local KVK or the official portal.\n\n")
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
