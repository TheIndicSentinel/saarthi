package com.saarthi.core.memory.db

import androidx.sqlite.db.SimpleSQLiteQuery
import javax.inject.Inject
import javax.inject.Singleton

/** FTS5 body-chunk prefilter (P3) — keeps SQLite MATCH SQL in core-memory. */
@Singleton
class RagChunkFtsSearch @Inject constructor(
    private val ragChunkDao: RagChunkDao,
) {
    suspend fun searchContent(sessionId: String, matchQuery: String, limit: Int): List<RagChunkEntity> =
        ragChunkDao.ftsSearchContentRaw(
            SimpleSQLiteQuery(
                """
                SELECT c.* FROM rag_chunks AS c
                INNER JOIN rag_chunks_fts AS fts ON c.id = fts.rowid
                WHERE c.sessionId = ? AND c.chunkIndex >= 0
                  AND (c.chunkRole IS NULL OR c.chunkRole NOT IN ('outline', 'registry', 'prompt_hint'))
                  AND fts MATCH ?
                LIMIT ?
                """.trimIndent(),
                arrayOf<Any>(sessionId, matchQuery, limit),
            ),
        )
}
