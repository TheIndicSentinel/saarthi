package com.saarthi.core.memory.db

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs [block] inside a single Room transaction on the shared [SaarthiDatabase].
 *
 * Multi-table cascades (e.g. delete a chat → its messages + memories + RAG
 * chunks + the session row) span several DAOs. Without a transaction, a crash
 * or SQLite error partway through leaves orphaned rows (messages gone but RAG
 * chunks remaining, etc.). Wrapping the whole cascade here makes it atomic:
 * either every delete commits or none does.
 *
 * Kept in core-memory so the `room-ktx` `withTransaction` extension and the raw
 * [SaarthiDatabase] handle stay encapsulated here rather than being injected
 * into feature modules. All DAOs come from this same singleton database, so
 * their suspend calls inside [block] enlist in the same transaction.
 */
@Singleton
class DatabaseTransactionRunner @Inject constructor(
    private val db: SaarthiDatabase,
) {
    suspend fun <T> runInTransaction(block: suspend () -> T): T = db.withTransaction(block)
}
