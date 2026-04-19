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
        return "What I know about you:\n" +
                entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }
}

private fun MemoryEntity.toDomain() = MemoryEntry(
    key = key,
    value = value,
    packSource = packSource,
    updatedAt = updatedAt,
)
