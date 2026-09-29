package com.saarthi.feature.assistant.domain

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

interface ChatRepository {
    fun streamResponse(userMessage: String, attachments: List<AttachedFile> = emptyList()): Flow<String>
    /**
     * Collects a [streamResponse] turn on the repository's app-lifetime scope,
     * so leaving the chat screen (which clears the ViewModel) does not cancel
     * generation. The flow must not throw — callers `.catch` first.
     */
    fun launchTurn(turn: Flow<String>): Job
    /** True while a launched turn is running — lets a recreated ViewModel lock its input. */
    fun isGenerating(): StateFlow<Boolean>
    /** Cancels the in-flight turn (native model + collection) and waits for it to finish. */
    suspend fun cancelActiveTurn()
    /**
     * Longest user message the loaded model's prompt budget can hold without
     * trimPrompt cutting the start of it. 0 = budget unknown (don't block).
     */
    fun maxUserMessageChars(): Int
    fun getHistory(): Flow<List<ChatMessage>>
    fun getSessions(): Flow<List<ChatSession>>
    fun getCurrentSessionId(): Flow<String>
    suspend fun createSession(): String
    suspend fun switchSession(sessionId: String)
    suspend fun deleteSession(sessionId: String)
    suspend fun clearHistory()
    /**
     * Settings "Delete all conversations" — wipes EVERY chat session and its
     * cascaded artefacts (messages, session memories, RAG chunks) plus the
     * cross-chat USER_SCOPE profile memory, then starts a fresh empty chat.
     * Distinct from [clearHistory], which only clears the current session.
     */
    suspend fun deleteAllData()
    /** Local JSON of chats + memories for Settings export. Never uploads. */
    suspend fun exportAllData(): File
    suspend fun deleteMessage(id: String)
    fun getTokensPerSecond(): Flow<Float>
    /**
     * True when the visible thread is longer than the recap the next prompt
     * will include (compact never recaps; STANDARD/LARGE keep a bounded window).
     * Emits when history or the loaded model changes so the chat UI can show
     * a banner before the next send.
     */
    fun olderMessagesOmitted(): Flow<Boolean>
    /** True when the current chat has saved messages older than the loaded window. */
    fun hasOlderMessages(): Flow<Boolean>
    /** Prepend the previous page of the current chat's saved messages. */
    suspend fun loadOlderMessages()
    /** Drop Room chunks for one attachment URI in the current session. */
    suspend fun removeIndexedDocument(docUri: String)
}
