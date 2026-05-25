package com.saarthi.feature.assistant.data

import android.content.Context
import com.saarthi.core.i18n.KisanPackPreference
import com.saarthi.core.i18n.PackId
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.memory.db.RagChunkDao
import com.saarthi.core.memory.db.RagChunkEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs / replaces the Kisan knowledge pack.
 *
 * The pack file is plain JSON, served either from the bundled
 * `app/src/main/assets/packs/kisan_seed.json` (shipped with the APK
 * so the feature works the moment the user picks the Kisan persona)
 * or from a manifest-driven download (when a manifest URL is wired
 * up via `BuildConfig.KISAN_PACK_MANIFEST_URL`).
 *
 * Format (one pack file = one snapshot of all entries):
 * ```
 * {
 *   "packId": "kisan",
 *   "version": 1,
 *   "language": "en",
 *   "title": "Kisan Knowledge — Govt of India sources",
 *   "source": "data.gov.in, Agmarknet",
 *   "entries": [
 *     { "topic": "...", "content": "...", "sourceUrl": "...", "tags": ["..."] }
 *   ]
 * }
 * ```
 *
 * Install is idempotent and atomic from the user's perspective:
 *  1. parse the JSON to a list of `RagChunkEntity` first (fail-fast
 *     on malformed input — old pack stays intact).
 *  2. delete the old pack rows by sessionId.
 *  3. insert the new rows in one DAO call.
 *  4. record the new version + language in `KisanPackPreference`.
 *
 * Each pack entry becomes one chunk in the regular `rag_chunks`
 * table under sessionId = `PackId.KISAN.sessionId`. BM25 then ranks
 * it alongside the chat session's own chunks.
 */
