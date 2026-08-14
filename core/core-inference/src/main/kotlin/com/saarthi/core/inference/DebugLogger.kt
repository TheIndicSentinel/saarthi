package com.saarthi.core.inference

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only debug log that lands in the device's public **Downloads** folder
 * so a non-technical user can grab `saarthi_debug.log` with any file manager
 * (or the system Files app) without opening the Saarthi app — critical when
 * the app is *stuck* on onboarding/download and Settings is unreachable.
 *
 * Strategy:
 *  - Android 10+ (API 29+): write via [MediaStore.Downloads]. No permission
 *    needed; the file becomes a normal entry under /Download/saarthi_debug.log
 *    visible to every file manager and the Files app.
 *  - Android 9 and below: write directly to the public Downloads dir
 *    (works with the legacy WRITE_EXTERNAL_STORAGE model).
 *  - If both fail (extremely locked-down OEM): fall back to
 *    `externalFilesDir → filesDir` so the log still exists somewhere.
 *
 * H12 fix: [log] used to open/write/close the MediaStore or File sink
 * SYNCHRONOUSLY on the calling thread — including LiteRTInferenceEngine's
 * single-threaded `engineDispatcher`, which also serializes every other
 * engine operation (next generate call, release, reset). A slow write on a
 * contended device could stall inference itself for a diagnostic side
 * effect. [log] now only formats the line and enqueues it (an in-memory,
 * non-suspending, effectively-instant op); a single background coroutine
 * drains the channel and performs the actual I/O off the caller's thread.
 * Deliberately NOT also batching multiple lines into fewer writes — this
 * log's whole purpose is crash forensics ("the line just before the gap is
 * what caused the kill" — see docs/PRODUCTION.md), so each line is still
 * written with its own open/append/close the moment the writer coroutine
 * picks it up, preserving the "durable across process death" property the
 * original per-call synchronous write had, rather than trading it away for
 * a bigger throughput win this log doesn't need (it's low-volume — decision
 * points, not a per-token hot path).
 *
 * Point 9 (file privacy): messages written here may be user-attached via
 * Support (and, in beta, public Downloads). Call sites must not put
 * user-sourced content in [msg] — use [LogPrivacy] (lengths/counts only) for
 * document names, memory keys, reminder text, etc. Catalog model ids and
 * hardware facts remain fine.
 */
object DebugLogger {

    private const val FILE_NAME = "saarthi_debug.log"

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // One of these is non-null after init(). @Volatile because the writer
    // coroutine (a different thread than whatever called init()) reads
    // them — the channel send/receive between log() and the writer already
    // establishes happens-before for the LINE content, but these two are
    // read independently of any one specific channel item, so they need
    // their own visibility guarantee rather than relying on that.
    @Volatile private var mediaUri: Uri? = null
    @Volatile private var fileSink: File? = null

    // Cached resolver so log() doesn't have to walk back through Context.
    @Volatile private var resolverContext: Context? = null

    // Human-readable label for the "session start" line, e.g. "Downloads/saarthi_debug.log".
    private var pathLabel: String = "(uninit)"

