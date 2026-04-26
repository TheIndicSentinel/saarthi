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
     * Init via real filesystem path.  Preferred over nativeInitFd when the model
     * is in app-private storage: avoids /proc/self/fd/ mmap page-fault restrictions
     * that Samsung OneUI / Android 16 SELinux enforces during inference.
     * use_mmap is disabled internally so there are no page faults at decode time.
     */
    external fun nativeInitFromPath(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
    ): Long

    /**
     * Init via file descriptor — fallback for content-provider URIs without a
     * real filesystem path. use_mmap is also disabled here for the same reason.
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
