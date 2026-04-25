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

    /**
     * Init via file descriptor — avoids Android scoped-storage path restrictions.
     * The fd must remain open for the lifetime of the returned handle.
     * Returns a native context handle (pointer as Long), or -1 on failure.
     */
    external fun nativeInitFd(
        fd: Int,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
    ): Long

    external fun nativeLoadLoraAdapter(
        contextHandle: Long,
        adapterPath: String,
        scale: Float,
    ): Boolean

    external fun nativeClearLoraAdapter(contextHandle: Long)

    external fun nativeGenerate(
        contextHandle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
    ): String

    external fun nativeGenerateStream(
        contextHandle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        tokenCallback: TokenCallback,
    )

    external fun nativeRelease(contextHandle: Long)

    /**
     * Signals the current generation on [contextHandle] to stop.
     * Safe to call from any thread — sets an atomic flag checked inside doGenerate.
     * The native call returns within one token-decode cycle after this is called.
     */
    external fun nativeCancelGeneration(contextHandle: Long)

    /** Returns the last error string logged by llama.cpp (empty if none). */
    external fun nativeGetLastError(): String

    /** Routes native NLOG/NLOGE output into the same debug log file as DebugLogger. */
    external fun nativeSetDebugLogPath(path: String)

    interface TokenCallback {
        fun onToken(token: String): Boolean
    }
}
