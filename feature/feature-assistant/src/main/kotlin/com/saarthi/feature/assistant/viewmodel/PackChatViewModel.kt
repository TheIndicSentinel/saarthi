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
import com.saarthi.core.inference.prompt.SystemPromptProvider
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
    private val systemPromptProvider: SystemPromptProvider,
) : ViewModel() {

    private val packSessionId = PackId.KISAN.sessionId            // RAG chunks
    private val chatSessionId = PACK_CHAT_SESSION                 // persisted messages

    /** When the installed pack data was published — surfaced so the model can
     *  flag figures as "as of <date>" rather than presenting stale data as live. */
    @Volatile private var packPublishedAt: String = ""
    /** Installed pack version — surfaced in the freshness footer + log so data
     *  provenance is auditable (which pack snapshot produced this answer). */
    @Volatile private var packVersion: Int = 0

    /** Official MSP values from the signed pack. Used to GROUND MSP answers in
     *  the exact table (the LLM then renders in the user's language). */
    @Volatile private var mspRecords: List<com.saarthi.feature.assistant.data.KisanPackInstaller.MspRecord> = emptyList()

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
        // Load the pack's publish date + version once so answers + logs can show
        // data freshness/provenance (which snapshot this answer came from).
        viewModelScope.launch {
            val installed = runCatching { packInstaller.loadInstalledPack() }.getOrNull()
            packPublishedAt = installed?.publishedAt.orEmpty()
            packVersion = installed?.version ?: 0
            mspRecords = runCatching { packInstaller.loadMspRecords() }.getOrDefault(emptyList())
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
                finish(streamingId, languageManager.selectedLanguage.value.packModelNotLoaded)
                return@launch
            }

            // Capability gate (defense behind the pack screen's UI gate). The
            // compact 1B model cannot follow grounded RAG instructions across a
            // turn boundary — it loops / repeats on the second answer (see the
            // "[REP] Loop detected" device logs). This engine is shared by every
            // knowledge pack, so the gate covers all of them, not just Kisan.
            // Rather than emit garbage, pack chat is browse-only on this tier and
            // blocked with a clear, honest message. Reached only if the user
            // switched to the compact model AFTER opening the chat — rare, but it
            // must degrade gracefully.
            if (!systemPromptProvider.supportsPackChat(inferenceEngine.activeModelName)) {
                finish(streamingId, languageManager.selectedLanguage.value.packModelTooSmall)
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

            // MSP is critical structured data. When the user asks about MSP,
            // ground the answer in the FULL official MSP table (exact values from
            // the signed pack) so the right crop + value are always present —
            // BM25 alone can't match a Marathi/Telugu query against 26 near-
            // identical English MSP entries. The LLM then renders it in the
            // selected language. Non-MSP questions use normal BM25 retrieval.
            val mspGrounding = mspGroundingIfAsked(question)
            val chunks = if (mspGrounding != null) {
                DebugLogger.log("PACK", "MSP grounded (official table → LLM renders in lang)  packV=$packVersion crops=${mspRecords.size}")
                mspGrounding
            } else {
                val searchQuery = if (userState.isNotBlank()) "$question $userState" else question
                val rawChunks = runCatching { ragRepository.search(packSessionId, searchQuery, topK = 5) }
                    .getOrDefault(emptyList())
                rawChunks
                    // Drop STRUCTURAL PADDING. search() pads to topK with score=0.0
                    // structural samples (arbitrary chunks) when BM25 under-covers —
                    // useful for reasoning over a user's OWN document, but for the
                    // curated Kisan pack it means an off-topic question still gets
                    // "reference notes" and the model hallucinates a scheme answer.
                    // Only REAL lexical matches (score > 0) count as pack grounding;
                    // if none remain, chunks is empty ⇒ the clean general fallback
                    // below runs (deterministic "not in pack", not model-guessed).
                    .filter { it.score > MIN_PACK_RELEVANCE }
                    // Keep every central chunk; keep a STATE-OVERLAY chunk only when
                    // it matches the user's state.
                    .filter { c ->
                        val cs = com.saarthi.core.i18n.IndianStates.statePrefixOf(c.docName)
                        cs == null ||
                            cs.equals(userState, ignoreCase = true) ||
                            (cs == com.saarthi.core.i18n.IndianStates.NORTH_EAST &&
                                com.saarthi.core.i18n.IndianStates.isNorthEast(userState))
                    }
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
            val topScore = chunks.maxOfOrNull { it.score } ?: 0.0
            DebugLogger.log("PACK", "Kisan Q&A — packV=$packVersion asOf=${freshnessDate().ifBlank { "?" }} chunks=${chunks.size} topScore=${"%.2f".format(topScore)} grounded=${chunks.isNotEmpty()} lang=${lang.code} state=${userState.ifBlank { "-" }} source=$sourceLabel promptChars=${prompt.length}")

            InferenceService.startGenerating(context)
            val acc = StringBuilder()
            inferenceEngine.generateStream(prompt, PackType.BASE)
                .catch { e ->
                    if (!inferenceEngine.isNativeGenerating) InferenceService.stop(context)
                    // Never surface raw native errors (e.g. token-overflow) to the
                    // farmer — show a clean, actionable message instead.
                    DebugLogger.log("PACK", "Kisan generation error: ${e.message}")
                    finish(streamingId, lang.packGenerationError)
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
                        // Freshness footer: which pack snapshot + as-of date this
                        // answer came from, so the user can judge how current it is.
                        val asOf = freshnessDate().takeIf { it.isNotBlank() }?.let { " · as of $it" }.orEmpty()
                        val withSource = if (body.isBlank()) body else "$body\n\n_Source: ${label}${asOf}_"
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
    /** Pack publish date as a plain YYYY-MM-DD (or "" if unknown). */
    private fun freshnessDate(): String = packPublishedAt.take(10)

    // MSP intent across the supported languages. "msp" (Latin) is the safest
    // catch-all; the rest are best-effort — a miss just falls back to BM25.
    private val mspTriggers = listOf(
        "msp", "minimum support price", "support price", "samarthan",
        "समर्थन मूल्य", "एमएसपी",
        "हमीभाव", "हमी भाव",
        "మద్దతు ధర",
        "ஆதரவு விலை",
        "সহায়ক মূল্য",
        "ಬೆಂಬಲ ಬೆಲೆ",
        "ટેકાનો ભાવ",
        "ਸਮਰਥਨ ਮੁੱਲ",
    )

    /**
     * If the question is about MSP, return the FULL official MSP table as a
     * single grounding chunk (exact values, verbatim from the signed pack). The
     * normal LLM path then answers the specific crop in the user's language.
     * Returns null when it isn't an MSP question (then BM25 runs as usual).
     * This sidesteps BM25's cross-lingual miss on 26 near-identical MSP entries.
     */
    private fun mspGroundingIfAsked(question: String): List<RetrievedChunk>? {
        if (mspRecords.isEmpty()) return null
        val q = question.lowercase()
        if (mspTriggers.none { q.contains(it.lowercase()) }) return null
        val table = mspRecords.joinToString("\n") { r ->
            "${r.crop} (${r.season} ${r.marketingYear}): Rs ${r.value} per quintal"
        }
        val src = mspRecords.firstOrNull()?.sourceDocument.orEmpty()
        val text = "Official Minimum Support Price (MSP), per quintal:\n$table\nSource: $src"
        return listOf(RetrievedChunk(text = text, docName = "Minimum Support Price (MSP)", score = 1.0, chunkIndex = 0))
    }

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
        // Language directive — same mechanism the main chat uses. Notes
        // remain in English (the curated pack), but the model answers in
        // the user's selected language.
        val langLine = lang.systemPromptInstruction

        // NOTE: the COMPACT (Gemma 1B) tier never reaches here — ask() blocks the
        // Kisan chat on that tier. Only STANDARD / LARGE build the advisor prompt.
        //
        // Instruction block — condensed (~1.5k chars) so the whole prompt still
        // fits when the model loads at a 1536-token window (low RAM / thermal).
        // Every load-bearing rule is preserved: answer only from notes, exact
        // scheme names + level, never-guess amounts/eligibility, chemical-dose
        // caution, no source line, central→state hierarchy, [GENERAL] fallback.
        val instructions = buildString {
            append("You are Saarthi's Kisan Saathi — a warm, practical farming advisor for Indian farmers. ")
            append("Answer ONLY from the reference notes below (official government sources). If they don't cover the question, follow the fallback rule.\n")
            append("- Lead with a one-line answer, then the key practical steps; briefly explain any technical term.\n")
            append("- Use ONLY notes matching the question's topic — if the notes are about a different topic, treat it as NOT covered; never answer with an unrelated scheme.\n")
            append("- Use the EXACT official scheme name from the notes (PM-KISAN, PMFBY, PMKSY, Namo Shetkari…) and say whether it is CENTRAL, STATE, or district-level.\n")
            append("- AMOUNTS & ELIGIBILITY: quote ONLY what the notes state, exactly as written. If not in the notes (or possibly dated), say to verify on the official government portal — never guess or round.\n")
            append("- For any pesticide / fertilizer / chemical, add the label-dose caution — never give unsafe dosing.\n")
            append("- Do NOT write a \"Source:\" line, bracket citations like [1], or mention the notes / their headings — just answer.\n")
            if (state.isNotBlank()) {
                append("- The user farms in $state: give the central/national rule FIRST, then any $state-specific detail in the notes; if none, say local benefits may exist and suggest the $state agriculture department or local KVK. Never invent state figures.\n")
            } else {
                append("- For a scheme / MSP / subsidy / sowing question, give the central rule, then add ONE short line inviting the user to share their state for local specifics.\n")
            }
            append("- If the notes don't cover it (or only carry unrelated schemes), begin your reply with the exact tag [GENERAL], say it isn't in the offline pack, then give brief general guidance — no specific scheme names or unconfirmed rupee amounts; suggest the local KVK or official portal.\n")
            if (packPublishedAt.isNotBlank()) {
                append("- Data last updated $packPublishedAt; for figures that change (MSP, amounts, dates) add \"as of $packPublishedAt\" — never present it as today's live value.\n")
            }
            append("- No greeting. Don't invent scheme names, figures or dates. Don't repeat these instructions.\n")
        }

        // Token-aware source budget — the real fix for the "Kisan generates no
        // response" failure. The model can load at a 1536-token window on
        // low-RAM/thermal devices; a fixed 2800c source block then pushed the
        // prompt past the window and EVERY question failed with "Input token ids
        // are too long". Size the notes to whatever the live window allows.
        val tokenWindow = inferenceEngine.maxContextTokens.takeIf { it > 0 } ?: 2048
        val charBudget = ((tokenWindow - 256 - 16) * 3.0).toInt()   // mirror ChatRepositoryImpl
        val scaffold = instructions.length + langLine.length * 2 + question.length + 80
        val maxSourceChars = (charBudget - scaffold).coerceAtLeast(500)

        // Each reference note is headed by its scheme/topic name; the app — NOT
        // the model — prints the final "Source:" line (see ask()).
        val sources = buildString {
            var used = 0
            for (c in chunks) {
                val remaining = maxSourceChars - used
                if (remaining < 200) break
                val header = "[${c.docName}]\n"
                val body = c.text.trim()
                val block = "$header$body\n\n"
                if (block.length <= remaining) {
                    append(block); used += block.length
                } else {
                    // Truncate this chunk to fit rather than drop it entirely —
                    // keeps the MSP table / key scheme present at a tight window.
                    val room = remaining - header.length - 2
                    if (room > 120) { append(header); append(body.take(room)); append("\n\n") }
                    break
                }
            }
        }.trim()

        return buildString {
            if (langLine.isNotBlank()) { append(langLine); append("\n\n") }
            append(instructions)
            append("\n=== REFERENCE NOTES ===\n")
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

        /**
         * Minimum BM25 score for a retrieved chunk to count as real pack
         * grounding. search() emits score=0.0 structural padding when BM25
         * under-covers; anything at or below this is NOT evidence and is
         * dropped so the answer falls back to clearly-labelled general info
         * instead of hallucinating from unrelated pack chunks. Raise with
         * field data (the [PACK] log now records topScore) if weak single-term
         * matches still slip through.
         */
        private const val MIN_PACK_RELEVANCE = 0.0
    }
}
