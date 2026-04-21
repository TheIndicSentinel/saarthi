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
import com.saarthi.core.inference.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// GGUF magic: 'G','G','U','F' = 0x47,0x47,0x55,0x46
private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun modelsDir(): File =
        File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    fun adaptersDir(): File =
        File(context.getExternalFilesDir(null), "adapters").also { it.mkdirs() }

    fun localPathFor(model: ModelEntry): File = File(modelsDir(),    model.fileName)
    fun localPathFor(lora: LoraEntry):   File = File(adaptersDir(), lora.fileName)

    fun isDownloaded(model: ModelEntry): Boolean =
        isFileComplete(localPathFor(model), model.fileSizeBytes)

    fun isDownloaded(lora: LoraEntry): Boolean =
        isFileComplete(localPathFor(lora), lora.fileSizeBytes)

    fun download(model: ModelEntry): Flow<DownloadProgress> =
        downloadFile(model.downloadUrl, localPathFor(model), model.displayName, model.fileSizeBytes)

    fun downloadLora(lora: LoraEntry): Flow<DownloadProgress> =
        downloadFile(lora.downloadUrl, localPathFor(lora), lora.displayName, lora.fileSizeBytes)

    fun cancelDownload(model: ModelEntry) = cancelByUrl(model.downloadUrl, localPathFor(model))
    fun cancelDownload(lora: LoraEntry)   = cancelByUrl(lora.downloadUrl,  localPathFor(lora))

    /** Returns the set of file paths currently being actively downloaded. */
    fun activeDownloadingPaths(): Set<String> {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or
            DownloadManager.STATUS_RUNNING or
            DownloadManager.STATUS_PAUSED
        )
        val paths = mutableSetOf<String>()
        dm.query(query)?.use { cursor ->
            val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            while (cursor.moveToNext()) {
                if (uriCol >= 0) {
                    val localUri = cursor.getString(uriCol) ?: continue
                    Uri.parse(localUri).path?.let { paths += it }
                }
            }
        }
        return paths
    }

    // ── File validation ───────────────────────────────────────────────────────

    /**
     * A file is complete when:
     * 1. It exists and is at least 1 MB
     * 2. Its size is within 2% of [expectedBytes] (guards against partial downloads)
     * 3. For GGUF files: the first 4 bytes are the GGUF magic
     *
     * Passing expectedBytes = 0 skips the size check (used for scanned files
     * whose expected size is unknown).
     */
    fun isFileComplete(file: File, expectedBytes: Long = 0L): Boolean {
        if (!file.exists()) return false
        val size = file.length()
        if (size < 1_000_000L) return false
        // Size check: must be >= 98% of expected (tolerates minor server-side differences)
        if (expectedBytes > 0L && size < (expectedBytes * 0.98).toLong()) {
            Timber.w("File ${file.name} is incomplete: ${size / 1_048_576}MB of ${expectedBytes / 1_048_576}MB")
            return false
        }
        if (!file.name.endsWith(".gguf", ignoreCase = true)) return true
        return runCatching {
            file.inputStream().use { s ->
                val magic = ByteArray(4)
                s.read(magic) == 4 && magic.contentEquals(GGUF_MAGIC)
            }
        }.getOrElse { false }
    }

    // ── Core implementation ───────────────────────────────────────────────────

    private fun downloadFile(
        url: String,
        destFile: File,
        title: String,
        expectedBytes: Long,
    ): Flow<DownloadProgress> = callbackFlow {
        if (isFileComplete(destFile, expectedBytes)) {
            trySend(DownloadProgress.Completed(destFile.absolutePath))
            close()
            return@callbackFlow
        }
        if (destFile.exists()) {
            Timber.w("Deleting incomplete file: ${destFile.name} (${destFile.length() / 1_048_576}MB of ${expectedBytes / 1_048_576}MB expected)")
            destFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(title)
            setDescription("Downloading AI model…")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(destFile))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        val downloadId = dm.enqueue(request)
        Timber.d("Download enqueued id=$downloadId  url=$url  expected=${expectedBytes / 1_048_576}MB")

        registerCompletionReceiver(downloadId, destFile, expectedBytes)

        while (true) {
            val progress = queryProgress(downloadId) ?: break
            trySend(progress)
            delay(500)
        }

        awaitClose {
            if (!isFileComplete(destFile, expectedBytes)) dm.remove(downloadId)
        }
    }

    private fun ProducerScope<DownloadProgress>.registerCompletionReceiver(
        downloadId: Long,
        destFile: File,
        expectedBytes: Long,
    ) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                if (queryStatus(downloadId) == DownloadManager.STATUS_SUCCESSFUL) {
                    val actualSize = destFile.length()
                    val complete = isFileComplete(destFile, expectedBytes)
                    DebugLogger.log("DOWNLOAD", "STATUS_SUCCESSFUL  file=${destFile.name}  actualSize=${actualSize/1_048_576}MB  expectedSize=${expectedBytes/1_048_576}MB  isComplete=$complete  path=${destFile.absolutePath}")
                    if (complete) {
                        trySend(DownloadProgress.Completed(destFile.absolutePath))
                    } else {
                        destFile.delete()
                        trySend(DownloadProgress.Failed(
                            "Download incomplete — file is ${actualSize / 1_048_576}MB " +
                            "but expected ${expectedBytes / 1_048_576}MB. Please try again."
                        ))
                    }
                } else {
                    trySend(DownloadProgress.Failed(queryFailureReason(downloadId)))
                }
                close()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }

        invokeOnClose { runCatching { context.unregisterReceiver(receiver) } }
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
            when (status) {
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PENDING  -> DownloadProgress.Downloading(downloaded, total)
                DownloadManager.STATUS_FAILED   -> DownloadProgress.Failed(queryFailureReason(downloadId))
                else                            -> null
            }
        }
    }

    private fun queryFailureReason(downloadId: Long): String {
        val query = DownloadManager.Query().setFilterById(downloadId)
        return dm.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return "Download failed"
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (reason) {
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space"
                DownloadManager.ERROR_FILE_ERROR         -> "Storage write error"
                401, 403 -> "Model requires HuggingFace login (HTTP $reason)"
                404      -> "Model file not found at URL (HTTP 404)"
                else     -> "Download failed (code $reason)"
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
}
