package com.saarthi.app.packs

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.saarthi.app.BuildConfig
import com.saarthi.app.R
import com.saarthi.core.i18n.KisanPackPreference
import com.saarthi.core.inference.DebugLogger
import com.saarthi.feature.assistant.data.KisanPackInstaller
import com.saarthi.feature.assistant.data.ReminderManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Background worker that polls the Kisan-pack manifest URL, downloads
 * a newer pack snapshot when the server's version is ahead, hands the
 * JSON to [KisanPackInstaller] and posts a "pack updated" notification.
 *
 * Plain [CoroutineWorker] (no foreground service): manifest + pack JSON
 * are tiny compared to the multi-GB model files that need
 * ModelDownloadWorker's FGS protection. No new Gradle deps needed:
 * dependencies are pulled in via Hilt's [EntryPoint] pattern rather
 * than @HiltWorker (which requires `androidx.hilt:hilt-work` + a custom
 * `WorkerFactory` we don't have set up).
 *
 * No-op safety nets:
 *  • [BuildConfig.KISAN_PACK_MANIFEST_URL] empty → exits cleanly so dev /
 *    CI builds never try to contact a non-existent server.
 *  • Manifest `version` ≤ installed version → exits cleanly.
 *  • Any HTTP / IO failure → [Result.retry] so WorkManager backs off.
 *
 * Schedule + constraints: see [PackUpdateScheduler] — UNMETERED, idle,
 * battery-not-low, 24 h cadence.
 */
class PackUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun installer(): KisanPackInstaller
        fun preference(): KisanPackPreference
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val manifestUrl = BuildConfig.KISAN_PACK_MANIFEST_URL
        if (manifestUrl.isBlank()) {
            DebugLogger.log("PACK", "Update check skipped — KISAN_PACK_MANIFEST_URL is empty")
            return@withContext Result.success()
        }

        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val installer = deps.installer()
        val preference = deps.preference()

        val manifest = runCatching { fetchManifest(manifestUrl) }.getOrElse { e ->
            DebugLogger.log("PACK", "Manifest fetch failed (${e.message}) — will retry later")
            Timber.w(e, "Pack manifest fetch failed")
            return@withContext Result.retry()
        }

        preference.recordUpdateCheck()

        // App-too-old gate: a pack whose schema needs a newer app must not install.
        if (manifest.minAppVersionCode > BuildConfig.VERSION_CODE) {
            DebugLogger.log(
                "PACK",
                "Kisan pack v${manifest.packVersion} needs app >= ${manifest.minAppVersionCode} (this app ${BuildConfig.VERSION_CODE}) — skipping",
            )
            return@withContext Result.success()
        }

        val installedVersion = preference.installedVersion.value
        if (manifest.packVersion <= installedVersion) {
            DebugLogger.log("PACK", "Kisan pack already up to date (installed v$installedVersion, manifest v${manifest.packVersion})")
            return@withContext Result.success()
        }

        DebugLogger.log("PACK", "Kisan pack update available: v$installedVersion → v${manifest.packVersion}; downloading ${manifest.downloadUrl}")

        val bytes = runCatching { fetchPackBytes(manifest.downloadUrl) }.getOrElse { e ->
            DebugLogger.log("PACK", "Pack download failed (${e.message}) — will retry later")
            Timber.w(e, "Pack download failed")
            return@withContext Result.retry()
        }

        // Verify (SHA-256 + ECDSA signature) THEN install. Rejection / parse
        // failure returns null and the current pack stays (install rollback).
        val newVersion = installer.installVerified(
            rawBytes = bytes,
            expectedSha256 = manifest.sha256,
            signatureB64 = manifest.signature,
            publicKeyB64 = BuildConfig.KISAN_PACK_PUBLIC_KEY,
            source = "manifest:v${manifest.packVersion}",
        )

        if (newVersion == null) {
            // Bad signature / hash / parse / empty. Don't hammer-retry a
            // tampered or malformed pack — re-check on the next 24h cycle.
            DebugLogger.log("PACK", "Kisan pack v${manifest.packVersion} not installed (verification or parse failed) — keeping current pack")
            return@withContext Result.success()
        }

        notifyPackUpdated(manifest.title.ifBlank { "Kisan Knowledge" }, newVersion)
        Result.success()
    }

    // ── HTTP plumbing (HttpURLConnection — no extra dep needed) ───────

    private data class ManifestPayload(
        val packVersion: Int,
        val downloadUrl: String,
        val sha256: String,
        val signature: String,
        val minAppVersionCode: Int,
        val language: String,
        val title: String,
    )

    /**
     * Parse the v2 manifest:
     * `{ packVersion, minAppVersionCode, language, title,
     *    pack: { url, sha256, signature, signatureAlgo, keyId } }`.
     * `version` is accepted as a fallback for `packVersion`.
     */
    private fun fetchManifest(url: String): ManifestPayload {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            instanceFollowRedirects = true  // GitHub releases/latest → asset CDN
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) error("HTTP $code from manifest")
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val root = JSONObject(raw)
            val pack = root.optJSONObject("pack") ?: error("manifest: missing 'pack' object")
            val version = root.optInt("packVersion", root.optInt("version", 0)).takeIf { it > 0 }
                ?: error("manifest: invalid packVersion")
            return ManifestPayload(
                packVersion       = version,
                downloadUrl       = pack.optString("url").takeIf { it.isNotBlank() } ?: error("manifest: missing pack.url"),
                sha256            = pack.optString("sha256").takeIf { it.isNotBlank() } ?: error("manifest: missing pack.sha256"),
                signature         = pack.optString("signature").takeIf { it.isNotBlank() } ?: error("manifest: missing pack.signature"),
                minAppVersionCode = root.optInt("minAppVersionCode", 0),
                language          = root.optString("language", "en"),
                title             = root.optString("title", "Kisan Knowledge"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchPackBytes(downloadUrl: String): ByteArray {
        val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) error("HTTP $code from pack download")
            return conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    // ── User-facing notification ──────────────────────────────────────

    private fun notifyPackUpdated(packTitle: String, version: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val tapIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val tapPi = PendingIntent.getActivity(
            applicationContext, NOTIF_ID, tapIntent ?: android.content.Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, ReminderManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🌾 $packTitle updated")
            .setContentText("Saarthi has refreshed the Kisan knowledge pack to v$version with the latest Govt data.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Saarthi has refreshed the Kisan knowledge pack to v$version with the latest Govt data sources. Open the chat and ask in Kisan mode to use it.",
            ))
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIF_ID, notification)
    }

    companion object {
        const val NOTIF_ID = 815_001
    }
}
