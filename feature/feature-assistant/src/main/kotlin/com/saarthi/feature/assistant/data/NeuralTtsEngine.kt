package com.saarthi.feature.assistant.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.saarthi.core.i18n.VoiceHint
import com.saarthi.core.inference.DebugLogger
import java.io.File
import java.util.Locale

/**
 * Neural TTS backend using sherpa-onnx + Piper ONNX models.
 *
 * One instance is created per loaded voice pack directory. The engine is
 * initialized synchronously on a background thread (never on main). Audio
 * is played via [AudioTrack] in streaming mode using
 * [OfflineTts.generateWithCallback] — this fires chunks of PCM samples as
 * the model produces them, so first audio starts playing before synthesis
 * is complete.
 *
 * Lifecycle: created by [VoicePackManager] when a pack is installed and
 * the device is MID+. Released via [release] when the pack is uninstalled
 * or the app exits. [TtsManager.setNeuralEngine] holds the live reference.
 *
 * @param voicesDir  the files/voices/ root (from Context.filesDir)
 * @param pack       the pack whose model to load
 */
internal class NeuralTtsEngine(
    voicesDir: File,
    private val pack: VoiceCatalog.VoicePack,
) : TtsEngine {

    private val modelDir = File(voicesDir, pack.extractedDir)
    private val modelPath = File(modelDir, pack.modelFilename).absolutePath
    private val espeakDataDir = File(modelDir, "espeak-ng-data").absolutePath

    private var tts: OfflineTts? = null
    private var sampleRate: Int = 22050
    @Volatile private var stopped = false

    override val isAvailable: Boolean
        get() = tts != null

    /**
     * Initialise the sherpa-onnx engine. Must be called on a background
     * thread — OfflineTts loads native libraries synchronously.
     *
     * Returns false if the model directory is incomplete (download was
     * interrupted). The caller falls back to SystemTtsEngine.
     */
    fun init(): Boolean {
        if (!File(modelPath).exists()) {
            DebugLogger.log("TTS", "NeuralTts: model not found at $modelPath — skipping")
            return false
        }
        return runCatching {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model   = modelPath,
                        dataDir = espeakDataDir,
                    ),
                    numThreads = 2,
                    debug      = false,
                    provider   = "cpu",
                ),
            )
            val engine = OfflineTts(config = config)
            sampleRate = engine.sampleRate()
            tts = engine
            DebugLogger.log("TTS", "NeuralTts: loaded ${pack.id}  sampleRate=$sampleRate")
            true
        }.getOrElse { e ->
            DebugLogger.log("TTS", "NeuralTts: init failed for ${pack.id}: ${e.message}")
            false
        }
    }

    override fun speakChunks(
        chunks: List<String>,
        locale: Locale,
        hint: VoiceHint,
        utteranceIdPrefix: String,
        callbacks: TtsCallbacks,
    ) {
        val engine = tts ?: return
        stopped = false

        val track = buildAudioTrack(sampleRate) ?: run {
            DebugLogger.log("TTS", "NeuralTts: AudioTrack creation failed — falling through")
            return
        }
        track.play()

        chunks.forEachIndexed { i, chunk ->
            if (stopped) {
                track.stop()
                track.release()
                return
            }
            val chunkId = "${utteranceIdPrefix}_$i"
            callbacks.onStart(chunkId)

            val speed = hint.rate.coerceIn(0.5f, 2.0f)
            engine.generateWithCallback(
                text  = chunk,
                sid   = 0,
                speed = speed,
            ) { samples ->
                if (stopped) return@generateWithCallback 0
                val pcm = floatArrayToPcm16(samples)
                track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                1  // continue synthesis
            }
            callbacks.onDone(chunkId)
        }

        // Drain the audio track buffer before releasing so the last sentence
        // isn't cut off mid-word.
        if (!stopped) track.stop()
        track.release()
    }

    override fun stop() {
        stopped = true
        tts?.let { runCatching { /* OfflineTts has no cancel; stopped flag cuts the callback */ } }
    }

    override fun release() {
        stopped = true
        runCatching { tts?.release() }
        tts = null
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun buildAudioTrack(sampleRate: Int): AudioTrack? = runCatching {
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
    }.getOrNull()

    /** Convert normalised float [-1,1] samples to signed 16-bit PCM. */
    private fun floatArrayToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            out[i] = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
