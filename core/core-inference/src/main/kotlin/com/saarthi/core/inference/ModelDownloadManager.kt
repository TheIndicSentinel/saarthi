package com.saarthi.core.inference

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import com.saarthi.core.inference.model.DownloadProgress
import com.saarthi.core.inference.model.ModelEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * Manages model downloads using AndroidX WorkManager + OkHttp.
 *
 * Replaces the previous DownloadManager implementation which stalled on
 * Samsung OneUI: the Doze scheduler paused PAUSED_WAITING_TO_RETRY
 * downloads when the screen locked, silently blocking multi-GB downloads.
 *
 * WorkManager advantages:
 *  • Persists across reboots — the download resumes automatically after a
 *    reboot or if the OS swipes the app away.
 *  • OkHttp Range headers — byte-exact resumption from the last offset if
 *    the connection drops mid-stream; no wasted bytes re-downloaded.
 *  • Foreground Worker — [ModelDownloadWorker] calls setForeground() on
 *    start, keeping the process alive via a persistent notification even
 *    when the screen is locked (uses specialUse type, not dataSync —
 *    dataSync was removed in Android 16 / API 36).
 *  • Constraints(requiresNetwork = true) — WorkManager re-queues work
 *    automatically when the network comes back after a drop.
 *
 * The public API is identical to the previous DownloadManager implementation
 * so callers (OnboardingViewModel, SettingsScreen) require no changes.
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hfTokenManager: HuggingFaceTokenManager,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val workManager = WorkManager.getInstance(context)

    /** Per-model download progress, keyed by model ID. UI observes this. */
    private val _allProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val allProgress: StateFlow<Map<String, DownloadProgress>> = _allProgress.asStateFlow()

    /**
     * WorkInfo observer jobs, keyed by model ID. Cancelled when work finishes
     * or when [cancelDownload] is called. Kept separately from WorkManager
     * so they don't outlive their usefulness.
     */
    private val activeObservers = ConcurrentHashMap<String, Job>()

    /**
     * Models for which a download has been started in this process lifetime.
     * Used by [activeDownloadingPaths] to translate model IDs → tmp file paths
     * without querying WorkManager on every call.
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
     * then DownloadManager-suffixed variants (-1, -2 …) that Android DownloadManager
     * created during the previous implementation. If a suffixed file is found it is
     * renamed to the canonical path so future calls always resolve correctly.
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
     * Starts or reattaches to a download. If the file is already complete on disk,
     * immediately emits [DownloadProgress.Completed] without enqueuing any work.
     * If a WorkManager job is already ENQUEUED/RUNNING for this model (from a
     * previous session), [ExistingWorkPolicy.KEEP] preserves it and we just
     * attach an observer.
     */
    fun startDownload(model: ModelEntry) {
        enqueueWork(model, ExistingWorkPolicy.KEEP)
    }

    /** Cancels the active download AND removes any partial tmp file. */
    fun cancelDownload(model: ModelEntry) {
        workManager.cancelUniqueWork(model.id)
        activeObservers.remove(model.id)?.cancel()
        trackedModels.remove(model.id)
        tmpPathFor(model).delete()
        localPathFor(model).delete()
        _allProgress.update { it - model.id }
    }

    /**
     * Force-restarts a download from zero. Deletes the partial tmp file (no resume)
     * and re-enqueues with REPLACE, which atomically cancels any in-flight job and
     * starts a fresh one — no manual cancel + delay race condition.
     */
    fun restartDownload(model: ModelEntry) {
        activeObservers.remove(model.id)?.cancel()
        trackedModels.remove(model.id)
        tmpPathFor(model).delete()
        _allProgress.update { it - model.id }
        enqueueWork(model, ExistingWorkPolicy.REPLACE)
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
     * Reattaches progress observation to any download that WorkManager is still
     * running (e.g. after the app process was killed and restarted). Does nothing
     * for models whose files are already complete on disk.
     */
    fun reattachActiveDownloads(models: List<ModelEntry>) {
        models.forEach { model ->
            if (activeObservers[model.id]?.isActive == true) return@forEach
            val destFile = resolveLocalFile(model)
            if (isFileComplete(destFile, model.fileSizeBytes)) return@forEach

            // Check WorkManager synchronously (called from IO scope).
            scope.launch {
                val infos = runCatching {
                    workManager.getWorkInfosForUniqueWork(model.id).get()
                }.getOrDefault(emptyList())

                val hasActiveWork = infos.any {
                    it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.RUNNING  ||
                    it.state == WorkInfo.State.BLOCKED
                }
                if (hasActiveWork) {
                    DebugLogger.log("DOWNLOAD", "Reattaching WorkManager observer: ${model.id}")
                    trackedModels[model.id] = model
                    observeWork(model)
                }
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

    // ── File validation ───────────────────────────────────────────────────────

    /**
     * Checks if a model file on disk is complete enough to use.
     *
     * [trustOS] = true when WorkManager reported SUCCESS (the OS completed the
     * HTTP transfer) — we use a generous 85% threshold to tolerate HuggingFace
     * file-size drift across revisions. [trustOS] = false for manual scans
     * (99% threshold) to prevent loading truncated / corrupted files.
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

    private fun enqueueWork(model: ModelEntry, policy: ExistingWorkPolicy) {
        val finalFile = resolveLocalFile(model)
        if (isFileComplete(finalFile, model.fileSizeBytes)) {
            _allProgress.update { it + (model.id to DownloadProgress.Completed(finalFile.absolutePath)) }
            return
        }

        trackedModels[model.id] = model

        val token   = hfToken
        val tmpFile = tmpPathFor(model)

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_URL        to model.downloadUrl,
                ModelDownloadWorker.KEY_TMP_PATH   to tmpFile.absolutePath,
                ModelDownloadWorker.KEY_DEST_PATH  to finalFile.absolutePath,
                ModelDownloadWorker.KEY_TITLE      to "Downloading ${model.displayName}",
                ModelDownloadWorker.KEY_HF_TOKEN   to token,
            ))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            // LINEAR back-off starting at 10s: a dropped connection during a 2.5GB
            // download resumes in 10s, 20s, 30s … instead of the default exponential
            // 30s, 60s, 120s … which wastes several minutes on a brief Wi-Fi hiccup.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            // setExpedited: runs with higher priority on Android 12+ (expedited job)
            // and falls back gracefully when system quota is exhausted.
            // getForegroundInfo() in ModelDownloadWorker satisfies the pre-Android 12
            // requirement that expedited work declares a foreground notification.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(model.id)
            .build()

        workManager.enqueueUniqueWork(model.id, policy, request)
        observeWork(model)
        DebugLogger.log("DOWNLOAD", "WorkManager job enqueued  model=${model.id}  policy=$policy  tmp=${tmpFile.name}")
        // Network + storage snapshot at enqueue time — explains "stuck"
        // downloads. The worker's constraints are NetworkType.CONNECTED +
        // RequiresStorageNotLow; if either fails the worker silently stays
        // ENQUEUED with no further logs from the worker itself.
        DebugLogger.log("DOWNLOAD-DIAG", "enqueue snapshot — ${networkAndStorageSnapshot()}")
        // Stuck-check: if the worker is still ENQUEUED after 8s, neither
        // constraint flipped to satisfied — log a clear diagnostic so the
        // log shows WHY no progress, instead of going silent.
        scope.launch {
            delay(8_000)
            val infos = runCatching { workManager.getWorkInfosForUniqueWork(model.id).get() }.getOrNull()
            val info = infos?.firstOrNull() ?: return@launch
            if (info.state == WorkInfo.State.ENQUEUED) {
                DebugLogger.log("DOWNLOAD-DIAG",
                    "STILL ENQUEUED after 8s — worker hasn't run. " +
                    "Constraints required: NetworkType.CONNECTED + RequiresStorageNotLow. " +
                    "Now: ${networkAndStorageSnapshot()}. " +
                    "If both look OK on this device, the OEM (Realme/OPPO/Vivo/Xiaomi) " +
                    "is likely blocking background work — check App auto-launch / " +
                    "Background activity permission for Saarthi.")
            }
        }
    }

    /**
     * Diagnostic snapshot used to explain why a queued download isn't running.
     * Reports active network type, whether it's validated for internet, whether
     * it's metered, and free internal storage.
     */
    private fun networkAndStorageSnapshot(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val notMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val freeMb = runCatching { context.filesDir.freeSpace / 1_048_576L }.getOrDefault(-1L)
        return "net=$transport internet=$hasInternet validated=$validated notMetered=$notMetered freeStorage=${freeMb}MB"
    }

    /**
     * Subscribes to [WorkManager.getWorkInfosForUniqueWorkFlow] for [model].
     * Translates [WorkInfo.State] + progress data into [DownloadProgress] and
     * emits into [_allProgress]. The coroutine self-cancels when work finishes.
     */
    private fun observeWork(model: ModelEntry) {
        activeObservers[model.id]?.cancel()
        activeObservers[model.id] = scope.launch {
            var lastState: WorkInfo.State? = null
            workManager.getWorkInfosForUniqueWorkFlow(model.id)
                .collect { infos ->
                    val info = infos.firstOrNull() ?: return@collect
                    // Log every state transition — ENQUEUED → RUNNING is the
                    // signal that the worker actually started. If it goes from
                    // ENQUEUED → (nothing) the worker is held by constraints.
                    if (info.state != lastState) {
                        DebugLogger.log("WORK-STATE", "${model.id}  ${lastState ?: "-"} -> ${info.state}")
                        lastState = info.state
                    }
                    val progress = mapToProgress(info, model)
                    if (progress != null) {
                        _allProgress.update { it + (model.id to progress) }
                    }
                    if (info.state.isFinished) {
                        activeObservers.remove(model.id)
                        if (info.state != WorkInfo.State.SUCCEEDED) {
                            trackedModels.remove(model.id)
                        }
                    }
                }
        }
    }

    private fun mapToProgress(info: WorkInfo, model: ModelEntry): DownloadProgress? =
        when (info.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING -> {
                val downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED, 0L)
                val total      = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, 0L)
                DownloadProgress.Downloading(downloaded, total)
            }
            WorkInfo.State.SUCCEEDED -> {
                val path = info.outputData.getString(ModelDownloadWorker.KEY_FINAL_PATH)
                    ?: localPathFor(model).absolutePath
                DownloadProgress.Completed(path)
            }
            WorkInfo.State.FAILED -> {
                val reason = info.outputData.getString(ModelDownloadWorker.KEY_ERROR_MSG)
                    ?: "Download failed"
                DownloadProgress.Failed(reason)
            }
            WorkInfo.State.CANCELLED -> null
            else                     -> null
        }
}
