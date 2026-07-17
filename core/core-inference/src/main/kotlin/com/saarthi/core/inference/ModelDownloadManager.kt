package com.saarthi.core.inference

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.storage.StorageManager
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
    private val failureStore: DownloadFailureStore,
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

    // Deliberately the SAME filesystem as modelsDir() (both under
    // context.filesDir), not getExternalFilesDir(). Internal and app-specific
    // external storage are different mount points on essentially every real
    // device without a removable SD card — File.renameTo() cannot cross
    // mount points, so the old external-tmp layout made the copyTo+delete
    // fallback in ModelDownloadService the NORMAL completion path, not a rare
    // edge case, doubling peak storage use to ~2x the model size during every
    // finalization (field research, 2026-07-17). Keeping tmp and final on one
    // volume makes the rename atomic and cheap, and means the storage
    // pre-flight check (which only ever validated modelsDir()) is now
    // actually checking the volume that matters for the whole transfer.
    fun tmpModelsDir(): File =
        File(context.filesDir, "models_tmp").also { it.mkdirs() }

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

        // Authoritative storage check right before starting the transfer.
        // ModelEntry.isSafeFor() only ran once, at picker-load time, against a
        // StatFs snapshot — it can be stale by the time the user actually taps
        // Download. remainingBytes accounts for a resumable partial tmp file
        // rather than demanding the full file size again.
        val remainingBytes = model.fileSizeBytes - (if (!replace) tmpPathFor(model).length() else 0L)
        if (remainingBytes > 0 && !ensureStorageAvailable(remainingBytes)) {
            val neededMb = remainingBytes / 1_048_576
            val availableMb = context.filesDir.freeSpace / 1_048_576
            DebugLogger.log("DOWNLOAD",
                "Insufficient storage for ${model.id}: needs ~${neededMb}MB, only ${availableMb}MB available")
            _allProgress.update {
                it + (model.id to DownloadProgress.Failed(
                    "Not enough storage: needs ~${neededMb}MB, only ${availableMb}MB available"))
            }
            return
        }

        trackedModels[model.id] = model

        // Immediate UI feedback: flip the model into a Downloading state right
        // away so the button reflects "starting" before the service emits its
        // first byte-level progress. Don't clobber an existing live progress.
        // Seed from the tmp file's current size (not 0) — a resumed download
        // already has bytes on disk, and showing 0% here made a resume look
        // indistinguishable from a genuine restart-from-scratch.
        val existingBytes = if (!replace) tmpPathFor(model).length() else 0L
        _allProgress.update { current ->
            if (current[model.id] is DownloadProgress.Downloading) current
            else current + (model.id to DownloadProgress.Downloading(existingBytes, model.fileSizeBytes))
        }

        DebugLogger.log("DOWNLOAD",
            "Starting foreground-service download  model=${model.id}  replace=$replace  ${networkSnapshot()}")

        val serviceStarted = ModelDownloadService.start(
            context = context,
            modelId = model.id,
            url = model.downloadUrl,
            tmpPath = tmpPathFor(model).absolutePath,
            destPath = finalFile.absolutePath,
            title = "${languageManager.selectedLanguage.value.downloadingTitlePrefix} ${model.displayName}",
            token = hfToken,
            replace = replace,
            expectedSha256 = model.expectedSha256,
        )
        if (!serviceStarted) {
            // startForegroundService() itself threw — the Intent never
            // reached the service, so onStartCommand()'s own fgsStarted
            // check never runs. Without this, the "Downloading" state set
            // just above would sit unchanged forever with no real transfer
            // behind it.
            trackedModels.remove(model.id)
            _allProgress.update {
                it + (model.id to DownloadProgress.Failed("Could not start the download service. Please try again."))
            }
        }
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
        migrateLegacyExternalTmp(models)
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
        sweepOrphanedTmpFiles(models)
    }

    /**
     * One-time migration: models_tmp used to live under getExternalFilesDir()
     * before 2026-07-17 — see [tmpModelsDir]'s kdoc for why that changed
     * (cross-mount-point renames made the non-atomic copyTo+delete fallback
     * the routine case, not a rare edge case). Any partial download an older
     * build left in the old location would otherwise become a permanent
     * orphan: nothing else ever looks there again, so it would just sit
     * unreachable on disk with no way to resume or reclaim it. Moves any
     * matching file into the new internal [tmpModelsDir] so the normal
     * resume pass above picks it up exactly as if it had always lived there —
     * UNLESS the model already completed via some other path since then, in
     * which case the legacy partial is discarded directly rather than
     * migrated, so a stale leftover for a finished model never turns into an
     * unreachable multi-GB copy that nothing would ever clean up again.
     */
    private fun migrateLegacyExternalTmp(models: List<ModelEntry>) {
        val legacyDir = context.getExternalFilesDir(null)?.let { File(it, "models_tmp") } ?: return
        if (!legacyDir.exists()) return
        val modelsByFileName = models.associateBy { it.fileName }

        legacyDir.listFiles()?.forEach { legacyFile ->
            // Each file's migration is isolated in its own runCatching — one
            // failure (e.g. a copy interrupted by low storage) must not
            // abort the rest of the batch, and must not fail silently either.
            runCatching {
                val model = modelsByFileName[legacyFile.name]
                when {
                    model == null -> {
                        // Not a current catalog model — same orphan reasoning
                        // as sweepOrphanedTmpFiles, just for the legacy location.
                        legacyFile.delete()
                    }
                    isFileComplete(resolveLocalFile(model), model.fileSizeBytes) -> {
                        // The model already completed via some other path — a
                        // fresh download after this dir moved, a restart, etc.
                        // Migrating this partial would copy a multi-GB file
                        // into internal storage that NOTHING would then ever
                        // clean up: reattachActiveDownloads() skips it because
                        // destFile is already complete, and sweepOrphanedTmpFiles()
                        // won't touch it because its filename IS a valid
                        // catalog entry. Discard directly instead — no copy.
                        DebugLogger.log("DOWNLOAD",
                            "Discarding legacy tmp for already-completed model ${model.id}: " +
                            "${legacyFile.name} (${legacyFile.length() / 1_048_576}MB)")
                        if (!legacyFile.delete()) {
                            // Not compounding — same file is re-checked (and
                            // re-attempted) on the next launch rather than
                            // being migrated, so a stuck delete just retries
                            // instead of leaking a multi-GB copy each time.
                            Timber.w("Failed to delete legacy tmp for completed model: ${legacyFile.absolutePath}")
                        }
                    }
                    else -> {
                        val newLocation = File(tmpModelsDir(), legacyFile.name)
                        when {
                            !newLocation.exists() -> {
                                DebugLogger.log("DOWNLOAD",
                                    "Migrating legacy external tmp file to internal storage: " +
                                    "${legacyFile.name} (${legacyFile.length() / 1_048_576}MB)")
                                if (!legacyFile.renameTo(newLocation)) {
                                    legacyFile.copyTo(newLocation, overwrite = true)
                                    legacyFile.delete()
                                }
                            }
                            legacyFile.length() > newLocation.length() -> {
                                // Both exist — keep whichever has more bytes,
                                // rather than unconditionally discarding the
                                // legacy copy regardless of which one actually
                                // has more resume progress.
                                DebugLogger.log("DOWNLOAD",
                                    "Legacy tmp (${legacyFile.length() / 1_048_576}MB) is more complete than " +
                                    "internal (${newLocation.length() / 1_048_576}MB) for ${legacyFile.name} — replacing")
                                newLocation.delete()
                                if (!legacyFile.renameTo(newLocation)) {
                                    legacyFile.copyTo(newLocation, overwrite = true)
                                    legacyFile.delete()
                                }
                            }
                            else -> {
                                // Internal copy is already as complete or more —
                                // legacy copy is redundant, reclaim the space.
                                legacyFile.delete()
                            }
                        }
                    }
                }
            }.onFailure {
                DebugLogger.log("DOWNLOAD",
                    "WARN legacy tmp migration failed for ${legacyFile.name}: ${it.message}")
            }
        }
        runCatching { legacyDir.delete() }
    }

    /**
     * Deletes tmp files belonging to a model no longer in the catalog (e.g. a
     * future app update that drops an entry). A no-op today — the catalog has
     * never shrunk yet — but nothing else scans [tmpModelsDir] independent of
     * the current catalog, so an orphaned partial file would otherwise sit
     * unreachable on disk forever. Runs after the resume pass above, so a
     * legitimate in-progress download is never mistaken for an orphan.
     */
    private fun sweepOrphanedTmpFiles(models: List<ModelEntry>) {
        val validNames = models.map { it.fileName }.toSet()
        runCatching {
            tmpModelsDir().listFiles()?.forEach { file ->
                if (file.name !in validNames) {
                    DebugLogger.log("DOWNLOAD",
                        "Sweeping orphaned tmp file: ${file.name} (${file.length() / 1_048_576}MB)")
                    file.delete()
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

    // ── Service callbacks (called from ModelDownloadService) ────────────────────

    internal fun emitProgress(modelId: String, downloaded: Long, total: Long) {
        _allProgress.update { it + (modelId to DownloadProgress.Downloading(downloaded, total)) }
    }

    internal fun emitCompleted(modelId: String, path: String) {
        trackedModels.remove(modelId)
        _allProgress.update { it + (modelId to DownloadProgress.Completed(path)) }
        scope.launch { failureStore.clear() }
    }

    internal fun emitFailed(modelId: String, reason: String) {
        trackedModels.remove(modelId)
        _allProgress.update { it + (modelId to DownloadProgress.Failed(reason)) }
        // Persisted so the failure survives a process restart — the
        // in-memory _allProgress entry above is lost if the app is
        // backgrounded and killed before the user reopens it.
        scope.launch { failureStore.recordFailure(modelId, reason) }
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
     * Authoritative right-before-download storage check via [StorageManager]
     * (API 26+, unconditional at this app's minSdk 28) — the platform's
     * recommended mechanism for reserving space ahead of a large write. If
     * the OS doesn't already report enough allocatable space, [allocateBytes]
     * is attempted first (this can trigger eviction of other apps'
     * reclaimable cache, the documented way to free room for exactly this
     * scenario) before concluding there's genuinely not enough.
     *
     * Fails OPEN (returns true) if the check itself errors — a diagnostic API
     * hiccup shouldn't block a download that might otherwise succeed; a
     * genuine out-of-space condition is still caught by the write loop's own
     * ENOSPC handling in [ModelDownloadService].
     */
    private fun ensureStorageAvailable(bytesNeeded: Long): Boolean = runCatching {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val uuid = storageManager.getUuidForPath(modelsDir())
        if (storageManager.getAllocatableBytes(uuid) >= bytesNeeded) return@runCatching true
        runCatching { storageManager.allocateBytes(uuid, bytesNeeded) }
        storageManager.getAllocatableBytes(uuid) >= bytesNeeded
    }.getOrDefault(true)

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
