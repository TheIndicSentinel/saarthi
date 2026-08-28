package com.saarthi.core.memory.db

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens [SaarthiDatabase] through SQLCipher.
 *
 * New installs create an encrypted `saarthi.db`. Existing plaintext files are
 * exported once via [PlaintextToSqlCipherMigrator] before Room opens.
 */
@Singleton
class SaarthiDatabaseFactory @Inject constructor(
    private val passphraseStore: DatabasePassphraseStore,
) {
    fun create(context: Context): SaarthiDatabase {
        System.loadLibrary("sqlcipher")
        val hexPassphrase = passphraseStore.getOrCreateHexPassphrase()
        PlaintextToSqlCipherMigrator.migrateIfUnencrypted(
            context.getDatabasePath(DB_NAME),
            hexPassphrase,
        )
        val factory = SupportOpenHelperFactory(
            hexPassphrase.toByteArray(StandardCharsets.UTF_8),
        )
        return Room.databaseBuilder(context, SaarthiDatabase::class.java, DB_NAME)
            .openHelperFactory(factory)
            // Real, data-preserving migrations for every shipped schema:
            //   v3 → v4: shared_memory becomes per-chat (sessionId added; PK change).
            //   v4 → v5: adds rag_chunks for persisted document RAG.
            //   v5 → v6: FTS5 index on rag_chunks for large-session prefilter.
            //   v6 → v7: rag_chunks structure metadata columns (Wave 2).
            //   v7 → v8: parentChunkIndex for hierarchical section graph.
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            // Destruction is allowed ONLY from the pre-schema-export internal
            // dev builds (v1/v2). Any other missing migration throws at startup.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2)
            .build()
    }

    companion object {
        const val DB_NAME = "saarthi.db"
    }
}
