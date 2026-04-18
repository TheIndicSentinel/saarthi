package com.saarthi.core.memory.domain

import kotlinx.coroutines.flow.Flow

data class MemoryEntry(
    val key: String,
    val value: String,
    val packSource: String,
    val updatedAt: Long,
)

// Interface Segregation: only expose what callers need
interface MemoryRepository {
    fun observeAll(): Flow<List<MemoryEntry>>
    fun observeByPack(pack: String): Flow<List<MemoryEntry>>
    suspend fun get(key: String): MemoryEntry?
    suspend fun set(key: String, value: String, packSource: String)
    suspend fun delete(key: String)

    // Build a context summary string for prompt injection
    suspend fun buildContextSummary(): String
}
