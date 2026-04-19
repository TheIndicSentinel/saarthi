package com.saarthi.core.inference.engine

object LlamaCppBridge {

    private var loaded = false

    fun tryLoad(): Boolean {
        if (loaded) return true
        return runCatching {
            System.loadLibrary("llama_bridge")
            loaded = true
        }.isSuccess
    }

    /** Returns a native context handle (pointer as Long), or -1 on failure. */
    external fun nativeInit(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
    ): Long

    /**
     * Load a GGUF LoRA adapter on top of the current model.
     * Can be called at any time after [nativeInit] to swap the active adapter.
     * Returns true on success.
     */
    external fun nativeLoadLoraAdapter(
        contextHandle: Long,
        adapterPath: String,
        scale: Float,
    ): Boolean

    /** Remove the active LoRA adapter, reverting to base-model behaviour. */
    external fun nativeClearLoraAdapter(contextHandle: Long)

    /** Synchronous generation — blocks until full response is ready. */
    external fun nativeGenerate(
        contextHandle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
    ): String

    /**
     * Streaming generation — calls [tokenCallback] for each token as it is produced.
     * Returns when done or when the callback returns false.
     */
    external fun nativeGenerateStream(
        contextHandle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        tokenCallback: TokenCallback,
    )

    external fun nativeRelease(contextHandle: Long)

    interface TokenCallback {
        /** Called per token. Return false to abort generation. */
        fun onToken(token: String): Boolean
    }
}
