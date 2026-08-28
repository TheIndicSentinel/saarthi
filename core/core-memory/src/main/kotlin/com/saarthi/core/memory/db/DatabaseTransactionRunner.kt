package com.saarthi.core.memory.db

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * Best-effort [VACUUM] after a bulk delete has **committed**.
     *
     * SQLite DELETE only marks pages free — `saarthi.db` keeps its old size
     * (and the deleted chat/RAG/memory bytes as unused pages) until VACUUM.
     * SQLite forbids VACUUM inside a transaction, so callers must invoke this
     * **after** [runInTransaction] returns, never from inside [block].
     *
     * Failures are swallowed: the wipe already committed, and a reclaim miss
     * must not throw into user-visible delete paths or undo that commit.
     * Not for ordinary per-turn inserts.
     */
    suspend fun vacuum() {
        withContext(Dispatchers.IO) {
            runCatching {
                db.openHelper.writableDatabase.execSQL("VACUUM")
            }.getOrDefault(Unit)
        }
    }
}
