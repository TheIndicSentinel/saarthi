package com.saarthi.core.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Thrown when the server returns a terminal HTTP error (401/403/404) — no retry. */
private class DownloadHttpException(val code: Int, message: String) : IOException(message)

/**
 * Thrown when the device is (or is about to be) out of storage — either
 * proactively, from the write loop's periodic [File.usableSpace] check
 * catching a device slowly filling up from another app running
 * concurrently, or reactively, from the write itself failing with ENOSPC.
 * Terminal — retrying a write against a full disk wastes battery on exactly
 * the devices with the least of it to spare.
 */
private class InsufficientStorageException(message: String) : IOException(message)

/**
 * Thrown when a freshly-completed download's SHA-256 doesn't match
 * ModelEntry.expectedSha256. Not caught specially — it deliberately falls
 * through to the generic retry handler below, so a persistently-corrupted
 * source still fails cleanly after MAX_ATTEMPTS instead of retrying forever,
 * the same bound every other transient failure already gets.
 */
private class ChecksumMismatchException(message: String) : IOException(message)

/**
 * Thrown when a Range-resume request gets HTTP 416 (Requested Range Not
 * Satisfiable). Per RFC 7233 §4.4, this means the requested start offset is
 * at or past the resource's current length — i.e. the local tmp file already
 * has at least as many bytes as the server currently has to give. This
 * happens when the catalog's hardcoded fileSizeBytes drifts from what
 * HuggingFace actually serves for a revision, or when a transfer finished
 * writing every byte but was killed the instant before the atomic rename.
 *
 * [serverTotalBytes] is parsed from the 416 response's own Content-Range
 * header (an unsatisfied-range indicator followed by a slash and the total
 * size — RFC 7233 §4.4 requires the server to include it) — used by
 * [runDownload] to verify the local file actually
 * covers what the server reports before accepting it as complete, rather
 * than assuming from the 416 status alone.
 */
private class RangeExhaustedException(val serverTotalBytes: Long?) :
    IOException("Local file already complete relative to server")

/**
 * Foreground Service that performs model downloads.
 *
 * WHY a foreground service and not WorkManager:
 * WorkManager schedules work through the platform JobScheduler. OEM Android
 * skins (OxygenOS/ColorOS/RealmeUI on OnePlus/OPPO/Realme, MIUI, FuntouchOS,
 * One UI's "Deep sleep", etc.) aggressively refuse to *dispatch* deferred
 * JobScheduler jobs for apps the user hasn't whitelisted — even when every
 * constraint (network, storage) is satisfied. The result is a job stuck in
 * ENQUEUED that never runs, with no error. This was reproduced on a OnePlus
 * CPH2487 (Android 14): all constraints green, worker never started.
 *
 * The robust, industry-standard pattern for a *user-initiated* long transfer
 * is to start a foreground service directly in response to the tap. Because
 * the app is in the foreground at that moment, the start is allowed under the
 * Android 12+ background-FGS-start rules; once [startForeground] posts the
 * ongoing notification, OEM ROMs keep the service alive the same way they keep
 * a music player or active upload alive. This is what production apps
 * (WhatsApp, Telegram, Play Store) use for downloads.
 *
 * What is preserved from the previous WorkManager implementation:
 *  • OkHttp HTTP Range resume — byte-exact continuation from a partial tmp file.
 *  • Atomic tmp -> dest rename, GGUF/size validation (via [ModelDownloadManager]).
 *  • Progress reported into [ModelDownloadManager.allProgress] (UI unchanged).
 *  • Foreground type = dataSync (switched from specialUse 2026-07-17 — see
 *    this service's manifest declaration for the full reasoning, including
 *    how the API 35+ 6-hour background timeout is handled via [onTimeout]).
 *
 * Improvement over WorkManager: [START_REDELIVER_INTENT] means if the OS kills
 * the service mid-download, the last start intent is redelivered and the
 * download resumes from the partial tmp file with zero wasted bytes.
 */
@AndroidEntryPoint
class ModelDownloadService : Service() {

