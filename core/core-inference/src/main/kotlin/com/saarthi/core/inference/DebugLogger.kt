package com.saarthi.core.inference

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
 */
object DebugLogger {

    private const val FILE_NAME = "saarthi_debug.log"

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // One of these is non-null after init():
    private var mediaUri: Uri? = null
    private var fileSink: File? = null

    // Cached resolver so log() doesn't have to walk back through Context.
    @Volatile private var resolverContext: Context? = null

    // Human-readable label for the "session start" line, e.g. "Downloads/saarthi_debug.log".
    private var pathLabel: String = "(uninit)"

    @Synchronized
    fun init(context: Context) {
        if (mediaUri != null || fileSink != null) return
        val app = context.applicationContext
        resolverContext = app

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaUri = findOrCreateMediaStoreEntry(app)
            if (mediaUri != null) {
                pathLabel = "Downloads/$FILE_NAME (MediaStore)"
                announce()
                return
            }
        }

        // Legacy / fallback chain. Direct File writes to public Downloads only
        // work pre-Q without MANAGE_EXTERNAL_STORAGE; after that we land in
        // app-scoped storage, which is still accessible via adb / IDE.
        val candidates = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME),
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

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg\n"
        Log.d("SaarthiDbg", line.trimEnd())
        writeLine(line)
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

    /**
     * Look up an existing /Download/saarthi_debug.log entry, else create one.
     * Returns null if the volume isn't writable (extremely rare — only happens
     * on emulators without external storage mounted).
     */
    private fun findOrCreateMediaStoreEntry(context: Context): Uri? {
        return runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            // Re-use an entry from a previous session so we keep appending to
            // the same file instead of creating saarthi_debug (1).log,
            // saarthi_debug (2).log, … on every launch.
            val proj = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME}=?"
            val existing: Uri? = resolver.query(collection, proj, selection, arrayOf(FILE_NAME), null)?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
            }
            if (existing != null) return@runCatching existing

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                // IS_PENDING=0 so the file is immediately visible to file
                // managers; the user shouldn't have to wait for an app
                // "finalize" step to see the log.
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.insert(collection, values)
        }.getOrNull()
    }
}
