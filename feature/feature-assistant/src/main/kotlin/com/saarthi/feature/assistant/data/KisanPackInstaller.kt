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

    /**
     * Install the bundled seed pack on first launch, OR re-install it when the
     * bundled seed is a NEWER version than what's installed — so seed content
     * updates (new tags, fixed entries) shipped in an app update actually reach
     * existing users, not just fresh installs. A remotely-downloaded pack
     * (PackUpdateWorker) sets a higher version and is left untouched.
     */
    suspend fun installSeedIfAbsent() = withContext(Dispatchers.IO) {
        val bundledVersion = runCatching {
            context.assets.open(SEED_ASSET_PATH).bufferedReader().use { JSONObject(it.readText()).optInt("version", 0) }
        }.getOrDefault(0)
        if (bundledVersion <= 0) return@withContext
        if (preference.installedVersion.value >= bundledVersion) return@withContext
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
        val entities = parsed.toEntities()
        if (entities.isEmpty()) {
            DebugLogger.log("PACK", "Kisan install skipped: pack has 0 entries (source=$source)")
            return@withContext null
        }

        // Rollback safety: snapshot the current good pack into the "previous"
        // slot BEFORE we mutate anything. If the swap below fails partway we
        // revert to it (see rollbackToPrevious), and loadInstalledPack can
        // self-heal to it on a corrupt active file. The new pack is only
        // committed AFTER it has fully parsed and produced non-empty entries —
        // a malformed pack never reaches this point, so the old pack stays.
        backupActiveToPrevious()

        val committed = runCatching {
            ragChunkDao.deleteBySession(PackId.KISAN.sessionId)
            ragChunkDao.insertAll(entities)
            writeActiveFile(raw)
            preference.recordInstall(version = parsed.version, language = parsed.language)
        }.isSuccess

        if (!committed) {
            DebugLogger.log("PACK", "Kisan install commit failed (source=$source) — rolling back to previous pack")
            val restored = rollbackToPrevious()
            DebugLogger.log("PACK", "Kisan rollback ${if (restored) "succeeded" else "found no previous pack"}")
            return@withContext null
        }

        DebugLogger.log(
            "PACK",
            "Kisan pack v${parsed.version} (${parsed.language}, ${entities.size} entries) installed from $source",
        )
        parsed.version
    }

    /**
     * Revert to the last known-good pack saved in the "previous" slot. Used
     * when a fresh install's commit fails, and available to PackUpdateWorker
     * for operational rollback if a downloaded pack is later found bad.
     * Rebuilds BOTH the RAG chunks and the active file from the previous
     * snapshot. Returns false when there is no usable previous pack.
     */
    suspend fun rollbackToPrevious(): Boolean = withContext(Dispatchers.IO) {
        val prev = previousPackFile()
        val raw = runCatching {
            if (prev.exists() && prev.length() > 0L) prev.readText(Charsets.UTF_8) else null
        }.getOrNull() ?: return@withContext false
        val parsed = runCatching { parsePackJson(raw) }.getOrNull() ?: return@withContext false
        val entities = parsed.toEntities()
        if (entities.isEmpty()) return@withContext false
        runCatching {
            ragChunkDao.deleteBySession(PackId.KISAN.sessionId)
            ragChunkDao.insertAll(entities)
            writeActiveFile(raw)
            preference.recordInstall(version = parsed.version, language = parsed.language)
        }.isSuccess
    }

    // ── File helpers (rollback / self-heal) ───────────────────────────────
    private fun activePackFile(): File = File(context.filesDir, "$PACKS_DIR/$ACTIVE_PACK_FILE")
    private fun previousPackFile(): File = File(context.filesDir, "$PACKS_DIR/$PREVIOUS_PACK_FILE")

    private fun writeActiveFile(raw: String) {
        val dir = File(context.filesDir, PACKS_DIR).apply { mkdirs() }
        File(dir, ACTIVE_PACK_FILE).writeText(raw, Charsets.UTF_8)
    }

    /** Copy the current active pack into the previous slot (best-effort). */
    private fun backupActiveToPrevious() {
        runCatching {
            val active = activePackFile()
            if (active.exists() && active.length() > 0L) {
                active.copyTo(previousPackFile(), overwrite = true)
            }
        }.onFailure { Timber.w(it, "Kisan: failed to back up active pack to previous") }
    }

    private fun ParsedPack.toEntities(): List<RagChunkEntity> =
        entries.mapIndexed { idx, entry ->
            RagChunkEntity(
                sessionId  = PackId.KISAN.sessionId,
                docUri     = "pack://kisan/$version/$idx",
                docName    = entry.topic.take(80),
                mimeType   = MIME_PACK,
                chunkIndex = idx,
                text       = entry.toIndexedText(),
            )
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
        // Self-healing fallback chain: active → previous → bundled seed.
        // Each step tolerates a missing or corrupt source, so a damaged active
        // file silently recovers to the last good pack (or the seed) rather
        // than showing the user an empty pack.
        readInstalledFrom(activePackFile(), "filesDir/$PACKS_DIR/$ACTIVE_PACK_FILE")
            ?.let { return@withContext it }
        readInstalledFrom(previousPackFile(), "filesDir/$PACKS_DIR/$PREVIOUS_PACK_FILE")
            ?.let { return@withContext it }
        val seed = runCatching {
            context.assets.open(SEED_ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@withContext null
        runCatching { parsePackJson(seed).toInstalled("assets/$SEED_ASSET_PATH") }.getOrNull()
    }

    /** Read + parse a pack file into the UI model, or null if missing/corrupt. */
    private fun readInstalledFrom(file: File, label: String): InstalledPack? {
        if (!file.exists() || file.length() == 0L) return null
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching { parsePackJson(raw).toInstalled(label) }.getOrNull()
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
        // Last known-good pack, kept for rollback + the loadInstalledPack
        // self-heal chain (active → previous → bundled seed).
        private const val PREVIOUS_PACK_FILE = "kisan_previous.json"
    }
}
