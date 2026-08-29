package com.saarthi.core.inference.model

/**
 * Whether a catalog [downloadUrl] needs a Hugging Face Bearer token.
 *
 * In this app only `huggingface.co/google/…` repos are gated (Gemma 3n).
 * `litert-community` and other public hosts download with no Authorization
 * header. First-run auto-pick is Gemma 4 / Compact on public repos, so
 * typical onboarding does not need a token.
 */
fun huggingFaceDownloadRequiresAuth(downloadUrl: String): Boolean {
    val withoutScheme = downloadUrl.substringAfter("://", downloadUrl)
    return withoutScheme.startsWith("huggingface.co/google/", ignoreCase = true)
}

/** Trims a user-pasted token. Empty after trim means “no token”. */
fun resolveHuggingFaceDownloadToken(userSavedToken: String): String = userSavedToken.trim()

enum class HuggingFaceAuthGate {
    /** Public repo, or the user saved a non-blank token. */
    ALLOW,
    /** Gated google/ repo and no saved token — do not start the transfer. */
    NEED_TOKEN,
}

const val HF_TOKEN_REQUIRED_MESSAGE =
    "This model is on a gated Hugging Face repo. Choose it again and paste a read-only token when asked."

fun resolveHuggingFaceAuthGate(
    downloadUrl: String,
    userSavedToken: String,
): HuggingFaceAuthGate {
    val token = resolveHuggingFaceDownloadToken(userSavedToken)
    return if (!huggingFaceDownloadRequiresAuth(downloadUrl) || token.isNotEmpty()) {
        HuggingFaceAuthGate.ALLOW
    } else {
        HuggingFaceAuthGate.NEED_TOKEN
    }
}
