package com.saarthi.core.memory.domain

import kotlinx.coroutines.flow.Flow

data class MemoryEntry(
    val sessionId: String,
    val key: String,
    val value: String,
    val packSource: String,
    val updatedAt: Long,
)

/**
 * Per-chat user memory store.
 *
 * Every method that reads or writes memory is scoped to a [sessionId]. New
 * chats start with a clean memory view; deleted chats cascade-clear their
 * memories. This is the contract that prevents the bug class users
 * reported as "states-in-India answer mentions groceries from another
 * chat" — memories live in only one chat at a time and never leak.
 *
 * The cross-session [deleteEverything] and [observeAll] are reserved for
 * settings / admin surfaces only. They must NOT be used from the chat
 * pipeline.
 */
interface MemoryRepository {

    // ── Session-scoped (chat-side calls live here) ───────────────────────

    fun observeBySession(sessionId: String): Flow<List<MemoryEntry>>
    suspend fun get(sessionId: String, key: String): MemoryEntry?
    suspend fun set(sessionId: String, key: String, value: String, packSource: String)
    suspend fun delete(sessionId: String, key: String)
    suspend fun deleteForSession(sessionId: String)

    /** Build the "Facts the USER has shared" block for a chat's system prompt. */
    suspend fun buildContextSummary(sessionId: String): String

    // ── Admin surface (reserved for settings) ────────────────────────────

    fun observeAll(): Flow<List<MemoryEntry>>
    suspend fun deleteEverything()
}
