package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.KisanPackPreference
import com.saarthi.core.inference.DebugLogger
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

sealed class PackUpdateOutcome {
    /** [KisanPackRemoteConfig.manifestUrl] is empty — nothing to fetch. */
    data object Unavailable : PackUpdateOutcome()
    /** Another check (in-app tap or WorkManager) is already running. */
    data object Busy : PackUpdateOutcome()
    data object UpToDate : PackUpdateOutcome()
    data class Updated(val fromVersion: Int, val toVersion: Int) : PackUpdateOutcome()
    data object AppTooOld : PackUpdateOutcome()
    /** Verify/parse failed — current pack left untouched. */
    data object KeptCurrent : PackUpdateOutcome()
    data object NetworkFailed : PackUpdateOutcome()
}

internal interface PackHttp {
    fun getString(url: String): String
    fun getBytes(url: String): ByteArray
}

/**
 * Shared Kisan pack update path used by [com.saarthi.app.packs.PackUpdateWorker]
 * and the in-app refresh tap. Always goes through
 * [KisanPackInstaller.installVerified] — SHA-256 + signature are never skipped.
 *
 * A process-wide [Mutex] so a tap and the 24h worker cannot install at once.
 */
@Singleton
class PackUpdateChecker @Inject constructor(
    private val installer: KisanPackInstaller,
    private val preference: KisanPackPreference,
    private val remote: KisanPackRemoteConfig,
) {
    internal constructor(
        installer: KisanPackInstaller,
        preference: KisanPackPreference,
        remote: KisanPackRemoteConfig,
        http: PackHttp,
    ) : this(installer, preference, remote) {
        this.http = http
    }

    private var http: PackHttp = DefaultPackHttp
    private val mutex = Mutex()

    val isRemoteConfigured: Boolean
        get() = remote.manifestUrl.isNotBlank()

    suspend fun checkAndInstall(): PackUpdateOutcome {
        if (remote.manifestUrl.isBlank()) {
            DebugLogger.log("PACK", "Update check skipped — KISAN_PACK_MANIFEST_URL is empty")
            return PackUpdateOutcome.Unavailable
        }
        if (!mutex.tryLock()) {
            DebugLogger.log("PACK", "Update check skipped — another check is in progress")
            return PackUpdateOutcome.Busy
        }
        try {
            val manifest = runCatching { parseManifest(http.getString(remote.manifestUrl)) }.getOrElse { e ->
                DebugLogger.log("PACK", "Manifest fetch failed (${e.message}) — will retry later")
                Timber.w(e, "Pack manifest fetch failed")
                return PackUpdateOutcome.NetworkFailed
            }

            preference.recordUpdateCheck()

            if (manifest.minAppVersionCode > remote.appVersionCode) {
                DebugLogger.log(
                    "PACK",
                    "Kisan pack v${manifest.packVersion} needs app >= ${manifest.minAppVersionCode} " +
                        "(this app ${remote.appVersionCode}) — skipping",
                )
                return PackUpdateOutcome.AppTooOld
            }

            val installedVersion = preference.installedVersion.value
            if (manifest.packVersion <= installedVersion) {
                DebugLogger.log(
                    "PACK",
                    "Kisan pack already up to date (installed v$installedVersion, manifest v${manifest.packVersion})",
                )
                return PackUpdateOutcome.UpToDate
            }

            DebugLogger.log(
                "PACK",
                "Kisan pack update available: v$installedVersion → v${manifest.packVersion}; downloading ${manifest.downloadUrl}",
            )

            val bytes = runCatching { http.getBytes(manifest.downloadUrl) }.getOrElse { e ->
                DebugLogger.log("PACK", "Pack download failed (${e.message}) — will retry later")
                Timber.w(e, "Pack download failed")
                return PackUpdateOutcome.NetworkFailed
            }

            val newVersion = installer.installVerified(
                rawBytes = bytes,
                expectedSha256 = manifest.sha256,
                signatureB64 = manifest.signature,
                publicKeyB64 = remote.publicKeyB64,
                source = "manifest:v${manifest.packVersion}",
            )

            if (newVersion == null) {
                DebugLogger.log(
                    "PACK",
                    "Kisan pack v${manifest.packVersion} not installed (verification or parse failed) — keeping current pack",
                )
                return PackUpdateOutcome.KeptCurrent
            }
            return PackUpdateOutcome.Updated(fromVersion = installedVersion, toVersion = newVersion)
        } finally {
            mutex.unlock()
        }
    }

    internal data class ManifestPayload(
        val packVersion: Int,
        val downloadUrl: String,
        val sha256: String,
        val signature: String,
        val minAppVersionCode: Int,
    )

    companion object {
        /**
         * Field extraction without [org.json.JSONObject] — that class is a no-op
         * stub in JVM unit tests (this project has no Robolectric).
         */
        internal fun parseManifest(raw: String): ManifestPayload {
            if (!raw.contains("\"pack\"")) error("manifest: missing 'pack' object")
            val version = jsonInt(raw, "packVersion")?.takeIf { it > 0 }
                ?: jsonInt(raw, "version")?.takeIf { it > 0 }
                ?: error("manifest: invalid packVersion")
            return ManifestPayload(
                packVersion = version,
                downloadUrl = jsonString(raw, "url")?.takeIf { it.isNotBlank() }
                    ?: error("manifest: missing pack.url"),
                sha256 = jsonString(raw, "sha256")?.takeIf { it.isNotBlank() }
                    ?: error("manifest: missing pack.sha256"),
                signature = jsonString(raw, "signature")?.takeIf { it.isNotBlank() }
                    ?: error("manifest: missing pack.signature"),
                minAppVersionCode = jsonInt(raw, "minAppVersionCode") ?: 0,
            )
        }

        private fun jsonInt(raw: String, key: String): Int? =
            Regex(""""$key"\s*:\s*(-?\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()

        private fun jsonString(raw: String, key: String): String? =
            Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""").find(raw)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
    }
}

private object DefaultPackHttp : PackHttp {
    override fun getString(url: String): String = open(url, connectMs = 15_000, readMs = 15_000) { conn ->
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode} from manifest")
        conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
    }

    override fun getBytes(url: String): ByteArray = open(url, connectMs = 30_000, readMs = 60_000) { conn ->
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode} from pack download")
        conn.inputStream.readBytes()
    }

    private fun <T> open(url: String, connectMs: Int, readMs: Int, read: (HttpURLConnection) -> T): T {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            return read(conn)
        } finally {
            conn.disconnect()
        }
    }
}
