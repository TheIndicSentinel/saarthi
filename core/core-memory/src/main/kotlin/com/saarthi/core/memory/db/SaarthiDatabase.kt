package com.saarthi.core.memory.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MemoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SaarthiDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
}
