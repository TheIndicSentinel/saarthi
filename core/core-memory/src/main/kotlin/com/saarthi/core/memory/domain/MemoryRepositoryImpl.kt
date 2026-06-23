package com.saarthi.core.memory.domain

import com.saarthi.core.memory.db.MemoryDao
import com.saarthi.core.memory.db.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MemoryRepositoryImpl @Inject constructor(
    private val dao: MemoryDao,
) : MemoryRepository {

    override fun observeBySession(sessionId: String): Flow<List<MemoryEntry>> =
        dao.observeBySession(sessionId).map { it.map(MemoryEntity::toDomain) }

    override suspend fun get(sessionId: String, key: String): MemoryEntry? =
        dao.getInSession(sessionId, key)?.toDomain()

    override suspend fun set(sessionId: String, key: String, value: String, packSource: String) {
        dao.upsert(
            MemoryEntity(
                sessionId = sessionId,
                key = key,
                value = value,
                packSource = packSource,
            ),
        )
    }

    override suspend fun delete(sessionId: String, key: String) =
        dao.deleteInSession(sessionId, key)

    override suspend fun deleteForSession(sessionId: String) =
        dao.deleteAllInSession(sessionId)

    override suspend fun buildContextSummary(sessionId: String): String {
        // Two tiers, industry-standard:
        //   1. USER_SCOPE — durable identity facts that follow the user across
        //      every chat (name, profession, city, …). Rendered first so the
        //      model sees stable profile context before chat-specific notes.
        //   2. this session — conversational facts scoped to THIS chat only.
        // A session-scoped fact with the same key OVERRIDES the global one
        // (the user may have corrected it inside this chat). USER_SCOPE itself
        // never reads a second tier.
        val userEntries = dao.getBySession(MemoryRepository.USER_SCOPE)
        // Session-scoped conversational facts go stale; drop ones not touched in
        // SESSION_FACT_TTL_MS so the prompt isn't bloated by months-old context.
        // USER_SCOPE identity facts are durable and never aged out here.
        val staleCutoff = System.currentTimeMillis() - MemoryRepository.SESSION_FACT_TTL_MS
        val sessionEntries =
            if (sessionId == MemoryRepository.USER_SCOPE) emptyList()
            else dao.getBySession(sessionId).filter { it.updatedAt >= staleCutoff }

        // Merge preserving insertion order: global identity first, then
        // session facts; same-key session fact replaces the global one in place.
        val merged = LinkedHashMap<String, MemoryEntity>()
        userEntries.forEach { merged[it.key] = it }
        sessionEntries.forEach { e ->
            if (merged.containsKey(e.key)) merged[e.key] = e   // override, keep position
            else merged[e.key] = e
        }
        val entries = merged.values.toList()
        if (entries.isEmpty()) return ""

        // Friendly labels for keys the model commonly emits. Any unmapped key
        // is humanised (snake_case → Title case) so the LLM still gets a
        // readable line rather than a raw slug.
        // All labels are prefixed "User's …" so the model never mistakes
        // these stored facts for attributes of itself — the root cause of
        // the pronoun-confusion bug ("my name is Arjun" → model says "I am
        // Arjun" in subsequent turns, especially in non-English languages).
        val labelMap = mapOf(
            "name"            to "User's name",
            "user_name"       to "User's name",
            "age"             to "User's age",
            "user_age"        to "User's age",
            "city"            to "User's city / location",
            "user_city"       to "User's city / location",
            "profession"      to "User's profession / work",
            "user_profession" to "User's profession / work",
            "likes"           to "Things the user likes",
            "user_likes"      to "Things the user likes",
            "dislikes"        to "Things the user dislikes",
            "user_dislikes"   to "Things the user dislikes",
            "family"          to "User's family",
            "user_family"     to "User's family",
            "goals"           to "User's goals / aspirations",
            "user_goals"      to "User's goals / aspirations",
            "language"        to "User's preferred language",
            "user_language"   to "User's preferred language",
            "health"          to "User's health notes",
            "user_health"     to "User's health notes",
            "budget"          to "User's budget",
            "user_budget"     to "User's budget",
            "favourite_color" to "User's favourite colour",
            "favourite_food"  to "User's favourite food",
            "favourite_music" to "User's favourite music",
            "hobbies"         to "User's hobbies",
            "diet"            to "User's dietary preference",
            "employer"        to "User's employer / company",
            "pet"             to "User's pet",
            "birthday"        to "User's birthday",
        )

        // Returns bullets only — the chat-scope header lives in
        // SystemPromptProvider so there's a single source of truth for
        // that wording and no duplicate headers in the final prompt.
        // Assemble lines up to SUMMARY_MAX_CHARS — USER_SCOPE identity is first
        // (see merge order above) so it survives; lower-priority/overflow lines
        // are dropped rather than truncated mid-fact. Caps prompt bloat over time.
        return buildString {
            for (e in entries) {
                val label = labelMap[e.key]
                    ?: "User's " + e.key.replace("_", " ").replaceFirstChar { it.uppercase() }
                val line = "- $label: ${e.value}"
                if (length + line.length + 1 > MemoryRepository.SUMMARY_MAX_CHARS) break
                appendLine(line)
            }
        }.trimEnd()
    }

    // ── Admin surface ────────────────────────────────────────────────────

    override fun observeAll(): Flow<List<MemoryEntry>> =
        dao.observeAll().map { it.map(MemoryEntity::toDomain) }

    override suspend fun deleteEverything() = dao.deleteEverything()
}

private fun MemoryEntity.toDomain() = MemoryEntry(
    sessionId = sessionId,
    key = key,
    value = value,
    packSource = packSource,
    updatedAt = updatedAt,
)
