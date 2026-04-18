package com.saarthi.feature.assistant.domain

import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun streamResponse(userMessage: String, attachments: List<AttachedFile> = emptyList()): Flow<String>
    fun getHistory(): Flow<List<ChatMessage>>
    suspend fun clearHistory()
    suspend fun deleteMessage(id: String)
    fun getTokensPerSecond(): Flow<Float>
}
