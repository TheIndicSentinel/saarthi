package com.saarthi.core.i18n

/**
 * User-facing chat text when inference is not ready to generate.
 * Distinguishes first load / background reload from a genuine not-ready failure.
 */
fun SupportedLanguage.chatInferenceNotReadyMessage(
    initializing: Boolean,
    reloadingAfterRelease: Boolean,
    modelNotReady: String,
): String = when {
    initializing -> "⏳ $loadingModelBody"
    reloadingAfterRelease -> "⏳ $reloadingModelBanner"
    else -> modelNotReady
}
