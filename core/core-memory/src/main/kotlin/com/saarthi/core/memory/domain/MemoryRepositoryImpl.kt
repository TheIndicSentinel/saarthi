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

        // Friendly label map — makes the context readable for the model
        val labelMap = mapOf(
            "user_name"       to "Name",
            "user_age"        to "Age",
            "user_city"       to "City/Location",
            "user_profession" to "Profession",
            "user_likes"      to "Likes",
            "user_dislikes"   to "Dislikes",
            "user_family"     to "Family",
            "user_goals"      to "Goals",
            "user_language"   to "Preferred language",
            "user_health"     to "Health notes",
            "user_budget"     to "Budget",
        )

        return buildString {
            appendLine("Personal information about the user (use naturally in conversation):")
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
