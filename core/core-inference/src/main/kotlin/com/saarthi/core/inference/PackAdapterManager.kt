package com.saarthi.core.inference

import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.DownloadProgress
import com.saarthi.core.inference.model.LoraEntry
import com.saarthi.core.inference.model.PackType
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages domain-pack switching at runtime by loading / unloading LoRA adapters.
 *
 * Usage flow:
 *   1. User downloads a base model during onboarding → [InferenceEngine.initialize]
 *   2. User selects a domain pack (e.g. KNOWLEDGE) from HomeScreen
 *   3. [switchToPack] checks if adapter is downloaded:
 *      - Yes → calls [InferenceEngine.loadLoraAdapter] (< 1s, no model reload)
 *      - No  → returns [PackSwitchResult.AdapterRequired] so UI can prompt download
 *   4. When user returns to BASE, [clearAdapter] reverts to base model
 *
 * For MediaPipe models (no LoRA support), packs still work via system-prompt-only
 * — [switchToPack] always returns [PackSwitchResult.ReadySystemPromptOnly].
 */
@Singleton
class PackAdapterManager @Inject constructor(
    private val inferenceEngine: InferenceEngine,
    private val modelCatalog: ModelCatalog,
    private val downloadManager: ModelDownloadManager,
) {
    private var activeModelFamily: String = ""

    /** Call this once after the base model is successfully loaded. */
    fun setActiveModelFamily(modelFamily: String) {
        activeModelFamily = modelFamily
        Timber.d("PackAdapterManager: active family = $modelFamily")
    }

    /**
     * Switch to the given pack. Results:
     *  - [PackSwitchResult.Ready] — adapter loaded (or BASE pack, no adapter needed)
     *  - [PackSwitchResult.ReadySystemPromptOnly] — adapter not available for this family, system-prompt only
     *  - [PackSwitchResult.AdapterRequired] — adapter exists in catalog but not downloaded yet
     */
    suspend fun switchToPack(packType: PackType): PackSwitchResult {
        if (packType == PackType.BASE) {
            inferenceEngine.clearLoraAdapter()
            return PackSwitchResult.Ready
        }

        val lora = modelCatalog.loraForPack(packType, activeModelFamily)
            ?: return PackSwitchResult.ReadySystemPromptOnly   // no adapter for this family yet

        val localFile = downloadManager.localPathFor(lora)
        if (!localFile.exists()) {
            return PackSwitchResult.AdapterRequired(lora)
        }

        inferenceEngine.loadLoraAdapter(localFile.absolutePath, lora.defaultScale)
        return PackSwitchResult.Ready
    }

    /** Download an adapter and emit progress. After completion, call [switchToPack] again. */
    fun downloadAdapter(lora: LoraEntry): Flow<DownloadProgress> =
        downloadManager.downloadLora(lora)

    fun clearAdapter() = inferenceEngine.clearLoraAdapter()
}

sealed class PackSwitchResult {
    /** Adapter loaded (or BASE — no adapter required). */
    data object Ready : PackSwitchResult()
    /** No adapter available for this model family yet; system-prompt-only mode active. */
    data object ReadySystemPromptOnly : PackSwitchResult()
    /** Adapter exists in catalog but needs to be downloaded first. */
    data class AdapterRequired(val lora: LoraEntry) : PackSwitchResult()
}
