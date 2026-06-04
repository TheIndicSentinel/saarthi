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
        val sessionEntries =
            if (sessionId == MemoryRepository.USER_SCOPE) emptyList()
            else dao.getBySession(sessionId)

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
        val labelMap = mapOf(
            "name"            to "Name",
            "user_name"       to "Name",
            "age"             to "Age",
            "user_age"        to "Age",
            "city"            to "City / Location",
            "user_city"       to "City / Location",
            "profession"      to "Profession / Work",
            "user_profession" to "Profession / Work",
            "likes"           to "Things they like",
            "user_likes"      to "Things they like",
            "dislikes"        to "Things they dislike",
            "user_dislikes"   to "Things they dislike",
            "family"          to "Family",
            "user_family"     to "Family",
            "goals"           to "Goals / Aspirations",
            "user_goals"      to "Goals / Aspirations",
            "language"        to "Preferred language",
            "user_language"   to "Preferred language",
            "health"          to "Health notes",
            "user_health"     to "Health notes",
            "budget"          to "Budget",
            "user_budget"     to "Budget",
            "favourite_color" to "Favourite colour",
            "favourite_food"  to "Favourite food",
            "favourite_music" to "Favourite music",
            "hobbies"         to "Hobbies",
            "pet"             to "Pet",
            "birthday"        to "Birthday",
        )

        // Returns bullets only — the chat-scope header lives in
        // SystemPromptProvider so there's a single source of truth for
        // that wording and no duplicate headers in the final prompt.
        return buildString {
            entries.forEach { e ->
                val label = labelMap[e.key] ?: e.key.replace("_", " ").replaceFirstChar { it.uppercase() }
                appendLine("- $label: ${e.value}")
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
