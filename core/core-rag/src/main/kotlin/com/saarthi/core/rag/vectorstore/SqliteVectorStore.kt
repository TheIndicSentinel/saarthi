package com.saarthi.core.rag.vectorstore

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.math.sqrt

// SQLite-based cosine-similarity vector store.
// For production, load the sqlite-vss extension (.so) from assets.
class SqliteVectorStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dbName: String = "vectors.db",
) : VectorStore {

    private val db: SQLiteDatabase by lazy {
        val dbFile = File(context.filesDir, dbName)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).also { initSchema(it) }
    }

    private fun initSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                embedding BLOB NOT NULL
            )"""
        )
        Timber.d("VectorStore schema ready")
    }

    override suspend fun insert(text: String, embedding: FloatArray): Long {
        val blob = embedding.toBlob()
        val values = android.content.ContentValues().apply {
            put("text", text)
            put("embedding", blob)
        }
        return db.insertOrThrow("chunks", null, values)
    }

    override suspend fun search(queryEmbedding: FloatArray, topK: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        db.rawQuery("SELECT id, text, embedding FROM chunks", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val text = cursor.getString(1)
                val embBlob = cursor.getBlob(2)
                val emb = embBlob.toFloatArray()
                val score = cosineSimilarity(queryEmbedding, emb)
                results += SearchResult(id, text, score)
            }
        }
        return results.sortedByDescending { it.score }.take(topK)
    }

    override suspend fun deleteAll() = db.execSQL("DELETE FROM chunks")

    private fun FloatArray.toBlob(): ByteArray =
        ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .also { buf -> forEach { buf.putFloat(it) } }.array()

    private fun ByteArray.toFloatArray(): FloatArray =
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
            .let { buf -> FloatArray(size / 4) { buf.getFloat() } }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }
}
