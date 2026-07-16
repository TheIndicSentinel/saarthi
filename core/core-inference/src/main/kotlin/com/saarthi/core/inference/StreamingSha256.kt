package com.saarthi.core.inference

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 over a [File], streamed in fixed-size chunks — safe for the
 * multi-GB model files this app downloads. [PackVerifier.sha256Hex]
 * (feature-assistant, used for the small Kisan content packs) loads its
 * whole input into a single ByteArray via `MessageDigest.digest(ByteArray)`,
 * which would OOM on mid-range RAM for a 2.5-4.4GB model — this streams via
 * repeated `MessageDigest.update()` instead, never holding more than
 * [bufferSizeBytes] in memory at once.
 */
object StreamingSha256 {
    // 512KB — larger than the download write loop's 32KB buffer since this is
    // a one-shot sequential pass over an already-complete file, not
    // interleaved with network I/O.
    private const val DEFAULT_BUFFER_BYTES = 512 * 1024

    fun hex(file: File, bufferSizeBytes: Int = DEFAULT_BUFFER_BYTES): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(bufferSizeBytes)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
