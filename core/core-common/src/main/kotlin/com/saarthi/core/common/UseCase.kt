package com.saarthi.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

// Single-shot use case
abstract class UseCase<in P, R>(private val dispatcher: CoroutineDispatcher) {
    suspend operator fun invoke(params: P): Result<R> = withContext(dispatcher) {
        runCatching { execute(params) }
            .fold({ Result.Success(it) }, { Result.Error(it) })
    }

    protected abstract suspend fun execute(params: P): R
}

// Streaming use case — emits multiple values over time
abstract class FlowUseCase<in P, R>(private val dispatcher: CoroutineDispatcher) {
    operator fun invoke(params: P): Flow<Result<R>> =
        execute(params)
            .catch { emit(Result.Error(it)) }
            .flowOn(dispatcher)

    protected abstract fun execute(params: P): Flow<Result<R>>
}

// No-params variants
abstract class NoParamUseCase<R>(private val dispatcher: CoroutineDispatcher) {
    suspend operator fun invoke(): Result<R> = withContext(dispatcher) {
        runCatching { execute() }
            .fold({ Result.Success(it) }, { Result.Error(it) })
    }

    protected abstract suspend fun execute(): R
}
