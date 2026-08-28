package com.saarthi.feature.assistant.domain

import kotlinx.coroutines.flow.Flow
import java.io.File

interface ChatRepository {
    fun streamResponse(userMessage: String, attachments: List<AttachedFile> = emptyList()): Flow<String>
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
    /** Drop Room chunks for one attachment URI in the current session. */
    suspend fun removeIndexedDocument(docUri: String)
}