    @Inject lateinit var manager: ModelDownloadManager
    @Inject lateinit var languageManager: com.saarthi.core.i18n.LanguageManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One coroutine per in-flight model download, keyed by model id. */
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Serializes the actual byte transfer to ONE model at a time. Multiple
     * jobs may be launched (the user can queue several models), but each waits
     * here before transferring so two multi-GB downloads never run together.
     *
     * Why: concurrent transfers split the connection AND thrash the OS page
     * cache — gigabytes of file-backed write pages count against "available"
     * RAM, which on a flagship dragged avail from 3.1 GB → 2.3 GB mid-session
     * and tripped the GPU-unsafe fallback (avail < 3 GB) during inference.
     * One-at-a-time keeps each transfer faster and avail RAM steadier while a
     * model is loaded. A cancelled queued job simply never acquires the lock.
     */
    private val transferGate = Mutex()

    /** Latest (downloaded, total) per model — drives the foreground notification. */
    private val latest = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val titles = ConcurrentHashMap<String, String>()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 12+ requires startForeground() within ~5s of EVERY start —
        // including a cold-start CANCEL — or the system throws and kills us.
        val fgsStarted = promoteToForeground(intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE)

        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        when (intent?.action) {
            ACTION_CANCEL -> {
                if (modelId != null) {
                    jobs.remove(modelId)?.cancel()
                    latest.remove(modelId); titles.remove(modelId)
                    DebugLogger.log("DOWNLOAD_SVC", "CANCEL $modelId")
                }
                if (jobs.isEmpty()) stopEverything() else refreshForeground()
            }

            ACTION_START -> {
                val url     = intent.getStringExtra(EXTRA_URL)
                val tmp     = intent.getStringExtra(EXTRA_TMP_PATH)
                val dest    = intent.getStringExtra(EXTRA_DEST_PATH)
                val title   = intent.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
                val token   = intent.getStringExtra(EXTRA_HF_TOKEN) ?: ""
                val replace = intent.getBooleanExtra(EXTRA_REPLACE, false)
                val expectedSha256 = intent.getStringExtra(EXTRA_EXPECTED_SHA256)

                if (modelId == null || url == null || tmp == null || dest == null) {
                    DebugLogger.log("DOWNLOAD_SVC", "START missing extras — ignored")
                    if (jobs.isEmpty()) stopEverything()
                    return START_NOT_STICKY
                }

                if (!fgsStarted) {
                    // No real foreground protection — never let the download
                    // job launch (and the UI keep showing "Downloading") for
                    // a transfer with no FGS shield. Previously this failure
                    // was swallowed and the transfer proceeded unprotected
                    // with no state update at all.
                    DebugLogger.log("DOWNLOAD_SVC", "START aborted for $modelId — foreground promotion failed")
                    manager.emitFailed(modelId, "Could not start the download service. Please try again.")
                    if (jobs.isEmpty()) stopEverything()
                    return START_NOT_STICKY
                }

                titles[modelId] = title

                val existing = jobs[modelId]
                if (existing?.isActive == true && !replace) {
                    DebugLogger.log("DOWNLOAD_SVC", "START ignored — already running: $modelId")
                    return START_REDELIVER_INTENT
                }

                // For a replace (restart from zero), cancel the in-flight job and
                // have the new coroutine join() it before touching the tmp file —
                // guarantees no two transfers write the same file concurrently.
                val toCancel = if (replace) jobs.remove(modelId)?.also { it.cancel() } else null

                DebugLogger.log("DOWNLOAD_SVC",
                    "START $modelId  dest=${dest.substringAfterLast('/')}${if (replace) "  (replace)" else ""}")

                val job = serviceScope.launch {
                    toCancel?.join()
                    // Queue behind any active transfer (see [transferGate]). Only
                    // log "QUEUED" when something else actually holds the gate, so
                    // the common single-download case stays quiet.
                    if (transferGate.isLocked) {
                        DebugLogger.log("DOWNLOAD_SVC", "QUEUED $modelId — waiting for active download")
                    }
                    transferGate.withLock {
                        // For a replace, wipe the tmp only AFTER the old job has fully
                        // stopped (toCancel.join above) AND it's this model's turn —
                        // otherwise its final in-flight chunk could recreate the file
                        // and the new run would resume instead of starting over.
                        if (replace) File(tmp).delete()
                        runDownload(modelId, url, File(tmp), File(dest), token, title, expectedSha256)
                    }
                }
                jobs[modelId] = job
                job.invokeOnCompletion { cause ->
                    // remove(key, value): only clears the map if THIS job is still
                    // the registered one — avoids a restart's new job being evicted
                    // by the old job's completion callback.
                    jobs.remove(modelId, job)
                    latest.remove(modelId); titles.remove(modelId)
                    if (jobs.isEmpty()) {
                        // cause == null means runDownload() returned normally, i.e.
                        // the model is ready and the caller is about to hand off to
                        // InferenceService.startLoading() on its own async coroutine
                        // (see scheduleStopIfIdle's kdoc) — a failed/cancelled job has
                        // no such handoff, so it stops immediately as before.
                        if (cause == null) scheduleStopIfIdle() else stopEverything()
                    } else {
                        refreshForeground()
                    }
                }
            }

            else -> if (jobs.isEmpty()) stopEverything()
        }

