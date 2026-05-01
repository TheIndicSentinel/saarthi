package com.saarthi.core.inference.model

/**
 * Gemma 4-specific configuration and feature detection.
 *
 * Gemma 4 models (E2B, E4B) support:
 * • Frontier-level reasoning with built-in thinking mode
 * • Audio: Automatic Speech Recognition (ASR) + Speech Translation (AST)
 * • Vision: Document parsing, OCR, object detection, UI understanding
 * • 128K context window
 * • 35+ languages (pretrained on 140+)
 *
 * License: Apache 2.0
 * https://ai.google.dev/gemma/docs/gemma_4_license
 */
object Gemma4Config {

    // ─── Feature Flags ──────────────────────────────────────────────────────

    /** Whether audio input is supported (E2B and E4B only). */
    fun supportsAudio(modelFamily: String): Boolean {
        return modelFamily.startsWith("gemma4", ignoreCase = true) && 
               (modelFamily.contains("e2b", ignoreCase = true) || 
                modelFamily.contains("e4b", ignoreCase = true))
    }

    /** Whether vision/image input is supported (all Gemma 4 variants). */
    fun supportsVision(modelFamily: String): Boolean {
        return modelFamily.startsWith("gemma4", ignoreCase = true)
    }

    /** Maximum context length for the model. */
    fun getContextLength(modelFamily: String): Int {
        val family = modelFamily.lowercase()
        return when {
            family.startsWith("gemma4") && family.contains("e2b") -> 128_000
            family.startsWith("gemma4") && family.contains("e4b") -> 128_000
            family.startsWith("gemma4") && family.contains("26b") -> 256_000
            family.startsWith("gemma4") && family.contains("31b") -> 256_000
            family.startsWith("gemma4") -> 128_000 // Default for Gemma 4
            family == "gemma3n" -> 1_280
            family == "gemma3" -> 8_192
            family == "gemma2" -> 8_192
            else -> 2_048
        }
    }

    // ─── Reasoning Mode Configuration ────────────────────────────────────────

    /**
     * Gemma 4 supports built-in reasoning/thinking mode.
     * When enabled, the model outputs internal reasoning before the final answer.
     *
     * System prompt trigger:
     * ```
     * <|think|>
     * [model's internal reasoning]
     * <|channel>thought\n...\n<channel|>
     * [final answer]
     * ```
     */
    fun getReasoningSystemPrompt(enabled: Boolean): String {
        return if (enabled) {
            """<|think|>"""
        } else {
            ""
        }
    }

    // ─── Audio Configuration (E2B/E4B only) ──────────────────────────────────

    /**
     * Maximum audio duration supported: 30 seconds.
     */
    const val MAX_AUDIO_DURATION_SECONDS = 30

    /**
     * Recommended audio sample rate for ASR/AST.
     */
    const val RECOMMENDED_AUDIO_SAMPLE_RATE_HZ = 16000

    // ─── Vision Configuration ────────────────────────────────────────────────

    /**
     * Variable image resolution token budgets.
     * Lower = faster inference, higher = better detail preservation.
     * Supported: 70, 140, 280, 560, 1120
     */
    enum class ImageResolutionBudget(val tokens: Int) {
        LOW(70),           // For classification, fast processing
        MEDIUM_LOW(140),   // Balanced for most tasks
        MEDIUM(280),       // General purpose (recommended)
        MEDIUM_HIGH(560),  // Document/OCR tasks
        HIGH(1120),        // Fine-grained detail (slow)
    }

    fun getRecommendedImageBudget(task: String): ImageResolutionBudget {
        return when (task.lowercase()) {
            "ocr", "document", "text" -> ImageResolutionBudget.MEDIUM_HIGH
            "chart", "diagram" -> ImageResolutionBudget.MEDIUM
            "classification", "tagging" -> ImageResolutionBudget.LOW
            "video" -> ImageResolutionBudget.LOW  // Multiple frames
            else -> ImageResolutionBudget.MEDIUM
        }
    }

    // ─── Model Family Detection ──────────────────────────────────────────────

    fun isGemma4(modelId: String): Boolean {
        return modelId.contains("gemma4", ignoreCase = true) ||
               modelId.contains("gemma-4", ignoreCase = true)
    }

    fun isLatestGeneration(modelFamily: String): Boolean {
        return modelFamily.startsWith("gemma4", ignoreCase = true)
    }

    // ─── Sampling Parameters (Standardized across all models) ──────────────────

    data class SamplingParams(
        val temperature: Float = 1.0f,
        val topP: Float = 0.95f,
        val topK: Int = 64,
    ) {
        companion object {
            fun default(): SamplingParams = SamplingParams()
        }
    }

    // ─── License Information ────────────────────────────────────────────────

    const val LICENSE_NAME = "Apache 2.0"
    const val LICENSE_URL = "https://ai.google.dev/gemma/docs/gemma_4_license"
    const val RELEASE_DATE = "April 2026"
    const val MODEL_SOURCE = "Google DeepMind"

    // ─── Download Hints ─────────────────────────────────────────────────────

    fun getDownloadSizeEstimate(modelId: String): String {
        return when {
            modelId.contains("e2b", ignoreCase = true) -> "~1.8 GB (INT8)"
            modelId.contains("e4b", ignoreCase = true) -> "~2.8 GB (INT8)"
            modelId.contains("26b", ignoreCase = true) -> "~4.5 GB (INT8)"
            modelId.contains("31b", ignoreCase = true) -> "~6.2 GB (INT8)"
            else -> "Unknown"
        }
    }

    fun getExpectedInferenceSpeed(modelId: String, hardwareAccelerated: Boolean): String {
        return when {
            !hardwareAccelerated -> "8-15 tokens/sec (CPU)"
            modelId.contains("e2b", ignoreCase = true) -> "25-40 tokens/sec (GPU)"
            modelId.contains("e4b", ignoreCase = true) -> "20-30 tokens/sec (GPU)"
            modelId.contains("26b", ignoreCase = true) -> "15-25 tokens/sec (GPU)"
            modelId.contains("31b", ignoreCase = true) -> "10-20 tokens/sec (GPU)"
            else -> "Unknown"
        }
    }
}
