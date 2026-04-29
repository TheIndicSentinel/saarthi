package com.saarthi.core.memory.domain

import com.saarthi.core.memory.db.MemoryDao
import com.saarthi.core.memory.db.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MemoryRepositoryImpl @Inject constructor(
    private val dao: MemoryDao,
) : MemoryRepository {

    override fun observeAll(): Flow<List<MemoryEntry>> =
        dao.observeAll().map { it.map(MemoryEntity::toDomain) }

    override fun observeByPack(pack: String): Flow<List<MemoryEntry>> =
        dao.observeByPack(pack).map { it.map(MemoryEntity::toDomain) }

    override suspend fun get(key: String): MemoryEntry? =
        dao.get(key)?.toDomain()

    override suspend fun set(key: String, value: String, packSource: String) {
        dao.upsert(MemoryEntity(key = key, value = value, packSource = packSource))
    }

    override suspend fun delete(key: String) = dao.delete(key)

    override suspend fun buildContextSummary(): String {
        val entries = dao.getAll()
        if (entries.isEmpty()) return ""

        // Rich label map — keys the user can ask Saarthi to remember
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

        return buildString {
            appendLine("What you know about this user (treat as personal knowledge, use naturally):")
            entries.forEach { e ->
                val label = labelMap[e.key] ?: e.key.replace("_", " ").replaceFirstChar { it.uppercase() }
                appendLine("- $label: ${e.value}")
            }
        }.trimEnd()
    }
}

private fun MemoryEntity.toDomain() = MemoryEntry(
    key = key,
    value = value,
    packSource = packSource,
    updatedAt = updatedAt,
)
