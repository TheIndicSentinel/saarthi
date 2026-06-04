package com.saarthi.feature.assistant.domain

import kotlinx.coroutines.flow.Flow

/** Surfaced to the UI when the model successfully schedules a reminder. */
data class ScheduledReminderInfo(
    /** The reminder subject, e.g. "Take medicine" */
    val text: String,
    /** Human-readable time label, e.g. "in 30 minutes" or "at 6:00 PM" */
    val timeLabel: String,
)

interface ChatRepository {
    fun streamResponse(userMessage: String, attachments: List<AttachedFile> = emptyList()): Flow<String>
    fun getHistory(): Flow<List<ChatMessage>>
    fun getSessions(): Flow<List<ChatSession>>
    fun getCurrentSessionId(): Flow<String>
    suspend fun createSession(): String
    suspend fun switchSession(sessionId: String)
    suspend fun deleteSession(sessionId: String)
    suspend fun clearHistory()
    suspend fun deleteMessage(id: String)
    fun getTokensPerSecond(): Flow<Float>
    /** Emits the most recently scheduled reminder (null = nothing scheduled yet). */
    fun getLastReminder(): Flow<ScheduledReminderInfo?>
}
