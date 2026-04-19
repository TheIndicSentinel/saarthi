package com.saarthi.core.rag.di

import com.saarthi.core.rag.embedding.EmbeddingModel
import com.saarthi.core.rag.embedding.GemmaEmbeddingModel
import com.saarthi.core.rag.vectorstore.SqliteVectorStore
import com.saarthi.core.rag.vectorstore.VectorStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RagModule {

    @Binds
    @Singleton
    abstract fun bindEmbeddingModel(impl: GemmaEmbeddingModel): EmbeddingModel

    @Binds
    @Singleton
    abstract fun bindVectorStore(impl: SqliteVectorStore): VectorStore
}
