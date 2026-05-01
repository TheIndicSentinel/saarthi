package com.saarthi.core.inference

import com.saarthi.core.inference.model.Gemma4Config
import com.saarthi.core.inference.model.ModelEntry
import timber.log.Timber

/**
 * Model migration helper: tracks upgrade path from Gemma 3 → Gemma 4.
 *
 * Handles:
 * • Detecting current model and recommending upgrade
 * • Graceful fallback if Gemma 4 fails
 * • Feature capability detection
 * • Metadata logging for analytics
 */
class ModelMigrationHelper {

    /**
     * Analyzes the current model and determines if an upgrade to Gemma 4 is recommended.
     */
    fun shouldUpgradeToGemma4(currentModelEntry: ModelEntry?): Boolean {
        if (currentModelEntry == null) return true  // First launch
        
        val isCurrentGemma4 = Gemma4Config.isGemma4(currentModelEntry.id)
        if (isCurrentGemma4) return false  // Already on latest
        
        // Check if current model is outdated
        return currentModelEntry.modelFamily in listOf("gemma3", "gemma3n", "gemma2")
    }

    /**
     * Gets recommended upgrade path based on device capabilities.
     */
    fun getUpgradePath(
        currentModel: ModelEntry?,
        recommendedModels: List<ModelEntry>,
    ): ModelEntry? {
        // Filter to Gemma 4 models only
        val gemma4Models = recommendedModels.filter { Gemma4Config.isGemma4(it.id) }
        if (gemma4Models.isEmpty()) return null
        
        // Prefer E2B (smaller, sufficient for most cases)
        return gemma4Models.find { it.id.contains("e2b", ignoreCase = true) }
            ?: gemma4Models.find { it.id.contains("e4b", ignoreCase = true) }
            ?: gemma4Models.firstOrNull()
    }

    /**
     * Logs metadata about the upgrade decision for analytics.
     */
    fun logUpgradeDecision(
        from: ModelEntry?,
        to: ModelEntry?,
        reason: String,
    ) {
        val fromId = from?.id ?: "none"
        val toId = to?.id ?: "none"
        
        Timber.i(
            "Model upgrade: $fromId → $toId | Reason: $reason | " +
            "Supports Audio: ${if (to != null) Gemma4Config.supportsAudio(to.modelFamily) else "N/A"} | " +
            "Supports Vision: ${if (to != null) Gemma4Config.supportsVision(to.modelFamily) else "N/A"}"
        )
    }

    /**
     * Provides fallback strategy if Gemma 4 load fails.
     */
    fun getFallbackModel(
        failedGemma4Id: String,
        availableModels: List<ModelEntry>,
    ): ModelEntry? {
        // Try other Gemma 4 variants first
        val otherGemma4 = availableModels.find {
            Gemma4Config.isGemma4(it.id) && it.id != failedGemma4Id
        }
        if (otherGemma4 != null) {
            Timber.w("Gemma 4 load failed ($failedGemma4Id). Trying alternate Gemma 4: ${otherGemma4.id}")
            return otherGemma4
        }
        
        // Fall back to Gemma 3n (proven stable)
        val gemma3nFallback = availableModels.find { it.id.contains("gemma3n", ignoreCase = true) }
        if (gemma3nFallback != null) {
            Timber.w("Gemma 4 failed. Falling back to stable Gemma 3n: ${gemma3nFallback.id}")
            return gemma3nFallback
        }
        
        // Last resort: any model marked as stable
        return availableModels.find { "Stable" in it.tags }
            ?: availableModels.firstOrNull()
    }

    /**
     * Returns a human-readable upgrade summary.
     */
    fun getUpgradeSummary(current: ModelEntry?, target: ModelEntry?): String {
        return buildString {
            append("Upgrade to Gemma 4\n\n")
            append("🚀 New Features:\n")
            append("• Frontier-level reasoning (step-by-step thinking)\n")
            if (target != null && Gemma4Config.supportsAudio(target.modelFamily)) {
                append("• 🎤 Audio input (speech recognition & translation)\n")
            }
            if (target != null && Gemma4Config.supportsVision(target.modelFamily)) {
                append("• 👁️ Vision input (OCR, document parsing, UI understanding)\n")
            }
            append("• 📚 128K context window (vs ${current?.contextLength ?: "unknown"})\n")
            append("• 💬 35+ languages supported\n")
            append("\n")
            append("Performance:\n")
            append("• ${Gemma4Config.getExpectedInferenceSpeed(target?.id ?: "", true)}\n")
            append("• Download size: ${Gemma4Config.getDownloadSizeEstimate(target?.id ?: "")}\n")
            append("\n")
            append("License: ${Gemma4Config.LICENSE_NAME}\n")
            append("📖 https://ai.google.dev/gemma\n")
        }
    }
}
