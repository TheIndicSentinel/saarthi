package com.saarthi.feature.assistant.data

import android.content.Context
import com.saarthi.core.i18n.VoicePackPreference
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.DeviceProfiler
import com.saarthi.core.inference.model.DeviceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the full lifecycle of downloadable Piper voice packs:
 *
 *  1. Download a .tar.bz2 tarball from sherpa-onnx/releases.
 *  2. Extract it to files/voices/<extractedDir>/.
 *  3. Load [NeuralTtsEngine] from the extracted model.
 *  4. Hand the loaded engine to [TtsManager] via setNeuralEngine().
 *
 * Keeps a [DownloadState] flow per pack ID for UI progress reporting.
 * Gated to MID+ devices — on LOW/MINIMAL, download is rejected because
 * the RAM cost of running neural TTS alongside the LLM causes OOM.
 *
 * Uses OkHttp (already a transitive dep via core-inference).
 */
@Singleton
class VoicePackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsManager: TtsManager,
    private val pref: VoicePackPreference,
    private val deviceProfiler: DeviceProfiler,
) {
    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progressPct: Int) : DownloadState()
        object Extracting : DownloadState()
        object Ready : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val voicesDir: File = File(context.filesDir, "voices")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = HashMap<String, MutableStateFlow<DownloadState>>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** True when this device can run neural TTS (MID+ tier). */
    val isNeuralSupported: Boolean by lazy {
        runCatching {
            deviceProfiler.profile().tier.ordinal >= DeviceTier.MID.ordinal
        }.getOrDefault(false)
    }

    fun stateFor(packId: String): StateFlow<DownloadState> =
        _states.getOrPut(packId) { MutableStateFlow(DownloadState.Idle) }.asStateFlow()

    /** Start a download+extract+load cycle for [packId]. No-op if already downloading. */
    fun download(packId: String) {
        if (!isNeuralSupported) {
            DebugLogger.log("TTS", "VoicePackManager: neural TTS not supported on this device tier")
            return
        }
        val pack = VoiceCatalog.findById(packId) ?: return
        val stateFlow = _states.getOrPut(packId) { MutableStateFlow(DownloadState.Idle) }
        if (stateFlow.value is DownloadState.Downloading) return

        scope.launch {
            runCatching {
                downloadAndInstall(pack, stateFlow)
            }.onFailure { e ->
                DebugLogger.log("TTS", "VoicePackManager: download failed for $packId: ${e.message}")
                stateFlow.value = DownloadState.Error(e.message ?: "Download failed")
            }
        }
    }

    /** Remove a downloaded voice pack and unload it from TtsManager. */
    fun remove(packId: String) {
        scope.launch {
            val pack = VoiceCatalog.findById(packId) ?: return@launch
            File(voicesDir, pack.extractedDir).deleteRecursively()
            pref.markRemoved(packId)
            registerActivePack()
            _states[packId]?.value = DownloadState.Idle
            DebugLogger.log("TTS", "VoicePackManager: removed $packId")
        }
    }

    /**
     * Call on app start to make a previously-downloaded voice available. This
     * only REGISTERS the pack (cheap) — it does NOT load the heavy native engine
     * here. Loading happens lazily on first speak (see TtsManager), so it never
     * collides with the litertlm model load at startup (which previously caused
     * a SIGKILL crash-loop).
     */
    fun restoreOnStartup() {
        if (!isNeuralSupported) return
        scope.launch {
            val installed = pref.installedPackIds.first()
            if (installed.isEmpty()) return@launch
            registerActivePack()
        }
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private suspend fun downloadAndInstall(
        pack: VoiceCatalog.VoicePack,
        stateFlow: MutableStateFlow<DownloadState>,
    ) = withContext(Dispatchers.IO) {
        voicesDir.mkdirs()
        val tarFile = File(voicesDir, pack.tarFilename)

        // ── 1. Download ──────────────────────────────────────────────────────
        stateFlow.value = DownloadState.Downloading(0)
        DebugLogger.log("TTS", "VoicePackManager: downloading ${pack.tarUrl}")

        val request = Request.Builder().url(pack.tarUrl).build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${response.message}")
        }
        val totalBytes = response.body?.contentLength() ?: -1L
        var written = 0L

        response.body?.byteStream()?.use { input ->
            tarFile.outputStream().use { output ->
                val buf = ByteArray(32 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    output.write(buf, 0, n)
                    written += n
                    if (totalBytes > 0) {
                        stateFlow.value = DownloadState.Downloading(
                            (written * 100 / totalBytes).toInt().coerceIn(0, 99)
                        )
                    }
                }
            }
        }
        DebugLogger.log("TTS", "VoicePackManager: downloaded ${written / 1_048_576} MB → ${tarFile.name}")

        // ── 2. Extract ───────────────────────────────────────────────────────
        stateFlow.value = DownloadState.Extracting
        extractTarBz2(tarFile, voicesDir)
        tarFile.delete()
        DebugLogger.log("TTS", "VoicePackManager: extracted ${pack.extractedDir}")

        // ── 3. Persist + register (NOT load) ─────────────────────────────────
        // Register only — do NOT init the native engine here. Right after
        // onboarding the litertlm model is still loading on the GPU; loading
        // the sherpa-onnx native runtime concurrently SIGKILLs the process.
        // The engine loads lazily on the first speak instead.
        pref.markInstalled(pack.id)
        stateFlow.value = DownloadState.Ready
        registerActivePack()
    }

    private fun extractTarBz2(tarFile: File, destDir: File) {
        tarFile.inputStream().buffered().use { raw ->
            BZip2CompressorInputStream(raw).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out -> tar.copyTo(out) }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
    }

    /**
     * Register the installed pack (matching the gender preference) with
     * [TtsManager] as a LAZY loader. The heavy native init is deferred to the
     * first speak in a supported language — never run here, so it can't collide
     * with the litertlm model load.
     */
    private suspend fun registerActivePack() = withContext(Dispatchers.IO) {
        val installed = pref.installedPackIds.first()
        val gender = pref.voiceGender.first()
        val pack = VoiceCatalog.entries.firstOrNull { it.id in installed && it.gender == gender }
            ?: VoiceCatalog.entries.firstOrNull { it.id in installed }
        if (pack == null) {
            ttsManager.clearNeuralPack()
            return@withContext
        }
        val languages = VoiceCatalog.entries
            .filter { it.id in installed }
            .map { it.language }
            .toSet()
        val voices = voicesDir
        ttsManager.registerNeuralPack(
            loader = {
                // Runs lazily on TtsManager's IO scope on first speak.
                val engine = NeuralTtsEngine(voices, pack)
                if (engine.init()) {
                    DebugLogger.log("TTS", "VoicePackManager: neural engine loaded (lazy)  pack=${pack.id}")
                    engine
                } else {
                    null
                }
            },
            supportedLanguages = languages,
        )
        DebugLogger.log("TTS", "VoicePackManager: neural pack registered (lazy)  pack=${pack.id}  gender=$gender  langs=${languages.map { it.code }}")
    }

    /** Switch to the pack matching the new gender preference; re-registers lazily. */
    fun setGender(gender: String) {
        scope.launch {
            pref.setVoiceGender(gender)
            registerActivePack()
        }
    }

    private val DownloadState.isIdle get() = this is DownloadState.Idle
}
