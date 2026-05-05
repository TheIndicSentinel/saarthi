package com.saarthi.core.inference

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import com.saarthi.core.inference.model.DownloadProgress
import com.saarthi.core.inference.model.LoraEntry
import com.saarthi.core.inference.model.ModelEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// GGUF magic: 'G','G','U','F'
private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

/**
 * Manages model downloads using Android [DownloadManager].
 *
 * Downloads run in [scope] which is tied to the Application, NOT to any ViewModel.
 * Progress is exposed via [allProgress] (a StateFlow<Map<modelId, DownloadProgress>>)
 * so the UI can subscribe/unsubscribe without affecting the underlying download.
 *
 * When the user navigates away and the ViewModel is cleared, the download continues
 * in Android DownloadManager and [scope] keeps polling progress.  On return,
 * the ViewModel re-subscribes to [allProgress] and immediately sees current state.
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hfTokenManager: HuggingFaceTokenManager,
) {
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** Application-lifetime scope — survives ViewModel/Activity lifecycle. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Per-model download progress, keyed by model ID. UI observes this. */
    private val _allProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val allProgress: StateFlow<Map<String, DownloadProgress>> = _allProgress.asStateFlow()

    /** Active polling jobs keyed by model ID. Cancelling stops polling but NOT the download. */
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    @Volatile private var hfToken: String = ""

    init {
        scope.launch { hfTokenManager.effectiveToken.collect { hfToken = it } }
        // Re-attach to any downloads that were already in progress (e.g. from a previous session).
        scope.launch { delay(1_000) /* wait for DI to settle */ }
    }

    fun modelsDir(): File =
        File(context.filesDir, "models").also { it.mkdirs() }

    fun adaptersDir(): File =
        File(context.filesDir, "adapters").also { it.mkdirs() }

    fun tmpModelsDir(): File =
        File(context.getExternalFilesDir(null), "models_tmp").also { it.mkdirs() }

    fun tmpAdaptersDir(): File =
        File(context.getExternalFilesDir(null), "adapters_tmp").also { it.mkdirs() }

    fun localPathFor(model: ModelEntry): File = File(modelsDir(), model.fileName)
    fun localPathFor(lora: LoraEntry): File   = File(adaptersDir(), lora.fileName)

    fun tmpPathFor(model: ModelEntry): File = File(tmpModelsDir(), model.fileName)
    fun tmpPathFor(lora: LoraEntry): File   = File(tmpAdaptersDir(), lora.fileName)

    private fun atomicMove(source: File, dest: File): Boolean {
        try {
            if (dest.exists()) dest.delete()
            if (source.renameTo(dest)) return true
            source.copyTo(dest, overwrite = true)
            source.delete()
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to move file")
            return false
        }
    }

    /**
     * Returns the actual file on disk for a model — checks canonical name first,
     * then DownloadManager-suffixed variants (-1, -2 …) that Android creates when
     * the destination file already exists at download time.
     * If a suffixed file is found it is renamed to the canonical path so subsequent
     * calls always resolve to [localPathFor].
     */
    fun resolveLocalFile(model: ModelEntry): File {
        val canonical = localPathFor(model)
        if (canonical.exists()) return canonical
        val base = model.fileName.substringBeforeLast('.')
        val ext  = model.fileName.substringAfterLast('.')
        for (i in 1..9) {
            val candidate = File(modelsDir(), "$base-$i.$ext")
            if (candidate.exists()) {
                DebugLogger.log("DOWNLOAD", "Renaming suffixed file ${candidate.name} → ${canonical.name}")
                candidate.renameTo(canonical)
                return canonical
            }
        }
        return canonical
    }

    fun isDownloaded(model: ModelEntry): Boolean =
        isFileComplete(resolveLocalFile(model), model.fileSizeBytes)

    fun isDownloaded(lora: LoraEntry): Boolean =
        isFileComplete(localPathFor(lora), lora.fileSizeBytes)

    // ── Download API (called from ViewModel) ──────────────────────────────────

    /**
     * Starts or reattaches to a download.  Progress is pushed into [allProgress]
     * from [scope] regardless of which ViewModel is currently observing.
     */
    fun startDownload(model: ModelEntry) {
        if (activeJobs[model.id]?.isActive == true) return  // already polling

        val finalFile = resolveLocalFile(model)
        if (isFileComplete(finalFile, model.fileSizeBytes)) {
            _allProgress.update { it + (model.id to DownloadProgress.Completed(finalFile.absolutePath)) }
            return
        }

        val tmpFile = tmpPathFor(model)

        val job = scope.launch {
            val downloadId = enqueueOrReattach(model.downloadUrl, tmpFile, model.displayName)
            if (downloadId == -1L) {
                _allProgress.update { it + (model.id to DownloadProgress.Failed("Failed to start download")) }
                return@launch
            }

            // Register a one-shot broadcast receiver for completion
            registerCompletionReceiver(model.id, downloadId, tmpFile, finalFile, model.fileSizeBytes)

            // Poll progress until DownloadManager reports done or failure
            while (true) {
                val progress = queryProgress(downloadId) ?: break
                _allProgress.update { it + (model.id to progress) }
                delay(if (progress is DownloadProgress.Paused) 3_000L else 600L)
            }

            // The polling loop exited (STATUS_SUCCESSFUL or cursor gone).
            delay(500) // Small delay to let DownloadManager flush to disk
            if (isFileComplete(tmpFile, model.fileSizeBytes, trustOS = true)) {
                val moved = atomicMove(tmpFile, finalFile)
                if (moved) {
                    DebugLogger.log("DOWNLOAD", "Success (poll-exit, moved): ${finalFile.name}")
                    _allProgress.update { it + (model.id to DownloadProgress.Completed(finalFile.absolutePath)) }
                } else {
                    _allProgress.update { it + (model.id to DownloadProgress.Failed("Failed to move model to internal storage")) }
                }
            } else {
                // Check if maybe the status is actually failed
                val finalStatus = queryStatus(downloadId)
                if (finalStatus == DownloadManager.STATUS_FAILED) {
                    val reason = queryFailureReason(downloadId)
                    DebugLogger.log("DOWNLOAD", "Failed (poll-exit): $reason")
                    _allProgress.update { it + (model.id to DownloadProgress.Failed(reason)) }
                }
            }
        }
        activeJobs[model.id] = job
    }

    /** Cancels the active download AND removes the partial file. */
    fun cancelDownload(model: ModelEntry) {
        activeJobs[model.id]?.cancel()
        activeJobs.remove(model.id)
        cancelByUrl(model.downloadUrl, localPathFor(model))
        _allProgress.update { it - model.id }
    }

    fun cancelDownload(lora: LoraEntry) = cancelByUrl(lora.downloadUrl, localPathFor(lora))

    /** Clears completed/failed state from the progress map (after user dismissed). */
    fun clearProgress(modelId: String) {
        _allProgress.update { it - modelId }
    }

    /** 
     * Force-restarts a download. Useful if DownloadManager is stuck in a 
     * 'Will retry shortly' loop due to transient network issues.
     */
    fun restartDownload(model: ModelEntry) {
        cancelDownload(model)
        startDownload(model)
    }

    /**
     * Restores [DownloadProgress.Completed] state for every model whose file is
     * already on disk and complete.  Call this on ViewModel init to populate the
     * "downloaded" badge in the model picker.
     *
     * This is intentionally SEPARATE from [reattachActiveDownloads] so it does NOT
     * trigger the "newlyCompleted" auto-select logic in OnboardingViewModel — those
     * models are already downloaded, not freshly completed.
     *
     * Three cases handled:
     *  • DM still active (PENDING/RUNNING/PAUSED) → skip; [reattachActiveDownloads] handles it.
     *  • DM reported FAILED → skip; the file on disk is partial. Do NOT mark as complete
     *    even if it passes the 99% size threshold — a failed download near the end produces
     *    a file that looks complete by size but is truncated at the last few bytes.
     *  • DM reported SUCCESSFUL → trustOS=true (90% threshold) to tolerate HF file-size drift.
     *  • No DM record (old download, manually placed file) → trustOS=false (99% threshold).
     */
    fun restoreCompletedStates(models: List<ModelEntry>) {
        models.forEach { model ->
            if (_allProgress.value[model.id] != null) return@forEach  // already tracked
            val dmStatus = findDownloadStatus(model.downloadUrl)
            // Still downloading — will be handled by reattachActiveDownloads
            if (dmStatus == DownloadManager.STATUS_PENDING ||
                dmStatus == DownloadManager.STATUS_RUNNING ||
                dmStatus == DownloadManager.STATUS_PAUSED) return@forEach
            // Failed download — file on disk is partial, never safe to use as "complete"
            if (dmStatus == DownloadManager.STATUS_FAILED) return@forEach
            val file = resolveLocalFile(model)
            val trustOS = dmStatus == DownloadManager.STATUS_SUCCESSFUL
            if (isFileComplete(file, model.fileSizeBytes, trustOS = trustOS)) {
                _allProgress.update { it + (model.id to DownloadProgress.Completed(file.absolutePath)) }
            }
        }
    }

    /**
     * Reattaches progress polling ONLY for models whose download is genuinely still
     * in-progress in Android DownloadManager (STATUS_PENDING / RUNNING / PAUSED).
     *
     * Uses URL-based lookup ([findActiveDownloadId]) instead of file-path comparison.
     * File-path comparison via [COLUMN_LOCAL_URI] is unreliable — on Android 10+ the
     * column can return content:// URIs instead of file:// paths, causing the path
     * equality check to fail silently and leaving the download state lost on resume.
     *
     * Does NOT call startDownload for files that already exist on disk — that
     * would immediately emit Completed and trigger the auto-select path in the UI.
     * Use [restoreCompletedStates] to populate the UI for already-finished downloads.
     */
    fun reattachActiveDownloads(models: List<ModelEntry>) {
        models.forEach { model ->
            if (activeJobs[model.id]?.isActive == true) return@forEach  // already polling
            val destFile = resolveLocalFile(model)
            // Guard: if the file is already complete, do NOT call startDownload —
            // startDownload() immediately emits Completed for existing files, which
            // triggers auto-select in the ViewModel and can load a 2nd model into RAM
            // while another model is already active, causing OOM kills mid-inference.
            if (isFileComplete(destFile, model.fileSizeBytes)) return@forEach
            // URL-based lookup — works regardless of COLUMN_LOCAL_URI format.
            // enqueueOrReattach (called by startDownload) also uses URL lookup, so
            // this is consistent and won't start a duplicate download.
            if (findActiveDownloadId(model.downloadUrl) != null) {
                DebugLogger.log("DOWNLOAD", "Reattaching to in-progress download: ${model.id}")
                startDownload(model)
            }
        }
    }

    // ── Lora downloads (simple flow — adapters are small) ─────────────────────

    fun downloadLora(lora: LoraEntry): Flow<DownloadProgress> =
        legacyDownloadFlow(lora.downloadUrl, localPathFor(lora), lora.displayName, lora.fileSizeBytes)

    // ── File validation ───────────────────────────────────────────────────────

    /**
     * Checks if a file is complete.
     *
     * When [trustOS] is true (Android DownloadManager reported STATUS_SUCCESSFUL),
     * we use a very generous threshold (80%). Rationale:
     *   • HuggingFace model file sizes change across revisions without URL changes.
     *   • Our catalog `fileSizeBytes` is a best-effort estimate, NOT a contract.
     *   • The OS completed the HTTP transfer successfully, including content-length
     *     verification. If the OS says done, the file is almost certainly valid.
     *   • The 80% floor still catches severely truncated files (e.g. 0-byte, HTML
     *     error pages served instead of the binary, or interrupted partial writes).
     *
     * When [trustOS] is false (manual scan, user-picked file), we use a tighter 90%
     * threshold to avoid loading corrupted/partial files that weren't downloaded
     * through DownloadManager.
     */
    fun isFileComplete(file: File, expectedBytes: Long = 0L, trustOS: Boolean = false): Boolean {
        if (!file.exists()) return false
        val size = file.length()
        if (size < 1_000_000L) return false

        // If the actual file is LARGER than expected, it's valid — the catalog estimate
        // was simply outdated. Only flag files that are significantly SMALLER than expected.
        // trustOS = true (OS reported success): allow 10% deviation for HF revision drift.
        // trustOS = false (scan/init): require 99% match to prevent "phantom completions".
        val threshold = if (trustOS) 0.90 else 0.99

        if (expectedBytes > 0L && size < (expectedBytes * threshold).toLong()) {
            Timber.w("File ${file.name}: ${size / 1_048_576}MB of ${expectedBytes / 1_048_576}MB expected — incomplete (threshold: $threshold)")
            return false
        }

        if (file.name.endsWith(".gguf", ignoreCase = true)) {
            return runCatching {
                file.inputStream().use { s ->
                    val magic = ByteArray(4)
                    s.read(magic) == 4 && magic.contentEquals(GGUF_MAGIC)
                }
            }.getOrElse { false }
        }
        return true
    }

    /** Returns absolute paths of files currently being downloaded by Android DownloadManager. */
    fun activeDownloadingPaths(): Set<String> {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED
        )
        val paths = mutableSetOf<String>()
        dm.query(query)?.use { cursor ->
            val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            while (cursor.moveToNext()) {
                val uriStr = cursor.getString(uriCol) ?: continue
                // Robust path extraction: strip "file://" and handle double slashes
                val path = try {
                    val uri = Uri.parse(uriStr)
                    uri.path?.removePrefix("/file:")?.removePrefix("file:")
                } catch (e: Exception) { null }
                if (path != null) paths += path
            }
        }
        return paths
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun enqueueOrReattach(url: String, destFile: File, title: String): Long {
        findActiveDownloadId(url)?.let { return it }

        // Always delete the destination before enqueuing.  Android DownloadManager
        // creates "file-1.gguf", "file-2.gguf" etc. when the destination already
        // exists — this causes filename mismatches and "not downloaded" UI state.
        if (destFile.exists()) {
            Timber.w("Deleting existing file before download: ${destFile.name}")
            destFile.delete()
        }

        return runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(title)
                setDescription("Downloading AI model…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(destFile))
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                // Explicitly allow all network types for background robustness
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)

                // All HuggingFace repos in the catalog are gated (google/, litert-community/)
                // or accept Bearer tokens gracefully (bartowski/).  Always attach the token
                // so Android DownloadManager's initial request is authenticated.
                if (url.contains("huggingface.co")) {
                    val currentToken = hfToken.ifEmpty {
                        kotlinx.coroutines.withTimeoutOrNull(2000) {
                            hfTokenManager.effectiveToken.first()
                        } ?: ""
                    }
                    if (currentToken.isNotEmpty()) {
                        addRequestHeader("Authorization", "Bearer $currentToken")
                    } else {
                        Timber.w("No HF token available — download may fail for gated repos")
                    }
                }
            }
            dm.enqueue(request).also { Timber.d("Enqueued download id=$it  url=$url") }
        }.getOrElse { -1L }
    }


    private fun registerCompletionReceiver(
        modelId: String,
        downloadId: Long,
        tmpFile: File,
        finalFile: File,
        expectedBytes: Long,
    ) {
        lateinit var receiver: BroadcastReceiver
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                // Use goAsync so file I/O and DownloadManager queries run off the main thread.
                val pendingResult = goAsync()
                scope.launch {
                    runCatching { ctx.unregisterReceiver(receiver) }

                    val osSuccess = queryStatus(downloadId) == DownloadManager.STATUS_SUCCESSFUL
                    val isActuallyComplete = isFileComplete(tmpFile, expectedBytes, trustOS = osSuccess)

                    val progress = if (osSuccess && isActuallyComplete) {
                        val moved = atomicMove(tmpFile, finalFile)
                        if (moved) {
                            DebugLogger.log("DOWNLOAD", "Complete (moved): ${finalFile.name}")
                            DownloadProgress.Completed(finalFile.absolutePath)
                        } else {
                            DownloadProgress.Failed("Failed to move to internal storage")
                        }
                    } else {
                        val reason = queryFailureReason(downloadId)
                        DebugLogger.log("DOWNLOAD", "Failed: $reason")
                        DownloadProgress.Failed(reason)
                    }
                    _allProgress.update { it + (modelId to progress) }
                    activeJobs.remove(modelId)
                    pendingResult.finish()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun findActiveDownloadId(url: String): Long? {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED
        )
        dm.query(query)?.use { cursor ->
            val idCol  = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
            while (cursor.moveToNext()) {
                if (cursor.getString(uriCol) == url) return cursor.getLong(idCol)
            }
        }
        return null
    }

    /**
     * Returns the DownloadManager status code for [url], or null if no record exists.
     * Unlike [findActiveDownloadId], this queries ALL statuses including SUCCESSFUL and FAILED.
     * Used by [restoreCompletedStates] to distinguish a completed download from a failed one —
     * both leave the partial/complete file on disk, but only SUCCESSFUL is safe to mark as done.
     */
    private fun findDownloadStatus(url: String): Int? {
        dm.query(DownloadManager.Query())?.use { cursor ->
            val uriCol    = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
            val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            while (cursor.moveToNext()) {
                if (cursor.getString(uriCol) == url) return cursor.getInt(statusCol)
            }
        }
        return null
    }

    private fun cancelByUrl(url: String, localFile: File) {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED
        )
        dm.query(query)?.use { cursor ->
            val idCol  = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
            while (cursor.moveToNext()) {
                if (cursor.getString(uriCol) == url) dm.remove(cursor.getLong(idCol))
            }
        }
        localFile.delete()
    }

    private fun queryProgress(downloadId: Long): DownloadProgress? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        return dm.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status     = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total      = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason     = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (status) {
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PENDING -> DownloadProgress.Downloading(downloaded, total)
                DownloadManager.STATUS_PAUSED  -> DownloadProgress.Paused(pauseReason(reason))
                DownloadManager.STATUS_FAILED  -> DownloadProgress.Failed(queryFailureReason(downloadId))
                DownloadManager.STATUS_SUCCESSFUL -> {
                    // Polling loop reaches here when download completes. The BroadcastReceiver
                    // handles the "official" completion event, but returning null here causes
                    // the UI to stick at 99%. Emit Completed to break the loop cleanly.
                    val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val localUri = if (columnIndex >= 0) cursor.getString(columnIndex) else null
                    val path = localUri?.let { Uri.parse(it).path } ?: ""
                    DebugLogger.log("DOWNLOAD", "queryProgress: STATUS_SUCCESSFUL  path=$path  size=${downloaded}B")
                    null // Exit the polling loop — the BroadcastReceiver handles the Completed emit
                }
                else -> null
            }
        }
    }

    private fun pauseReason(code: Int) = when (code) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI    -> "Waiting for Wi-Fi — connect to Wi-Fi or enable mobile data downloads in Settings."
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network…"
        DownloadManager.PAUSED_WAITING_TO_RETRY    -> "Will retry shortly…"
        else                                       -> "Download paused."
    }

    private fun queryFailureReason(downloadId: Long): String {
        val query = DownloadManager.Query().setFilterById(downloadId)
        return dm.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return "Download failed"
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (reason) {
                DownloadManager.ERROR_INSUFFICIENT_SPACE  -> "Not enough storage. Free space and try again."
                DownloadManager.ERROR_FILE_ERROR          -> "Storage write error. Check available space."
                DownloadManager.ERROR_HTTP_DATA_ERROR     -> "Network error. Check your connection and retry."
                DownloadManager.ERROR_TOO_MANY_REDIRECTS  -> "Too many redirects — download failed."
                DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unexpected server response."
                401, 403 -> "Access denied (HTTP $reason) — HuggingFace token may be expired."
                404      -> "Model not found at URL (HTTP 404)."
                else     -> "Download failed (code $reason)."
            }
        } ?: "Download failed"
    }

    private fun queryStatus(downloadId: Long): Int {
        val query = DownloadManager.Query().setFilterById(downloadId)
        return dm.query(query)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            else DownloadManager.STATUS_FAILED
        } ?: DownloadManager.STATUS_FAILED
    }

    /** Simple flow for small downloads (LoRA adapters) that don't need app-lifetime tracking. */
    private fun legacyDownloadFlow(
        url: String, destFile: File, title: String, expectedBytes: Long,
    ): Flow<DownloadProgress> = kotlinx.coroutines.flow.callbackFlow {
        if (isFileComplete(destFile, expectedBytes)) {
            trySend(DownloadProgress.Completed(destFile.absolutePath)); close(); return@callbackFlow
        }
        val downloadId = enqueueOrReattach(url, destFile, title)
        if (downloadId == -1L) { trySend(DownloadProgress.Failed("Failed to start download")); close(); return@callbackFlow }
        while (true) {
            val p = queryProgress(downloadId) ?: break
            trySend(p)
            delay(if (p is DownloadProgress.Paused) 3_000L else 600L)
        }
        awaitClose {}
    }
}
