package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.inference.model.DeviceTier

/**
 * Catalog of downloadable Piper ONNX voice packs.
 *
 * Only languages with verified, commercially-usable (MIT-licensed) Piper
 * voices are listed. Languages with no verified free VITS model fall back
 * to Android system TTS permanently.
 *
 * Verified as of June 2026:
 *  • Hindi (hi_IN)    — pratham (male), priyamvada (female) — MIT
 *  • Telugu (te_IN)   — maya (female)                       — MIT
 *  • Malayalam (ml_IN)— arjun (male), meera (female)        — MIT
 *
 * All other languages in [SupportedLanguage] (Tamil, Bengali, Marathi,
 * Kannada, Gujarati, Punjabi, Odia) have no vetted free VITS model today
 * and are NOT listed here — they use system TTS fallback.
 *
 * Files are hosted on Hugging Face at the rhasspy/piper-voices repository
 * (MIT license). Each pack is two files: the ONNX model + a JSON config.
 * Both must be downloaded into the same directory for the engine to load.
 *
 * [requiredTier] gates neural TTS to MID+ devices (≥ 6 GB total RAM).
 * On LOW/MINIMAL devices the LLM is already near or over the RAM budget —
 * adding ~200 MB of TTS runtime + inference pressure would cause OOM.
 */
object VoiceCatalog {

    private const val HF_BASE =
        "https://huggingface.co/rhasspy/piper-voices/resolve/main"

    data class VoicePack(
        val id: String,
        val language: SupportedLanguage,
        val displayName: String,
        /** "male" or "female" — purely for display in settings. */
        val gender: String,
        /** Relative subdirectory under files/voices/ where files are stored. */
        val subDir: String,
        val onnxUrl: String,
        val configUrl: String,
        val onnxFilename: String,
        val configFilename: String,
        /** Approximate download size shown to the user before they tap Download. */
        val approximateSizeMb: Int,
        /** SHA-256 of the .onnx file. Validate after download; reject on mismatch. */
        val onnxSha256: String,
        val requiredTier: DeviceTier = DeviceTier.MID,
    )

    val entries: List<VoicePack> = listOf(
        // ── Hindi ───────────────────────────────────────────────────────────
        VoicePack(
            id             = "hi_IN-pratham-medium",
            language       = SupportedLanguage.HINDI,
            displayName    = "Hindi · Pratham",
            gender         = "male",
            subDir         = "hi/hi_IN/pratham/medium",
            onnxUrl        = "$HF_BASE/hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx",
            configUrl      = "$HF_BASE/hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx.json",
            onnxFilename   = "hi_IN-pratham-medium.onnx",
            configFilename = "hi_IN-pratham-medium.onnx.json",
            approximateSizeMb = 64,
            // Verify this SHA-256 against the actual file before shipping.
            // Run: sha256sum hi_IN-pratham-medium.onnx
            onnxSha256     = "VERIFY_BEFORE_SHIP",
        ),
        VoicePack(
            id             = "hi_IN-priyamvada-medium",
            language       = SupportedLanguage.HINDI,
            displayName    = "Hindi · Priyamvada",
            gender         = "female",
            subDir         = "hi/hi_IN/priyamvada/medium",
            onnxUrl        = "$HF_BASE/hi/hi_IN/priyamvada/medium/hi_IN-priyamvada-medium.onnx",
            configUrl      = "$HF_BASE/hi/hi_IN/priyamvada/medium/hi_IN-priyamvada-medium.onnx.json",
            onnxFilename   = "hi_IN-priyamvada-medium.onnx",
            configFilename = "hi_IN-priyamvada-medium.onnx.json",
            approximateSizeMb = 64,
            onnxSha256     = "VERIFY_BEFORE_SHIP",
        ),

        // ── Telugu ──────────────────────────────────────────────────────────
        VoicePack(
            id             = "te_IN-maya-medium",
            language       = SupportedLanguage.TELUGU,
            displayName    = "Telugu · Maya",
            gender         = "female",
            subDir         = "te/te_IN/maya/medium",
            onnxUrl        = "$HF_BASE/te/te_IN/maya/medium/te_IN-maya-medium.onnx",
            configUrl      = "$HF_BASE/te/te_IN/maya/medium/te_IN-maya-medium.onnx.json",
            onnxFilename   = "te_IN-maya-medium.onnx",
            configFilename = "te_IN-maya-medium.onnx.json",
            approximateSizeMb = 63,
            onnxSha256     = "VERIFY_BEFORE_SHIP",
        ),

        // ── Malayalam ── not included ─────────────────────────────────────────
        // Piper has ml_IN/arjun + ml_IN/meera (MIT), but Malayalam is not
        // in Saarthi's SupportedLanguage enum. Add here if the language is
        // added to the app in future.
    )

    /** Packs available for [language] on [deviceTier]. */
    fun forLanguage(language: SupportedLanguage, deviceTier: DeviceTier): List<VoicePack> =
        entries.filter {
            it.language == language &&
                deviceTier.ordinal >= it.requiredTier.ordinal
        }

    fun findById(id: String): VoicePack? = entries.firstOrNull { it.id == id }
}
