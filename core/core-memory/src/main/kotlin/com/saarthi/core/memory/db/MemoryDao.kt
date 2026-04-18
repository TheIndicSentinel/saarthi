package com.saarthi.core.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM shared_memory ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM shared_memory WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemoryEntity)

    @Query("DELETE FROM shared_memory WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM shared_memory WHERE packSource = :pack")
    fun observeByPack(pack: String): Flow<List<MemoryEntity>>
}
