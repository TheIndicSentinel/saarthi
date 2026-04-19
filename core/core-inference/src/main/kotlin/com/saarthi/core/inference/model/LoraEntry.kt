package com.saarthi.core.inference.model

/**
 * A LoRA adapter that adds domain-specific knowledge/behaviour on top of a base model.
 *
 * Adapters are small (~50–300 MB) GGUF files trained via QLoRA on top of a specific
 * model family. One adapter file works for all quant levels of the same family
 * (e.g. a Qwen2.5 adapter works for both Q4_K_M and Q8_0 variants).
 *
 * Industry standard: GGUF LoRA format, supported natively by llama.cpp via
 * llama_adapter_lora_init() + llama_set_adapters_lora().
 */
data class LoraEntry(
    val id: String,
    val packType: PackType,
    /** Base model family this adapter was trained on, e.g. "qwen2.5", "llama3.2", "gemma2". */
    val modelFamily: String,
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val fileSizeBytes: Long,
    /** Blending scale applied at inference. 1.0 = full fine-tune, 0.7 = softer blend. */
    val defaultScale: Float = 1.0f,
) {
    val fileSizeMb: Int get() = (fileSizeBytes / 1_048_576).toInt()
    val fileName: String get() = downloadUrl.substringAfterLast('/')
}
