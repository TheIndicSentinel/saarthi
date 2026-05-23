package com.saarthi.core.memory.di

import android.content.Context
import androidx.room.Room
import com.saarthi.core.memory.db.ChatSessionDao
import com.saarthi.core.memory.db.ConversationDao
import com.saarthi.core.memory.db.MemoryDao
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
            // Real migration for v4 → v5 (adds rag_chunks). Without this,
            // fallbackToDestructiveMigration below would wipe chat history
            // on upgrade — bad UX for an offline assistant.
            .addMigrations(MIGRATION_4_5)
            // Last-resort safety net for unforeseen schema drift; the real
            // migration takes priority and runs first.
            .fallbackToDestructiveMigration(dropAllTables = true)
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
