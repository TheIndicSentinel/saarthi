package com.saarthi.core.memory.db

import android.database.sqlite.SQLiteDatabase as FrameworkSQLiteDatabase
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase as SqlCipherDatabase
import java.io.File

/**
 * One-shot plaintext → SQLCipher copy for existing `saarthi.db` installs.
 *
 * Uses SQLCipher's `sqlcipher_export` (same sequence as Zetetic's
 * `ImportUnencryptedDatabaseTest`). The plaintext file is only removed after
 * the encrypted copy is in place. Failures leave the original file untouched.
 */
object PlaintextToSqlCipherMigrator {
    private const val TAG = "SQLCIPHER"

    /**
     * @param hexPassphrase 64-char lowercase hex; same UTF-8 bytes Room's
     *   [net.zetetic.database.sqlcipher.SupportOpenHelperFactory] will use.
     * @return true when a migration ran
     */
    fun migrateIfUnencrypted(dbFile: File, hexPassphrase: String): Boolean {
        if (!SqliteFileFormat.isUnencryptedSqlite(dbFile)) return false
        require(hexPassphrase.length == 64) { "SQLCipher passphrase hex must be 64 chars" }

        checkpointPlaintext(dbFile)

        val tmp = File(dbFile.parentFile, dbFile.name + ".encrypting")
        if (tmp.exists() && !tmp.delete()) {
            error("could not clear leftover ${tmp.name}")
        }

        val plaintext = SqlCipherDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SqlCipherDatabase.OPEN_READWRITE,
        )
        val userVersion: Int
        try {
            plaintext.execSQL(
                ATTACH_ENCRYPTED,
                arrayOf<Any>(tmp.absolutePath, hexPassphrase),
            )
            plaintext.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            plaintext.execSQL("DETACH DATABASE encrypted;")
            userVersion = plaintext.version
        } catch (t: Throwable) {
            plaintext.close()
            tmp.delete()
            throw t
        }
        plaintext.close()

        if (!tmp.isFile || tmp.length() == 0L) {
            tmp.delete()
            error("sqlcipher_export produced no encrypted file")
        }
        if (SqliteFileFormat.isUnencryptedSqlite(tmp)) {
            tmp.delete()
            error("sqlcipher_export left a plaintext file")
        }

        applyUserVersion(tmp, hexPassphrase, userVersion)

        val backup = File(dbFile.parentFile, dbFile.name + ".pre_sqlcipher")
        if (backup.exists() && !backup.delete()) {
            tmp.delete()
            error("could not clear leftover ${backup.name}")
        }
        if (!dbFile.renameTo(backup)) {
            tmp.delete()
            error("could not park plaintext ${dbFile.name}")
        }
        if (!tmp.renameTo(dbFile)) {
            backup.renameTo(dbFile)
            tmp.delete()
            error("could not move encrypted database into place")
        }
        deleteSidecars(dbFile)
        if (!backup.delete()) {
            Log.w(TAG, "encrypted copy is in place; leftover plaintext backup ${backup.name}")
        }
        deleteSidecars(backup)
        Log.i(TAG, "Migrated plaintext Room database to SQLCipher")
        return true
    }

    internal const val ATTACH_ENCRYPTED = "ATTACH DATABASE ? AS encrypted KEY ?;"

    private fun checkpointPlaintext(dbFile: File) {
        val framework = FrameworkSQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            FrameworkSQLiteDatabase.OPEN_READWRITE,
        )
        try {
            framework.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close()
        } finally {
            framework.close()
        }
    }

    private fun applyUserVersion(encrypted: File, hexPassphrase: String, userVersion: Int) {
        val db = SqlCipherDatabase.openDatabase(
            encrypted.absolutePath,
            hexPassphrase,
            null,
            SqlCipherDatabase.OPEN_READWRITE,
            null,
        )
        try {
            if (db.version != userVersion) {
                db.version = userVersion
            }
        } finally {
            db.close()
        }
    }

    private fun deleteSidecars(main: File) {
        File(main.path + "-wal").delete()
        File(main.path + "-shm").delete()
        File(main.path + "-journal").delete()
    }
}