    // Point 4: writeLine() already wraps its own I/O in runCatching, so this
    // is defense-in-depth (not a known live bug) — but an unguarded scope
    // is exactly the shape that turns any FUTURE change to this loop into a
    // silent hard crash of the app's own crash-forensics writer. Falls back
    // to plain Log.e (not DebugLogger.log — this scope IS DebugLogger, would
    // self-reenter) so it stays debug-build-only per point 1's gate.
    private val writerExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (BuildConfig.DEBUG) Log.e("SaarthiDbg", "writerScope failure: ${throwable::class.simpleName}: ${throwable.message}")
    }
    // Process-lifetime scope for the background writer — never explicitly
    // cancelled, same rationale as ChatRepositoryImpl's own singleton
    // `scope`: an Android process just dies, there's no clean shutdown hook
    // to cancel it from. SupervisorJob so one bad write can't kill the loop.
    private val writerScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + writerExceptionHandler)
    // Unlimited so log() (called from 200+ call sites, many NOT in a
    // coroutine) never suspends or drops a line waiting for channel space —
    // it only formats + enqueues, an effectively-instant in-memory op.
    private val lineChannel = Channel<String>(Channel.UNLIMITED)
    @Volatile private var writerStarted = false

    @Synchronized
    fun init(context: Context) {
        if (!writerStarted) {
            writerStarted = true
            writerScope.launch {
                for (line in lineChannel) writeLine(line)
            }
        }
        if (mediaUri != null || fileSink != null) return
        val app = context.applicationContext
        resolverContext = app

        // Privacy gate: only publish the log to the world-readable public
        // Downloads folder when PUBLIC_DEBUG_LOG is enabled (beta). A Play
        // production build sets it false, so the log stays in app-private
        // storage (still readable via adb / Android Studio for support) and
        // never exposes prompts / filenames / device info to other apps.
        val allowPublic = BuildConfig.PUBLIC_DEBUG_LOG

        if (allowPublic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaUri = findOrCreateMediaStoreEntry(app)
            if (mediaUri != null) {
                pathLabel = "Downloads/$FILE_NAME (MediaStore)"
                announce()
                return
            }
        }

        // Legacy / fallback chain. Direct File writes to public Downloads only
        // work pre-Q without MANAGE_EXTERNAL_STORAGE; after that we land in
        // app-scoped storage, which is still accessible via adb / IDE. When the
        // public gate is off, the public Downloads candidate is skipped so the
        // log can only land in app-private storage.
        val candidates = listOfNotNull(
            if (allowPublic) File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME) else null,
            File(app.getExternalFilesDir(null), FILE_NAME),
            File(app.filesDir, FILE_NAME),
        )
        val chosen = candidates.firstOrNull { f ->
            runCatching { f.parentFile?.mkdirs(); f.createNewFile() || f.exists() }.getOrDefault(false)
        } ?: candidates.last()
        fileSink = chosen
        pathLabel = chosen.absolutePath
        announce()
    }

    fun path(): String = pathLabel

    /**
     * A shareable `content://` Uri for the current log file, for the Support
     * screen's "attach log" action — or null if logging never initialized.
     *
     * [mediaUri] (public-Downloads mode) is already a MediaStore `content://`
     * Uri, directly shareable with no extra grant. [fileSink] (app-private
     * mode — the default for a production build, or the Android-9 fallback)
     * is a raw [File] outside any other app's reach; it's wrapped via
     * [androidx.core.content.FileProvider] so `ACTION_SEND` can still attach
     * it. Without this, "attach the log" only worked in the beta channel
     * (public Downloads) and silently had nothing to attach in production.
     */
    fun shareableUri(context: Context): Uri? {
        mediaUri?.let { return it }
        val f = fileSink ?: return null
        return runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.applicationContext.packageName}.fileprovider", f,
            )
        }.getOrNull()
    }

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg\n"
        // Logcat mirror is debug-build only — release builds must not put
        // diagnostic content (model names, memory keys, doc names, funnel
        // events) anywhere Logcat-observable, matching the same threshold
        // BuildConfig.PUBLIC_DEBUG_LOG already applies to the file sink
        // above. The file write below is unaffected — "attach log" still
        // works in every build.
        if (BuildConfig.DEBUG) Log.d("SaarthiDbg", line.trimEnd())
        // Enqueue only — the actual file/MediaStore write happens on the
        // background writer coroutine (see writerScope above), never on the
        // caller's thread. trySend on an UNLIMITED channel never suspends
        // and only fails if the channel was closed (never happens here), so
        // this stays a fire-and-forget, non-suspending call every one of
        // this function's 200+ call sites can keep using exactly as before.
        lineChannel.trySend(line)
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun announce() {
        log("APP", "--------------------------------------------------")
        log("APP", "=== Saarthi start session ===  path=$pathLabel")
        log("APP", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})  device=${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private fun writeLine(line: String) {
        val uri = mediaUri
        val ctx = resolverContext
        if (uri != null && ctx != null) {
            // "wa" = write-append. Opens, appends bytes, closes. Per-line
            // open/close keeps the file durable across process death — a
            // crash mid-download still leaves the log on disk.
            runCatching {
                ctx.contentResolver.openOutputStream(uri, "wa")?.use { os ->
                    os.write(line.toByteArray())
                }
            }
            return
        }
        val f = fileSink ?: return
        runCatching { f.appendText(line) }
    }

    // ── single-file guarantee ────────────────────────────────────────────────
    // The old approach queried MediaStore by LIKE pattern and kept the first
    // match, but entries from a previous install (different UID on Samsung /
    // Samsung One UI) resist deletion and cannot be written to. Every fresh
    // install then called resolver.insert() and created another file
    // ("saarthi_debug (1).log", "(2).log", …). The fix: persist the working
    // URI in private SharedPreferences and probe writability before trusting
    // any cached or found entry. If write fails, clean up best-effort and
    // insert a fresh entry that IS owned by the current install.
    private const val PREFS_NAME = "debug_logger_prefs"
    private const val PREF_LOG_URI = "log_uri_v2"

    /**
     * Returns a writable MediaStore URI for the log file, guaranteed to be
     * owned by (and writable by) the current install. Persists the URI in
     * private SharedPreferences so the file is reused across process restarts
     * without re-querying MediaStore every time.
     *
     * On first run (or after data-clear / reinstall), any stale floating
     * "saarthi_debug*.log" entries are deleted best-effort and a fresh
     * canonical file is created.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findOrCreateMediaStoreEntry(context: Context): Uri? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            // Fast path: reuse the URI from the last successful init.
            val savedStr = prefs.getString(PREF_LOG_URI, null)
            if (savedStr != null) {
                val savedUri = Uri.parse(savedStr)
                val writable = runCatching {
                    resolver.openOutputStream(savedUri, "wa")?.use { /* probe */ }
                    true
                }.getOrDefault(false)
                if (writable) return@runCatching savedUri
                // Stale (prior install or deleted by user) — fall through.
                prefs.edit().remove(PREF_LOG_URI).apply()
            }

            // Slow path: delete every floating saarthi_debug*.log variant
            // (best-effort; entries owned by a different UID will silently
            // resist deletion, which is fine — we just create a fresh one).
            val proj = arrayOf(MediaStore.Downloads._ID)
            resolver.query(
                collection, proj,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("saarthi_debug%.log"),
                "${MediaStore.Downloads._ID} ASC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (c.moveToNext()) {
                    runCatching {
                        resolver.delete(ContentUris.withAppendedId(collection, c.getLong(idCol)), null, null)
                    }
                }
            }

            // Insert a fresh file owned by the current install.
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            val newUri = resolver.insert(collection, values) ?: return@runCatching null
            prefs.edit().putString(PREF_LOG_URI, newUri.toString()).apply()
            newUri
        }.getOrNull()
    }
}
