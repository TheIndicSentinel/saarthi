package com.saarthi.core.common

import timber.log.Timber

/**
 * Chat/RAG SQLite is unusable for this process (corrupt or disk full).
 * Callers must not wipe the database. Show [userMessage] and leave recovery
 * to a process restart.
 */
class SqliteUnusableException(cause: Throwable) : Exception(USER_MESSAGE, cause) {
    companion object {
        const val USER_MESSAGE = "Restart the app"
    }
}

fun isSqliteUnusable(error: Throwable): Boolean {
    var cur: Throwable? = error
    val seen = HashSet<Throwable>()
    while (cur != null && seen.add(cur)) {
        if (cur is SqliteUnusableException) return true
        val name = cur.javaClass.name
        if (name.endsWith("SQLiteDatabaseCorruptException") ||
            name.endsWith("SQLiteFullException") ||
            name.endsWith("SQLiteDiskIOException") ||
            name.endsWith("SQLiteCantOpenDatabaseException")
        ) {
            return true
        }
        val msg = cur.message.orEmpty().lowercase()
        if (msg.contains("database disk image is malformed") ||
            msg.contains("sqlite_corrupt") ||
            msg.contains("sqlite_full") ||
            msg.contains("disk is full")
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
}

/**
 * Runs [block]; on corrupt/full SQLite, retries once. Still failing →
 * [SqliteUnusableException]. Other errors are not retried.
 */
suspend fun <T> sqliteWriteWithRetry(block: suspend () -> T): T {
    return try {
        block()
    } catch (first: Throwable) {
        if (!isSqliteUnusable(first)) throw first
        Timber.w(first, "SQLite write failed; retrying once")
        try {
            block()
        } catch (second: Throwable) {
            if (isSqliteUnusable(second)) throw SqliteUnusableException(second)
            throw second
        }
    }
}
