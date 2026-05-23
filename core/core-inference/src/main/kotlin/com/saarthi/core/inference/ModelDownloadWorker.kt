package com.saarthi.core.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown when the server returns a terminal HTTP error (401/403/404). */
private class DownloadHttpException(val code: Int, message: String) : IOException(message)

/**
 * CoroutineWorker that downloads a model file using OkHttp with HTTP Range
 * headers, enabling byte-exact resumption if the download is interrupted.
 *
 * Runs as a Foreground Worker (persistent notification) on Android 12+
 * via WorkManager's SystemForegroundService, keeping the process alive
 * during multi-GB downloads even when the screen locks or Samsung's
 * aggressive Doze kicks in.
 *
 * Foreground service type: SPECIAL_USE (not DATA_SYNC — that type was
 * removed in Android 16 / API 36, causing SecurityException on SM-S918B).
 *
 * Progress is reported via setProgress() and WorkInfo.progress so the UI
 * can observe downloaded / total bytes without holding a direct reference
 * to this worker instance.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_URL        = "download_url"
        const val KEY_TMP_PATH   = "tmp_path"
        const val KEY_DEST_PATH  = "dest_path"
        const val KEY_TITLE      = "title"
        const val KEY_HF_TOKEN   = "hf_token"
        const val KEY_DOWNLOADED = "downloaded_bytes"
        const val KEY_TOTAL      = "total_bytes"
        const val KEY_FINAL_PATH = "final_path"
        const val KEY_ERROR_MSG  = "error_msg"

        private const val NOTIF_ID   = 9001
        private const val CHANNEL_ID = "saarthi_download"

        // Rate-limit foreground notification updates so we don't spam
        // WorkManager's binder during fast downloads on 5G / Wi-Fi.
        private const val PROGRESS_REPORT_INTERVAL_MS = 800L
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    // Required by WorkManager when setExpedited() is used: called before doWork()
    // on Android 12+ when running as an expedited job to supply the notification
    // if WorkManager needs to promote the job to a Foreground Service.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(0L, 0L, inputData.getString(KEY_TITLE) ?: "Downloading model…")

    override suspend fun doWork(): Result {
        val url      = inputData.getString(KEY_URL)       ?: return Result.failure(workDataOf(KEY_ERROR_MSG to "Missing URL"))
        val tmpPath  = inputData.getString(KEY_TMP_PATH)  ?: return Result.failure(workDataOf(KEY_ERROR_MSG to "Missing tmp path"))
        val destPath = inputData.getString(KEY_DEST_PATH) ?: return Result.failure(workDataOf(KEY_ERROR_MSG to "Missing dest path"))
        val title    = inputData.getString(KEY_TITLE)     ?: "Downloading model…"
        val token    = inputData.getString(KEY_HF_TOKEN)  ?: ""

        val tmpFile  = File(tmpPath).also { it.parentFile?.mkdirs() }
        val destFile = File(destPath).also { it.parentFile?.mkdirs() }

        setForeground(buildForegroundInfo(0L, 0L, title))

        return try {
            downloadWithResume(url, tmpFile, token, title)

            // Atomic move to final destination — rename is faster and avoids
            // a partial write window; fall back to copy+delete if cross-device.
            if (destFile.exists()) destFile.delete()
            val moved = tmpFile.renameTo(destFile)
            if (!moved) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }

            DebugLogger.log("DOWNLOAD_WORKER", "Complete: ${destFile.name}  size=${destFile.length() / 1_048_576}MB")
            Result.success(workDataOf(KEY_FINAL_PATH to destFile.absolutePath))

        } catch (e: DownloadHttpException) {
            // Terminal HTTP error (401/403/404) — retrying won't help.
            Timber.e(e, "Terminal HTTP ${e.code} downloading ${tmpFile.name}")
            DebugLogger.log("DOWNLOAD_WORKER", "Terminal HTTP ${e.code}: ${e.message}")
            Result.failure(workDataOf(KEY_ERROR_MSG to (e.message ?: "Download failed (HTTP ${e.code})")))
        } catch (e: Exception) {
            if (isStopped) {
                // Cancelled by user — keep partial tmp file for future resume.
                DebugLogger.log("DOWNLOAD_WORKER", "Cancelled: ${tmpFile.name}  partial=${tmpFile.length() / 1_048_576}MB kept for resume")
                Result.failure(workDataOf(KEY_ERROR_MSG to "Cancelled"))
            } else {
                // Transient network / IO error — WorkManager retries with back-off.
                Timber.w(e, "Transient download error, will retry: ${tmpFile.name}")
                DebugLogger.log("DOWNLOAD_WORKER", "Retry on: ${e.message}")
                Result.retry()
            }
        }
    }

    private suspend fun downloadWithResume(
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
                // byte we have. HTTP 206 Partial Content confirms resumption; the server
                // may return 200 (full file) if it doesn't support Range — we handle both.
                if (existingBytes > 0L) addHeader("Range", "bytes=$existingBytes-")
                if (token.isNotEmpty()) addHeader("Authorization", "Bearer $token")
            }
            .build()

        val response = withContext(Dispatchers.IO) { http.newCall(request).execute() }
        val code = response.code
        if (!response.isSuccessful && code != 206) {
            response.close()
            // Terminal errors: don't retry — throw typed exception so the catch
            // block in doWork() can distinguish them from transient network failures.
            if (code == 401 || code == 403 || code == 404) {
                val msg = when (code) {
                    401, 403 -> "Access denied (HTTP $code) — HuggingFace token may be invalid or expired."
                    404      -> "Model not found at download URL (HTTP 404)."
                    else     -> "HTTP $code: ${response.message}"
                }
                throw DownloadHttpException(code, msg)
            }
            throw IOException("HTTP $code: ${response.message}")
        }

        val body = checkNotNull(response.body) { "Response body was null for HTTP $code" }

        // Derive total file size: Content-Range header is authoritative for 206
        // responses ("bytes START-END/TOTAL"), Content-Length for 200 responses.
        val totalBytes: Long = response.headers["Content-Range"]
            ?.substringAfterLast('/')?.trim()?.toLongOrNull()
            ?: body.contentLength().takeIf { it >= 0L }?.plus(existingBytes)
            ?: 0L

        withContext(Dispatchers.IO) {
            body.byteStream().use { input ->
                // append=true so we write after the bytes already on disk
                FileOutputStream(tmpFile, existingBytes > 0L).use { output ->
                    val buffer = ByteArray(32 * 1024)  // 32 KB per read
                    var bytesRead: Int
                    var totalWritten = existingBytes
                    var lastReportMs = System.currentTimeMillis()

                    while (input.read(buffer).also { bytesRead = it } >= 0) {
                        if (isStopped) return@withContext  // cancelled — bail fast, keep partial
                        output.write(buffer, 0, bytesRead)
                        totalWritten += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastReportMs >= PROGRESS_REPORT_INTERVAL_MS) {
                            lastReportMs = now
                            setProgress(workDataOf(KEY_DOWNLOADED to totalWritten, KEY_TOTAL to totalBytes))
                            setForeground(buildForegroundInfo(totalWritten, totalBytes, title))
                        }
                    }
                    // Final progress flush so the UI hits 100% before Completed state.
                    setProgress(workDataOf(KEY_DOWNLOADED to totalWritten, KEY_TOTAL to totalBytes))
                }
            }
        }
    }

    private fun buildForegroundInfo(
        downloaded: Long,
        total: Long,
        title: String,
    ): ForegroundInfo {
        ensureChannel()

        val notif = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (total > 0L) {
                    val pct  = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                    val dlMb  = downloaded / 1_048_576
                    val totMb = total / 1_048_576
                    setContentText("$pct%  ·  ${dlMb}MB / ${totMb}MB")
                    setProgress(100, pct, false)
                } else {
                    setContentText("Starting download…")
                    setProgress(0, 0, true)
                }
            }
            .build()

        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is stable on API 29–36+.
        // DATA_SYNC was removed in Android 16 (API 36) and causes
        // SecurityException on the SM-S918B — see AndroidManifest.xml comment.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("InlinedApi")
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress while AI models are downloaded in the background"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

}