        // Redeliver the last start intent if the OS kills us mid-transfer; the
        // download resumes from the partial tmp file via OkHttp Range.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * API 35+ only (never called on older platforms — safe to declare
     * unconditionally). A dataSync foreground service gets 6 cumulative
     * background hours per 24h window (resets whenever the user brings the
     * app to the foreground); the system calls this when that budget is
     * exhausted and expects stopSelf() within a few seconds, or it throws a
     * fatal RemoteServiceException. The tmp file is untouched by this —
     * whatever bytes were written stay on disk exactly as with any other
     * service death, and reattachActiveDownloads() resumes it via Range the
     * next time the app is opened. Most users check the app at least once
     * within 24h, which resets the timer before it would ever bind in
     * practice; this only matters for a download left completely untouched
     * for many hours straight.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DebugLogger.log("DOWNLOAD_SVC", "onTimeout — dataSync background budget exhausted, stopping cleanly")
        stopSelf()
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Computes and compares [destFile]'s SHA-256 against [expectedSha256].
     * Returns true if verified, or if no checksum is pinned for this catalog
     * entry (expectedSha256 == null — unaffected). On mismatch, deletes
     * [destFile] and logs loudly; does NOT throw — callers decide how to
     * react (this file is a fresh download in either caller, current-session
     * against the currently-pinned URL, so both react by discarding and
     * retrying — see [verifyChecksumOrThrow] and the HTTP-416
     * accept-as-complete branch in [runDownload]).
     *
     * Every catalog entry's downloadUrl is pinned to an immutable commit
     * SHA, so a mismatch here can only mean real transfer corruption or a
     * catalog data-entry error — not natural upstream drift — which means
     * there's no good reason to promote the file to "ready to use" anyway.
     */
    private suspend fun verifyChecksum(modelId: String, destFile: File, expectedSha256: String?): Boolean {
        if (expectedSha256 == null) return true
        val actual = withContext(Dispatchers.IO) { StreamingSha256.hex(destFile) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            Timber.e("Model checksum mismatch: $modelId  expected=$expectedSha256  actual=$actual")
            DebugLogger.log("DOWNLOAD_SVC",
                "CHECKSUM MISMATCH for $modelId — expected=$expectedSha256 actual=$actual — discarding")
            destFile.delete()
            return false
        }
        DebugLogger.log("DOWNLOAD_SVC", "Checksum verified for $modelId")
        return true
    }

    /** [verifyChecksum], but throws so the caller's normal retry/backoff catch handles it. */
    private suspend fun verifyChecksumOrThrow(modelId: String, destFile: File, expectedSha256: String?) {
        if (!verifyChecksum(modelId, destFile, expectedSha256)) {
            throw ChecksumMismatchException("Downloaded file failed integrity check")
        }
    }

    private suspend fun runDownload(
        modelId: String,
        url: String,
        tmpFile: File,
        destFile: File,
        token: String,
        title: String,
        expectedSha256: String? = null,
    ) {
        tmpFile.parentFile?.mkdirs()
        destFile.parentFile?.mkdirs()

        var attempt = 0
        while (true) {
            attempt++
            try {
                downloadWithResume(modelId, url, tmpFile, token, title)

                // Atomic move to final destination — rename is fast and avoids a
                // partial-write window; fall back to copy+delete if cross-device.
                if (destFile.exists()) destFile.delete()
                if (!tmpFile.renameTo(destFile)) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }

                DebugLogger.log("DOWNLOAD_SVC",
                    "COMPLETE ${destFile.name}  size=${destFile.length() / 1_048_576}MB")

                verifyChecksumOrThrow(modelId, destFile, expectedSha256)
                manager.emitCompleted(modelId, destFile.absolutePath)
                return

            } catch (e: CancellationException) {
                // User cancelled (or service killed) — keep partial tmp for resume.
                DebugLogger.log("DOWNLOAD_SVC",
                    "CANCELLED $modelId  partial=${tmpFile.length() / 1_048_576}MB kept")
                throw e

            } catch (e: DownloadHttpException) {
                // Terminal HTTP error — retrying won't help.
                Timber.e(e, "Terminal HTTP ${e.code} downloading ${tmpFile.name}")
                DebugLogger.log("DOWNLOAD_SVC", "TERMINAL HTTP ${e.code}: ${e.message}")
                manager.emitFailed(modelId, e.message ?: "Download failed (HTTP ${e.code})")
                return

            } catch (e: RangeExhaustedException) {
                // A 416 on resume means our Range start is at/past the
                // resource's current length — but verify against the size the
                // server itself reported (Content-Range on the 416 response)
                // before trusting it, rather than assuming from the status
                // code alone. A genuinely short local file (corruption, not
                // drift) gets discarded and restarted instead of silently
                // accepted.
                val localBytes = tmpFile.length()
                val serverTotal = e.serverTotalBytes
                if (serverTotal != null && localBytes < serverTotal) {
                    Timber.w("416 on resume but local ${localBytes}B < server-reported ${serverTotal}B for ${tmpFile.name} — discarding and restarting")
                    DebugLogger.log("DOWNLOAD_SVC",
                        "416 on resume for $modelId: local=${localBytes}B < server=${serverTotal}B — restarting from zero")
                    tmpFile.delete()
                    if (attempt >= MAX_ATTEMPTS) {
                        manager.emitFailed(modelId, "Download failed: local file shorter than server reports (HTTP 416)")
                        return
                    }
                    delay((attempt * 10_000L).coerceAtMost(60_000L))
                    // Loop continues; next downloadWithResume() starts clean
                    // since the tmp file no longer exists (existingBytes = 0).
                } else {
                    // Local file already covers everything the server currently
                    // has (or the server didn't report a total — fall back to
                    // trusting the byte count, same as before). Accept as
                    // complete rather than looping on retries that would hit
                    // the identical 416 forever (the old behavior, which reset
                    // the UI to 0% on the next attempt and looked like the
                    // download "restarting from scratch"). Downstream,
                    // ModelDownloadManager.isFileComplete() + confirmModelAndInit()
                    // still re-validate size/magic-bytes before the model is
                    // ever loaded, so a genuinely truncated file is still
                    // caught there.
                    DebugLogger.log("DOWNLOAD_SVC",
                        "416 on resume for $modelId — accepting local ${localBytes / 1_048_576}MB as complete" +
                            (serverTotal?.let { "  (server reports ${it / 1_048_576}MB)" } ?: "  (server total unknown)"))
                    if (destFile.exists()) destFile.delete()
                    if (!tmpFile.renameTo(destFile)) {
                        tmpFile.copyTo(destFile, overwrite = true)
                        tmpFile.delete()
                    }
                    // This is still a current-session download against the
                    // currently-pinned URL — hold it to the same strict bar
                    // as a normal completion, not the softer treatment
                    // reattached/legacy files get. A throw from inside this
                    // catch block would escape the whole retry loop instead
                    // of being retried by it, so react inline the same way
                    // the "local too short" branch above does.
                    if (expectedSha256 != null && !verifyChecksum(modelId, destFile, expectedSha256)) {
                        if (attempt >= MAX_ATTEMPTS) {
                            manager.emitFailed(modelId, "Downloaded file failed integrity check after $attempt attempts")
                            return
                        }
                        delay((attempt * 10_000L).coerceAtMost(60_000L))
                        // Loop continues; verifyChecksum() already deleted
                        // destFile on mismatch and tmpFile no longer exists,
                        // so next downloadWithResume() starts clean.
                    } else {
                        manager.emitCompleted(modelId, destFile.absolutePath)
                        return
                    }
                }

            } catch (e: InsufficientStorageException) {
                // Terminal — retrying a write against a full disk just burns
                // battery on the identical failure. Leave the tmp file in
                // place: resumable once the user frees space, same as any
                // other interrupted-but-kept partial download.
                DebugLogger.log("DOWNLOAD_SVC", "INSUFFICIENT STORAGE for $modelId  partial=${tmpFile.length() / 1_048_576}MB kept")
                manager.emitFailed(modelId, e.message ?: "Not enough storage space to finish this download.")
                return

            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS) {
                    Timber.w(e, "Download failed after $attempt attempts: ${tmpFile.name}")
                    DebugLogger.log("DOWNLOAD_SVC", "FAILED after $attempt attempts: ${e.message}")
                    manager.emitFailed(modelId, e.message ?: "Download failed")
                    return
                }
                // Transient network/IO error — linear back-off, then Range-resume.
                val backoffMs = (attempt * 10_000L).coerceAtMost(60_000L)
                DebugLogger.log("DOWNLOAD_SVC",
                    "RETRY $modelId in ${backoffMs / 1000}s (attempt $attempt): ${e.message}")
                delay(backoffMs)
            }
        }
    }

    private suspend fun downloadWithResume(
        modelId: String,
        url: String,
        tmpFile: File,
        token: String,
        title: String,
    ) {
        val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L

        val request = Request.Builder()
            .url(url)
            .apply {
                // Range header for resumption: ask the server to start from the last
                // byte we have. 206 confirms partial content; some servers reply 200
                // (full file) if they don't support Range — both are handled.
                if (existingBytes > 0L) addHeader("Range", "bytes=$existingBytes-")
                if (token.isNotEmpty()) addHeader("Authorization", "Bearer $token")
            }
            .build()

        val response = withContext(Dispatchers.IO) { http.newCall(request).execute() }
        val code = response.code
        if (!response.isSuccessful && code != 206) {
            response.close()
            if (code == 401 || code == 403 || code == 404) {
                val msg = when (code) {
                    401, 403 -> "Access denied (HTTP $code) — HuggingFace token may be invalid or expired."
                    404      -> "Model not found at download URL (HTTP 404)."
                    else     -> "HTTP $code: ${response.message}"
                }
                throw DownloadHttpException(code, msg)
            }
            if (code == 416) {
                // RFC 7233 §4.4: a 416 response SHOULD carry Content-Range:
                // bytes */<current-total-size> — the server telling us exactly
                // how large the resource is right now.
                val serverTotal = response.headers["Content-Range"]
                    ?.substringAfterLast('/')?.trim()?.toLongOrNull()
                throw RangeExhaustedException(serverTotal)
            }
            throw IOException("HTTP $code: ${response.message}")
        }

        val body = checkNotNull(response.body) { "Response body was null for HTTP $code" }

        val totalBytes: Long = response.headers["Content-Range"]
            ?.substringAfterLast('/')?.trim()?.toLongOrNull()
            ?: body.contentLength().takeIf { it >= 0L }?.plus(existingBytes)
            ?: 0L

        withContext(Dispatchers.IO) {
            body.byteStream().use { input ->
                // append = true so we write after the bytes already on disk
                FileOutputStream(tmpFile, existingBytes > 0L).use { output ->
                    val buffer = ByteArray(32 * 1024)  // 32 KB per read
                    var bytesRead: Int
                    var written = existingBytes
                    var lastReportMs = 0L

                    emitProgress(modelId, written, totalBytes)
                    while (input.read(buffer).also { bytesRead = it } >= 0) {
                        if (!isActive) throw CancellationException("download cancelled")
                        try {
                            output.write(buffer, 0, bytesRead)
                        } catch (e: IOException) {
                            // A disk-full write failure surfaces as a plain IOException
                            // (typically "No space left on device") — detect it and
                            // rethrow distinctly so runDownload() treats it as terminal
                            // instead of retrying a doomed write 6 times.
                            if (e.message?.contains("space", ignoreCase = true) == true ||
                                tmpFile.usableSpace < bytesRead) {
                                throw InsufficientStorageException("Not enough storage space to finish this download.")
                            }
                            throw e
                        }
                        written += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastReportMs >= PROGRESS_INTERVAL_MS) {
                            lastReportMs = now
                            emitProgress(modelId, written, totalBytes)
                            // Soft early-warning on the same cadence as progress
                            // reporting: catches storage slowly draining from
                            // another app running concurrently. Cheap File call,
                            // not the heavier StorageManager API — appropriate
                            // for a hot loop. The write itself failing with
                            // ENOSPC regardless of cause is still caught below.
                            val remaining = totalBytes - written
                            if (remaining > 0 && tmpFile.usableSpace < remaining) {
                                throw InsufficientStorageException("Not enough storage space to finish this download.")
                            }
                        }
                    }
                    emitProgress(modelId, written, totalBytes)
                }
            }
        }
    }

    private fun emitProgress(modelId: String, downloaded: Long, total: Long) {
        manager.emitProgress(modelId, downloaded, total)
        latest[modelId] = downloaded to total
        refreshForeground()
    }

    // ── Foreground notification ────────────────────────────────────────────────

    private fun promoteToForeground(title: String): Boolean =
        startForegroundCompat(buildNotification(title, 0L, 0L))

    private fun refreshForeground() {
        val entry = latest.entries.firstOrNull()
        val title = entry?.let { titles[it.key] } ?: DEFAULT_TITLE
        val (dl, total) = entry?.value ?: (0L to 0L)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(title, dl, total))
    }

    /**
     * Returns true only if [startForeground] actually succeeded — false
     * propagates up so [onStartCommand] can fail the download cleanly
     * instead of proceeding with a transfer that has no real FGS
     * protection (previously: this swallowed both the typed and the
     * untyped-retry failure silently, and the download job launched
     * regardless with the UI stuck showing "Downloading").
     */
    private fun startForegroundCompat(notification: Notification): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("InlinedApi")
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            return true
        } catch (e: Exception) {
            // Mirrors InferenceService: a silent failure here is the worst case
            // (process killed with no trace), so log it loudly and retry untyped.
            DebugLogger.log("DOWNLOAD_SVC", "WARN startForeground failed (${e.javaClass.simpleName}): ${e.message}")
            return runCatching { startForeground(NOTIF_ID, notification); true }.getOrElse {
                DebugLogger.log("DOWNLOAD_SVC", "WARN untyped startForeground also failed: ${it.message}")
                false
            }
        }
    }

    private fun stopEverything() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    /**
     * A completed download hands off directly to model loading
     * (InferenceService.startLoading()) on a separate, async ViewModel
     * coroutine — if this service's own FGS tears down before that call
     * fires, the app can momentarily have ZERO active foreground service.
     * Android's startForegroundService() background-launch restriction then
     * blocks the handoff outright: a field log (2026-07-19) showed
     * "FGS start skipped (ForegroundServiceStartNotAllowedException...)"
     * firing within ~100ms of this service's own completion, both times a
     * download finished with the screen off.
     *
     * Keeping this FGS alive a few seconds past completion covers that
     * handoff window — the app never drops to zero active foreground
     * services, so InferenceService's startForegroundService() call
     * qualifies for Android's "app already has an active foreground
     * service" exemption instead of racing to start from nothing. Re-checks
     * jobs.isEmpty() at fire time (not schedule time), so a new download
     * queued during the grace window is handled correctly without any
     * extra cancellation bookkeeping.
     */
    private fun scheduleStopIfIdle() {
        serviceScope.launch {
            delay(STOP_GRACE_MS)
            if (jobs.isEmpty()) stopEverything()
        }
    }

    private fun buildNotification(title: String, downloaded: Long, total: Long): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        // Tap → resume the app (same pattern as ReminderReceiver/PackUpdateWorker's
        // notifications) — was previously unset entirely, so tapping did nothing.
        val tapIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val tapPi = PendingIntent.getActivity(
            this, NOTIF_ID, tapIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return builder
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (total > 0L) {
                    val pct   = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                    val dlMb  = downloaded / 1_048_576
                    val totMb = total / 1_048_576
                    setContentText("$pct%  ·  ${dlMb}MB / ${totMb}MB")
                    setProgress(100, pct, false)
                } else {
                    setContentText(languageManager.selectedLanguage.value.startingDownload)
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress while AI models are downloaded"
            setShowBadge(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIF_ID = 9100        // distinct from InferenceService (9001)
        private const val CHANNEL_ID = "saarthi_download"
        private const val DEFAULT_TITLE = "Downloading AI model…"
        private const val PROGRESS_INTERVAL_MS = 800L
        private const val MAX_ATTEMPTS = 6
        private const val STOP_GRACE_MS = 3_000L

        private const val ACTION_START  = "com.saarthi.core.inference.action.DOWNLOAD_START"
        private const val ACTION_CANCEL = "com.saarthi.core.inference.action.DOWNLOAD_CANCEL"
        private const val EXTRA_MODEL_ID = "model_id"
        private const val EXTRA_URL      = "url"
        private const val EXTRA_TMP_PATH = "tmp_path"
        private const val EXTRA_DEST_PATH = "dest_path"
        private const val EXTRA_TITLE    = "title"
        private const val EXTRA_HF_TOKEN = "hf_token"
        private const val EXTRA_REPLACE  = "replace"
        private const val EXTRA_EXPECTED_SHA256 = "expected_sha256"

        /**
         * Starts (or resumes) a download. MUST be called from a foreground
         * context (it is — every caller is a user tap on the onboarding /
         * downloads screen) so the Android 12+ foreground-service start is
         * permitted.
         *
         * [replace] = true restarts from zero: any in-flight transfer for this
         * model is cancelled and the new one waits for it to fully stop before
         * writing, so the two never touch the tmp file at the same time.
         *
         * [expectedSha256] = null skips verification entirely (today's
         * behavior for every catalog entry that hasn't been pinned yet).
         *
         * Returns true only if the OS actually accepted the service start.
         * This is a DIFFERENT, earlier failure point than the in-service
         * fgsStarted check in onStartCommand() — if startForegroundService()
         * itself throws here, the Intent never reaches the service at all,
         * so onStartCommand() never runs and that later check never gets a
         * chance to fire. [ModelDownloadManager.launchDownload] uses this
         * return value to correct the optimistic "Downloading" state it sets
         * before calling this — otherwise the UI could be left stuck showing
         * progress for a transfer that never actually started.
         */
        fun start(
            context: Context,
            modelId: String,
            url: String,
            tmpPath: String,
            destPath: String,
            title: String,
            token: String,
            replace: Boolean = false,
            expectedSha256: String? = null,
        ): Boolean {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, modelId)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TMP_PATH, tmpPath)
                putExtra(EXTRA_DEST_PATH, destPath)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_HF_TOKEN, token)
                putExtra(EXTRA_REPLACE, replace)
                if (expectedSha256 != null) putExtra(EXTRA_EXPECTED_SHA256, expectedSha256)
            }
            return startServiceCompat(context, intent)
        }

        fun cancel(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            runCatching { startServiceCompat(context, intent) }
        }

        private fun startServiceCompat(context: Context, intent: Intent): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException can be thrown if the
                // app slipped to the background in the instant between the user's
                // tap and this call.
                DebugLogger.log("DOWNLOAD_SVC",
                    "WARN startForegroundService failed (${e.javaClass.simpleName}): ${e.message}")
                false
            }
        }
    }
}
