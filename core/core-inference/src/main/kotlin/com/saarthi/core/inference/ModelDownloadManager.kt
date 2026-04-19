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
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    // ── Directory helpers ─────────────────────────────────────────────────────

    fun modelsDir(): File =
        File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    fun adaptersDir(): File =
        File(context.getExternalFilesDir(null), "adapters").also { it.mkdirs() }

    fun localPathFor(model: ModelEntry): File = File(modelsDir(),   model.fileName)
    fun localPathFor(lora: LoraEntry):   File = File(adaptersDir(), lora.fileName)

    fun isDownloaded(model: ModelEntry): Boolean = localPathFor(model).exists()
    fun isDownloaded(lora: LoraEntry):   Boolean = localPathFor(lora).exists()

    // ── Download flows ────────────────────────────────────────────────────────

    fun download(model: ModelEntry): Flow<DownloadProgress> =
        downloadFile(model.downloadUrl, localPathFor(model), model.displayName)

    fun downloadLora(lora: LoraEntry): Flow<DownloadProgress> =
        downloadFile(lora.downloadUrl, localPathFor(lora), lora.displayName)

    // ── Cancel ────────────────────────────────────────────────────────────────

    fun cancelDownload(model: ModelEntry) = cancelByUrl(model.downloadUrl, localPathFor(model))
    fun cancelDownload(lora: LoraEntry)   = cancelByUrl(lora.downloadUrl,  localPathFor(lora))

    // ── Core implementation ───────────────────────────────────────────────────

    private fun downloadFile(
        url: String,
        destFile: File,
        title: String,
    ): Flow<DownloadProgress> = callbackFlow {
        if (destFile.exists()) {
            trySend(DownloadProgress.Completed(destFile.absolutePath))
            close()
            return@callbackFlow
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
        Timber.d("Download enqueued id=$downloadId  url=$url")

        registerCompletionReceiver(downloadId, destFile)

        // Poll progress until completion receiver fires
        while (true) {
            val progress = queryProgress(downloadId) ?: break
            trySend(progress)
            delay(500)
        }

        awaitClose {
            if (!destFile.exists()) dm.remove(downloadId)
        }
    }

    private fun ProducerScope<DownloadProgress>.registerCompletionReceiver(
        downloadId: Long,
        destFile: File,
    ) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                if (queryStatus(downloadId) == DownloadManager.STATUS_SUCCESSFUL) {
                    trySend(DownloadProgress.Completed(destFile.absolutePath))
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
                DownloadManager.ERROR_HTTP_DATA_ERROR    -> "HTTP error (bad data from server)"
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space"
                DownloadManager.ERROR_FILE_ERROR         -> "File write error"
                DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> {
                    val httpCode = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    "HTTP $httpCode — model may require login at huggingface.co"
                }
                400 -> "HTTP 400 Bad Request"
                401, 403 -> "HTTP $reason — model requires HuggingFace login (gated)"
                404 -> "HTTP 404 — model file not found at URL"
                else -> "Download failed (code $reason)"
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
