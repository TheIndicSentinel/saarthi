package com.saarthi.core.inference

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.saarthi.core.inference.model.DownloadProgress
import com.saarthi.core.inference.model.ModelEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// GGUF magic: 'G','G','U','F'
private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

/**
 * Manages model downloads. The actual byte transfer runs in
 * [ModelDownloadService] — a foreground service started directly from the
 * user's tap.
 *
 * WHY a foreground service instead of WorkManager:
 * WorkManager schedules through the platform JobScheduler, which OEM Android
 * skins (OxygenOS/ColorOS/RealmeUI, MIUI, FuntouchOS, One UI deep-sleep, …)
 * routinely refuse to dispatch for non-whitelisted apps — the job sits in
 * ENQUEUED forever even with network + storage constraints satisfied. This
 * was reproduced on a OnePlus CPH2487 (Android 14). A user-initiated
 * foreground service is the robust, portable pattern: the start is allowed
 * because the app is foregrounded at tap time, and the ongoing notification
 * makes OEM ROMs keep it alive (same mechanism a music player relies on).
 *
 * This class keeps the exact public API the previous WorkManager-backed
 * implementation exposed, so callers (OnboardingViewModel, ManageDownloads,
 * SettingsScreen) require no changes:
 *  • [startDownload] / [restartDownload] / [cancelDownload]
 *  • [allProgress] StateFlow
 *  • path helpers, [isDownloaded], [isFileComplete], [restoreCompletedStates],
 *    [reattachActiveDownloads], [activeDownloadingPaths], [clearProgress]
 *
 * [ModelDownloadService] reports progress back through the internal
 * [emitProgress] / [emitCompleted] / [emitFailed] callbacks below, which feed
 * the same [allProgress] flow the UI already observes.
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hfTokenManager: HuggingFaceTokenManager,
    private val languageManager: com.saarthi.core.i18n.LanguageManager,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Per-model download progress, keyed by model ID. UI observes this. */
    private val _allProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val allProgress: StateFlow<Map<String, DownloadProgress>> = _allProgress.asStateFlow()

    /**
     * Models for which a download has been started in this process lifetime.
     * Used by [activeDownloadingPaths] to translate model IDs → tmp file paths
     * without re-deriving them on every call.
     */
    private val trackedModels = ConcurrentHashMap<String, ModelEntry>()

    @Volatile private var hfToken: String = ""

    init {
        scope.launch { hfTokenManager.effectiveToken.collect { hfToken = it } }
    }

    // ── Directory / path helpers ──────────────────────────────────────────────

    fun modelsDir(): File = File(context.filesDir, "models").also { it.mkdirs() }

    fun tmpModelsDir(): File =
        // getExternalFilesDir can return null when external storage is unavailable
        // (device encrypted at boot before unlock, SD card ejected, etc.).
        // Fall back to internal storage so the download can still proceed.
        (context.getExternalFilesDir(null) ?: context.filesDir)
            .let { File(it, "models_tmp") }
            .also { it.mkdirs() }

    fun localPathFor(model: ModelEntry): File = File(modelsDir(), model.fileName)
    fun tmpPathFor(model: ModelEntry): File   = File(tmpModelsDir(), model.fileName)

    /**
     * Returns the actual file on disk for a model — checks canonical name first,
     * then DownloadManager-suffixed variants (-1, -2 …) that the legacy Android
     * DownloadManager created. If a suffixed file is found it is renamed to the
     * canonical path so future calls always resolve correctly.
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

    // ── Download API ──────────────────────────────────────────────────────────

    /**
     * Starts or resumes a download. If the file is already complete on disk,
     * immediately emits [DownloadProgress.Completed]. Otherwise it hands the
     * transfer to [ModelDownloadService]; a partial tmp file (from an
     * interrupted attempt) is resumed via OkHttp Range — no bytes re-fetched.
     *
     * MUST be called from a foreground context (it always is — every caller is
     * a user tap on the onboarding / downloads screen) so the foreground-service
     * start is permitted on Android 12+.
     */
    fun startDownload(model: ModelEntry) = launchDownload(model, replace = false)

    private fun launchDownload(model: ModelEntry, replace: Boolean) {
        val finalFile = resolveLocalFile(model)
        if (!replace && isFileComplete(finalFile, model.fileSizeBytes)) {
            _allProgress.update { it + (model.id to DownloadProgress.Completed(finalFile.absolutePath)) }
            return
        }

        trackedModels[model.id] = model

        // Immediate UI feedback: flip the model into a Downloading state right
        // away so the button reflects "starting" before the service emits its
        // first byte-level progress. Don't clobber an existing live progress.
        _allProgress.update { current ->
            if (current[model.id] is DownloadProgress.Downloading) current
            else current + (model.id to DownloadProgress.Downloading(0L, model.fileSizeBytes))
        }

        DebugLogger.log("DOWNLOAD",
            "Starting foreground-service download  model=${model.id}  replace=$replace  ${networkSnapshot()}")

        ModelDownloadService.start(
            context = context,
            modelId = model.id,
            url = model.downloadUrl,
            tmpPath = tmpPathFor(model).absolutePath,
            destPath = finalFile.absolutePath,
            title = "${languageManager.selectedLanguage.value.downloadingTitlePrefix} ${model.displayName}",
            token = hfToken,
            replace = replace,
        )
    }

    /** Cancels the active download AND removes any partial tmp / final file. */
    fun cancelDownload(model: ModelEntry) {
        ModelDownloadService.cancel(context, model.id)
        trackedModels.remove(model.id)
        tmpPathFor(model).delete()
        localPathFor(model).delete()
        _allProgress.update { it - model.id }
    }

    /**
     * Force-restarts a download from zero. Deletes the partial tmp file (no
     * resume) and re-launches with replace=true, which atomically cancels any
     * in-flight transfer inside the service and starts fresh — no separate
     * cancel intent, so no stop/restart race on the service.
     */
    fun restartDownload(model: ModelEntry) {
        trackedModels.remove(model.id)
        tmpPathFor(model).delete()
        _allProgress.update { it - model.id }
        launchDownload(model, replace = true)
    }

    /** Clears completed/failed state from the progress map (after user dismissed). */
    fun clearProgress(modelId: String) {
        _allProgress.update { it - modelId }
    }

    /**
     * Restores [DownloadProgress.Completed] state for every model whose file is
     * already on disk. Call this on ViewModel init to populate "downloaded" badges.
     *
     * Does NOT trigger auto-select logic — that is handled by the [allProgress]
     * collector in OnboardingViewModel which only acts on NEWLY emitted Completed.
     */
    fun restoreCompletedStates(models: List<ModelEntry>) {
        models.forEach { model ->
            if (_allProgress.value[model.id] != null) return@forEach
            val file = resolveLocalFile(model)
            if (isFileComplete(file, model.fileSizeBytes)) {
                _allProgress.update { it + (model.id to DownloadProgress.Completed(file.absolutePath)) }
            }
        }
    }

    /**
     * Resumes any download that was interrupted by a process kill / reboot.
     *
     * A lingering partial tmp file means a transfer was cut off mid-flight
     * (cancel + delete both wipe the tmp, so a leftover partial is never a
     * cancelled download). Re-starting hands it back to the service, which
     * Range-resumes from exactly where it stopped. This is the kill/reboot
     * resilience the old WorkManager path provided, now done explicitly.
     */
    fun reattachActiveDownloads(models: List<ModelEntry>) {
        models.forEach { model ->
            val destFile = resolveLocalFile(model)
            if (isFileComplete(destFile, model.fileSizeBytes)) return@forEach

            val tmp = tmpPathFor(model)
            if (tmp.exists() && tmp.length() > 1_000_000L) {
                DebugLogger.log("DOWNLOAD",
                    "Resuming interrupted download ${model.id}  partial=${tmp.length() / 1_048_576}MB")
                startDownload(model)
            }
        }
    }

    /**
     * Returns absolute paths of the tmp files currently being written to.
     * Used by OnboardingViewModel to exclude in-progress downloads from
     * the "ready to load" model list.
     */
    fun activeDownloadingPaths(): Set<String> =
        _allProgress.value
            .filter { (_, p) -> p is DownloadProgress.Downloading }
            .keys
            .mapNotNull { id -> trackedModels[id]?.let { tmpPathFor(it).absolutePath } }
            .toSet()

    // ── Service callbacks (called from ModelDownloadService) ────────────────────

    internal fun emitProgress(modelId: String, downloaded: Long, total: Long) {
        _allProgress.update { it + (modelId to DownloadProgress.Downloading(downloaded, total)) }
    }

    internal fun emitCompleted(modelId: String, path: String) {
        trackedModels.remove(modelId)
        _allProgress.update { it + (modelId to DownloadProgress.Completed(path)) }
    }

    internal fun emitFailed(modelId: String, reason: String) {
        trackedModels.remove(modelId)
        _allProgress.update { it + (modelId to DownloadProgress.Failed(reason)) }
    }

    // ── File validation ───────────────────────────────────────────────────────

    /**
     * Checks if a model file on disk is complete enough to use.
     *
     * [trustOS] = true when the transfer reported success — we use a generous
     * 85% threshold to tolerate HuggingFace file-size drift across revisions.
     * [trustOS] = false for manual scans (95% threshold) to prevent loading
     * truncated / corrupted files.
     */
    fun isFileComplete(file: File, expectedBytes: Long = 0L, trustOS: Boolean = false): Boolean {
        if (!file.exists()) return false
        val size = file.length()
        if (size < 1_000_000L) return false

        val threshold = if (trustOS) 0.85 else 0.95
        if (expectedBytes > 0L && size < (expectedBytes * threshold).toLong()) {
            Timber.w("${file.name}: ${size / 1_048_576}MB of ${expectedBytes / 1_048_576}MB expected — incomplete")
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

        // For .litertlm bundles: size check is sufficient — no magic-byte spec
        return true
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Lightweight network snapshot for the start-of-download log line. Helps
     * distinguish "no network" from "OEM blocked the job" in field logs —
     * though with the foreground-service model the OEM block no longer applies.
     */
    private fun networkSnapshot(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val freeMb = runCatching { context.filesDir.freeSpace / 1_048_576L }.getOrDefault(-1L)
        return "net=$transport validated=$validated freeStorage=${freeMb}MB"
    }
}
