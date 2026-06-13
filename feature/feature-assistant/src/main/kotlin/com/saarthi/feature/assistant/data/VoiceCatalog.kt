package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.model.DeviceTier

/**
 * Catalog of downloadable Piper ONNX voice packs.
 *
 * Source: sherpa-onnx/releases/tag/tts-models (Apache 2.0 runtime, MIT voices).
 * Each entry is a pre-packaged .tar.bz2 that includes BOTH the ONNX model
 * and the espeak-ng-data directory the engine needs for phonemization.
 * This means each pack is a single download, self-contained.
 *
 * Verified languages (MIT, commercially usable):
 *   Hindi   — pratham (male), priyamvada (female), rohan (male)
 *   Telugu  — no sherpa-onnx tarball yet; removed until available
 *
 * Languages NOT listed (no verified free tarball today):
 *   Tamil, Bengali, Marathi, Kannada, Gujarati, Punjabi, Odia → system TTS.
 *
 * [requiredTier] = MID (≥6 GB total RAM). LOW/MINIMAL devices cannot run
 * neural TTS alongside the resident LLM without OOM.
 */
object VoiceCatalog {

    private const val SHERPA_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    data class VoicePack(
        val id: String,
        val language: SupportedLanguage,
        val displayName: String,
        val gender: String,
        /** tarball filename — single download that expands to modelDir + espeak-ng-data. */
        val tarFilename: String,
        val tarUrl: String,
        /** ~size in MB shown to the user before download. */
        val approximateSizeMb: Int,
        /**
         * Directory name the tarball expands into under files/voices/.
         * After extraction: files/voices/<extractedDir>/<model>.onnx
         *                   files/voices/<extractedDir>/<model>.onnx.json
         *                   files/voices/<extractedDir>/espeak-ng-data/
         */
        val extractedDir: String,
        /** .onnx filename inside extractedDir. */
        val modelFilename: String,
        /** .onnx.json filename inside extractedDir. */
        val configFilename: String,
        val requiredTier: DeviceTier = DeviceTier.MID,
    )

    val entries: List<VoicePack> = listOf(
        // ── Hindi — male (pratham) ───────────────────────────────────────────
        VoicePack(
            id               = "hi_IN-pratham-medium",
            language         = SupportedLanguage.HINDI,
            displayName      = "Hindi · Pratham (male)",
            gender           = "male",
            tarFilename      = "vits-piper-hi_IN-pratham-medium.tar.bz2",
            tarUrl           = "$SHERPA_BASE/vits-piper-hi_IN-pratham-medium.tar.bz2",
            approximateSizeMb = 64,
            extractedDir     = "vits-piper-hi_IN-pratham-medium",
            modelFilename    = "hi_IN-pratham-medium.onnx",
            configFilename   = "hi_IN-pratham-medium.onnx.json",
        ),
        // ── Hindi — female (priyamvada) ──────────────────────────────────────
        VoicePack(
            id               = "hi_IN-priyamvada-medium",
            language         = SupportedLanguage.HINDI,
            displayName      = "Hindi · Priyamvada (female)",
            gender           = "female",
            tarFilename      = "vits-piper-hi_IN-priyamvada-medium.tar.bz2",
            tarUrl           = "$SHERPA_BASE/vits-piper-hi_IN-priyamvada-medium.tar.bz2",
            approximateSizeMb = 64,
            extractedDir     = "vits-piper-hi_IN-priyamvada-medium",
            modelFilename    = "hi_IN-priyamvada-medium.onnx",
            configFilename   = "hi_IN-priyamvada-medium.onnx.json",
        ),
        // Telugu and other languages: no verified sherpa-onnx tarball exists yet.
        // Add here when available; they will automatically appear in the UI.
    )

    /** Packs available for [language] on [deviceTier]. */
    fun forLanguage(language: SupportedLanguage, deviceTier: DeviceTier): List<VoicePack> =
        entries.filter {
            it.language == language && deviceTier.ordinal >= it.requiredTier.ordinal
        }

    fun findById(id: String): VoicePack? = entries.firstOrNull { it.id == id }
}
