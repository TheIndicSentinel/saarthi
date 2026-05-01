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
        File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    fun adaptersDir(): File =
        File(context.getExternalFilesDir(null), "adapters").also { it.mkdirs() }

    fun localPathFor(model: ModelEntry): File = File(modelsDir(), model.fileName)
    fun localPathFor(lora: LoraEntry): File   = File(adaptersDir(), lora.fileName)

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

        // resolveLocalFile renames any DownloadManager-suffixed file to the canonical path
        val destFile = resolveLocalFile(model)
        if (isFileComplete(destFile, model.fileSizeBytes)) {
            _allProgress.update { it + (model.id to DownloadProgress.Completed(destFile.absolutePath)) }
            return
        }

        val job = scope.launch {
            val downloadId = enqueueOrReattach(model.downloadUrl, destFile, model.displayName)
            if (downloadId == -1L) {
                _allProgress.update { it + (model.id to DownloadProgress.Failed("Failed to start download")) }
                return@launch
            }

            // Register a one-shot broadcast receiver for completion
            registerCompletionReceiver(model.id, downloadId, destFile, model.fileSizeBytes)

            // Poll progress until DownloadManager reports done or failure
            while (true) {
                val progress = queryProgress(downloadId) ?: break
                _allProgress.update { it + (model.id to progress) }
                delay(if (progress is DownloadProgress.Paused) 3_000L else 600L)
            }

            // The polling loop exited (STATUS_SUCCESSFUL or cursor gone).
            // The BroadcastReceiver *may* have already fired, but in a race condition
            // it might not yet have. Check the file directly and emit Completed if valid.
            // This prevents the UI from staying stuck at 99%.
            delay(500) // Small delay to let DownloadManager flush to disk
            if (isFileComplete(destFile, model.fileSizeBytes)) {
                DebugLogger.log("DOWNLOAD", "Success (poll-exit): ${destFile.name}  ${destFile.length() / 1_048_576}MB")
                _allProgress.update { it + (model.id to DownloadProgress.Completed(destFile.absolutePath)) }
            } else {
                // Check if maybe the status is actually failed
                val finalStatus = queryStatus(downloadId)
                if (finalStatus == DownloadManager.STATUS_FAILED) {
                    val reason = queryFailureReason(downloadId)
                    DebugLogger.log("DOWNLOAD", "Failed (poll-exit): $reason")
                    _allProgress.update { it + (model.id to DownloadProgress.Failed(reason)) }
                }
                // Otherwise let the BroadcastReceiver handle it
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
     * Called on ViewModel init — reattaches progress polling for any model

     * whose download is still active in Android DownloadManager.
     */
    fun reattachActiveDownloads(models: List<ModelEntry>) {
        val activePaths = activeDownloadingPaths()
        models.forEach { model ->
            val modelPath = localPathFor(model).absolutePath
            if (activePaths.any { it == modelPath } && activeJobs[model.id]?.isActive != true) {
                DebugLogger.log("DOWNLOAD", "Reattaching to in-progress download: ${model.id}")
                startDownload(model)
            }
        }
    }

    // ── Lora downloads (simple flow — adapters are small) ─────────────────────

    fun downloadLora(lora: LoraEntry): Flow<DownloadProgress> =
        legacyDownloadFlow(lora.downloadUrl, localPathFor(lora), lora.displayName, lora.fileSizeBytes)

    // ── File validation ───────────────────────────────────────────────────────

    fun isFileComplete(file: File, expectedBytes: Long = 0L): Boolean {
        if (!file.exists()) return false
        val size = file.length()
        if (size < 1_000_000L) return false
        // Threshold tightened to 99.5%. LiteRT models (.task/.litertlm) are 
        // deterministic in size. 90% was too loose and allowed corrupted 
        // partial files to attempt to load, causing native crashes.
        if (expectedBytes > 0L && size < (expectedBytes * 0.995).toLong()) {
            Timber.w("File ${file.name}: ${size / 1_048_576}MB of ${expectedBytes / 1_048_576}MB expected — incomplete")
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
        destFile: File,
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

                    val progress = if (queryStatus(downloadId) == DownloadManager.STATUS_SUCCESSFUL &&
                                       isFileComplete(destFile, expectedBytes)) {
                        DebugLogger.log("DOWNLOAD", "Complete: ${destFile.name}  ${destFile.length() / 1_048_576}MB")
                        DownloadProgress.Completed(destFile.absolutePath)
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
