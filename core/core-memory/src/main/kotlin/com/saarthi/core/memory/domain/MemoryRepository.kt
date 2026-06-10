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

    companion object {
        /**
         * Reserved sessionId for STABLE personal facts that should follow the
         * user across every chat — name, age, city, profession, family, etc.
         * This is the industry-standard "user profile" memory tier (ChatGPT /
         * Gemini / Claude): durable identity persists globally, while
         * conversational context stays per-chat.
         *
         * It is NOT a real chat session: it never appears in the session list,
         * is never created by [createSession], and is never cascade-deleted by
         * [deleteForSession] (which only targets a concrete chat id). Only a
         * full settings wipe ([deleteEverything]) clears it.
         *
         * The leading "__" cannot collide with the UUIDs / "default" that real
         * sessions use.
         */
        const val USER_SCOPE = "__user_profile__"

        /**
         * Keys that represent durable identity/profile facts and therefore
         * belong in [USER_SCOPE] (global), not in a single chat. Everything
         * else stays session-scoped — that's what still prevents the
         * "groceries from another chat" bleed for conversational content.
         *
         * Matching is prefix-tolerant: "user_name", "name", "preferred_name"
         * all classify as the "name" identity fact.
         */
        private val IDENTITY_KEY_STEMS = setOf(
            "name", "age", "city", "location", "address",
            "profession", "occupation", "job", "work", "company", "employer",
            "family", "spouse", "children", "kids",
            "language", "goal", "goals", "health", "allergy", "allergies",
            "birthday", "dob", "pet", "hobby", "hobbies",
            "favourite", "favorite", "likes", "dislikes",
            "diet", "food",
            "education", "degree", "student", "school", "college",
        )

        /** True when [key] is a durable identity fact that belongs in [USER_SCOPE]. */
        fun isUserScopedKey(key: String): Boolean {
            val k = key.trim().lowercase().removePrefix("user_").removePrefix("my_")
            return IDENTITY_KEY_STEMS.any { stem -> k == stem || k.startsWith("${stem}_") || k.contains(stem) }
        }
    }
}
