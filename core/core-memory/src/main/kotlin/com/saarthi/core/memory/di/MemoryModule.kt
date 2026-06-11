package com.saarthi.core.memory.di

import android.content.Context
import androidx.room.Room
import com.saarthi.core.memory.db.ChatSessionDao
import com.saarthi.core.memory.db.ConversationDao
import com.saarthi.core.memory.db.MemoryDao
import com.saarthi.core.memory.db.MIGRATION_3_4
import com.saarthi.core.memory.db.MIGRATION_4_5
import com.saarthi.core.memory.db.RagChunkDao
import com.saarthi.core.memory.db.SaarthiDatabase
import com.saarthi.core.memory.domain.MemoryRepository
import com.saarthi.core.memory.domain.MemoryRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SaarthiDatabase =
        Room.databaseBuilder(context, SaarthiDatabase::class.java, "saarthi.db")
            // Real, data-preserving migrations for every shipped schema:
            //   v3 → v4: shared_memory becomes per-chat (sessionId added; PK change).
            //   v4 → v5: adds rag_chunks for persisted document RAG.
            // These cover the only upgrade paths real users can be on, so chat
            // history, sessions, and memories survive an app update.
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
            // Destruction is allowed ONLY from the pre-schema-export internal dev
            // builds (v1/v2) that no real install should be on. Any OTHER missing
            // migration now throws at startup during testing — forcing us to add
            // a proper migration rather than silently wiping production user data.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2)
            .build()

    @Provides
    fun provideMemoryDao(db: SaarthiDatabase): MemoryDao = db.memoryDao()

    @Provides
    fun provideConversationDao(db: SaarthiDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideChatSessionDao(db: SaarthiDatabase): ChatSessionDao = db.chatSessionDao()

    @Provides
    fun provideRagChunkDao(db: SaarthiDatabase): RagChunkDao = db.ragChunkDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindMemoryRepository(impl: MemoryRepositoryImpl): MemoryRepository
}
