package com.saarthi.core.memory.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shared_memory")
data class MemoryEntity(
    @PrimaryKey val key: String,
    val value: String,
    val packSource: String,   // e.g. "MONEY", "KISAN"
    val updatedAt: Long = System.currentTimeMillis(),
)
