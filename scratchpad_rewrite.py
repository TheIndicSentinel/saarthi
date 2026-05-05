import re

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/ModelDownloadManager.kt", "r") as f:
    content = f.read()

# 1. Update directories
dir_str = """    fun modelsDir(): File =
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
    }"""

content = re.sub(
    r'    fun modelsDir\(\): File =[\s\S]*?fun localPathFor\(lora: LoraEntry\): File   = File\(adaptersDir\(\), lora\.fileName\)',
    dir_str,
    content
)

# 2. Update startDownload
start_dl_target = """    fun startDownload(model: ModelEntry) {
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
            if (isFileComplete(destFile, model.fileSizeBytes, trustOS = true)) {
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
    }"""

start_dl_replace = """    fun startDownload(model: ModelEntry) {
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
    }"""

content = content.replace(start_dl_target, start_dl_replace)

# 3. Update registerCompletionReceiver
receiver_target = """    private fun registerCompletionReceiver(
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

                    val osSuccess = queryStatus(downloadId) == DownloadManager.STATUS_SUCCESSFUL
                    val isActuallyComplete = isFileComplete(destFile, expectedBytes, trustOS = osSuccess)

                    val progress = if (osSuccess && isActuallyComplete) {
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
        }"""

receiver_replace = """    private fun registerCompletionReceiver(
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
        }"""

content = content.replace(receiver_target, receiver_replace)

# 4. In reattachActiveDownloads, update destFile to tmpFile, but we don't startDownload.
# Actually, reattachActiveDownloads doesn't need tmpFile because it just calls startDownload(model).
# Wait, reattachActiveDownloads checks: if (isFileComplete(destFile, model.fileSizeBytes)) return@forEach
# This uses finalFile, which is correct. We don't want to reattach if finalFile is complete.

with open("core/core-inference/src/main/kotlin/com/saarthi/core/inference/ModelDownloadManager.kt", "w") as f:
    f.write(content)