@Singleton
class KisanPackInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ragChunkDao: RagChunkDao,
    private val preference: KisanPackPreference,
) {

    /** Install the seed pack bundled in app assets — used on first launch. */
    suspend fun installSeedIfAbsent() = withContext(Dispatchers.IO) {
        if (preference.installedVersion.value > 0) return@withContext
        runCatching {
            context.assets.open(SEED_ASSET_PATH).use { installFrom(it, source = "seed:$SEED_ASSET_PATH") }
        }.onFailure { Timber.w(it, "Kisan seed pack not bundled — skipping") }
    }

    /**
     * Install from an arbitrary InputStream — used by the seed install
     * path AND by [PackUpdateWorker] after a successful HTTP download.
     * Returns the newly-installed pack version, or null if the stream
     * couldn't be parsed (old pack stays intact in that case).
     *
     * Side effect: writes the raw pack JSON to `filesDir/packs/kisan_active.json`
     * so [loadInstalledPack] can read full structured metadata
     * (topic / sourceUrl / tags) for the browsing screen. The RAG side
     * keeps using the chunk table; this file is the UI's source of truth.
     */
    suspend fun installFrom(input: InputStream, source: String): Int? = withContext(Dispatchers.IO) {
        val raw = runCatching { input.bufferedReader(Charsets.UTF_8).readText() }.getOrNull()
            ?: run {
                DebugLogger.log("PACK", "Kisan install failed: stream read error from $source")
                return@withContext null
            }
        val parsed = runCatching { parsePackJson(raw) }.getOrNull()
            ?: run {
                DebugLogger.log("PACK", "Kisan install failed: malformed pack JSON from $source")
                return@withContext null
            }
        val entities = parsed.entries.mapIndexed { idx, entry ->
            RagChunkEntity(
                sessionId  = PackId.KISAN.sessionId,
                docUri     = "pack://kisan/${parsed.version}/${idx}",
                docName    = entry.topic.take(80),
                mimeType   = MIME_PACK,
                chunkIndex = idx,
                text       = entry.toIndexedText(),
            )
        }
        if (entities.isEmpty()) {
            DebugLogger.log("PACK", "Kisan install skipped: pack has 0 entries (source=$source)")
            return@withContext null
        }
        // Atomic swap: drop old pack rows, insert new ones. RagChunkDao
        // has no @Transaction wrapper for these two but the gap is
        // microseconds — even if a search interleaves it'll just hit
        // a slightly-stale corpus for one turn.
        ragChunkDao.deleteBySession(PackId.KISAN.sessionId)
        ragChunkDao.insertAll(entities)
        // Persist the raw pack JSON so the browse screen can render
        // topic + sourceUrl + tags without re-parsing chunks. Failure
        // here is non-fatal: install still succeeds, browsing just
        // falls back to the bundled seed asset on next read.
        runCatching {
            val packsDir = File(context.filesDir, PACKS_DIR).apply { mkdirs() }
            File(packsDir, ACTIVE_PACK_FILE).writeText(raw, Charsets.UTF_8)
        }.onFailure { Timber.w(it, "Failed to persist raw Kisan pack to filesDir") }
        preference.recordInstall(version = parsed.version, language = parsed.language)
        DebugLogger.log(
            "PACK",
            "Kisan pack v${parsed.version} (${parsed.language}, ${entities.size} entries) installed from $source",
        )
        parsed.version
    }

    /**
     * Read back the currently-installed pack with full structured
     * metadata (topic / content / sourceUrl / tags) for the browse
     * screen. Reads from `filesDir/packs/kisan_active.json` written at
     * install time; falls back to the bundled seed asset if the file
     * isn't there yet (e.g. very first launch before
     * [installSeedIfAbsent] has finished).
     *
     * Returns null only when both sources are unavailable / malformed —
     * which in practice means the seed asset was not packaged. The
     * browse screen handles that with an empty-state message.
     */
    suspend fun loadInstalledPack(): InstalledPack? = withContext(Dispatchers.IO) {
        val activeFile = File(context.filesDir, "$PACKS_DIR/$ACTIVE_PACK_FILE")
        val raw = if (activeFile.exists() && activeFile.length() > 0L) {
            runCatching { activeFile.readText(Charsets.UTF_8) }.getOrNull()
        } else null
        val sourceLabel = if (raw != null) "filesDir/$PACKS_DIR/$ACTIVE_PACK_FILE" else "assets/$SEED_ASSET_PATH"
        val text = raw
            ?: runCatching { context.assets.open(SEED_ASSET_PATH).bufferedReader().use { it.readText() } }.getOrNull()
            ?: return@withContext null
        runCatching { parsePackJson(text).toInstalled(sourceLabel) }.getOrNull()
    }

    // ── Public domain model (for the browse screen) ────────────────

    /** A pack snapshot as the UI sees it — full metadata, ready to render. */
    data class InstalledPack(
        val version: Int,
        val language: String,
        val title: String,
        val source: String,
        val publishedAt: String,
        val entries: List<InstalledEntry>,
        /** Human-readable label of where this data was loaded from. */
        val loadedFrom: String,
    )

    data class InstalledEntry(
        val topic: String,
        val content: String,
        val sourceUrl: String?,
        val tags: List<String>,
    )

    // ── Internal ─────────────────────────────────────────────────────

    private data class ParsedPack(
        val version: Int,
        val language: String,
        val title: String,
        val source: String,
        val publishedAt: String,
        val entries: List<Entry>,
    ) {
        fun toInstalled(loadedFrom: String): InstalledPack = InstalledPack(
            version     = version,
            language    = language,
            title       = title,
            source      = source,
            publishedAt = publishedAt,
            entries     = entries.map { it.toPublic() },
            loadedFrom  = loadedFrom,
        )
    }

    private data class Entry(
        val topic: String,
        val content: String,
        val sourceUrl: String?,
        val tags: List<String>,
    ) {
        /**
         * Text BM25 will tokenise. Topic + tags are stuffed in too so
         * a query that hits the heading still scores the chunk. The
         * source URL is intentionally NOT included — it would inflate
         * BM25 term frequency for irrelevant tokens.
         */
        fun toIndexedText(): String = buildString {
            append(topic).append("\n\n")
            append(content)
            if (tags.isNotEmpty()) {
                append("\n\nTags: ").append(tags.joinToString(", "))
            }
        }
        fun toPublic(): InstalledEntry = InstalledEntry(topic, content, sourceUrl, tags)
    }

    private fun parsePackJson(raw: String): ParsedPack {
        val root = JSONObject(raw)
        val version = root.optInt("version", 0).takeIf { it > 0 }
            ?: error("pack: missing or invalid 'version'")
        val language = root.optString("language", "en")
        val title = root.optString("title", "Kisan Knowledge")
        val source = root.optString("source", "")
        val publishedAt = root.optString("publishedAt", "")
        val entriesJson = root.optJSONArray("entries")
            ?: error("pack: missing 'entries' array")
        val entries = buildList {
            for (i in 0 until entriesJson.length()) {
                val o = entriesJson.optJSONObject(i) ?: continue
                val topic = o.optString("topic").trim()
                val content = o.optString("content").trim()
                if (topic.isEmpty() || content.isEmpty()) continue
                val sourceUrl = o.optString("sourceUrl").takeIf { it.isNotBlank() }
                val tagsArr = o.optJSONArray("tags")
                val tags = if (tagsArr != null) {
                    (0 until tagsArr.length()).mapNotNull { tagsArr.optString(it).takeIf { t -> t.isNotBlank() } }
                } else emptyList()
                add(Entry(topic, content, sourceUrl, tags))
            }
        }
        return ParsedPack(version, language, title, source, publishedAt, entries)
    }

    companion object {
        private const val SEED_ASSET_PATH = "packs/kisan_seed.json"
        private const val MIME_PACK = "application/x-saarthi-pack"
        private const val PACKS_DIR = "packs"
        private const val ACTIVE_PACK_FILE = "kisan_active.json"
    }
}
