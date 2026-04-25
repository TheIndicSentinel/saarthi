package com.saarthi.core.inference.di

import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.engine.InferenceEngineSelector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class InferenceModule {

    @Binds
    @Singleton
    abstract fun bindInferenceEngine(impl: InferenceEngineSelector): InferenceEngine
}
