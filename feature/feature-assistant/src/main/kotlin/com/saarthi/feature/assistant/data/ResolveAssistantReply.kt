package com.saarthi.feature.assistant.data

/**
 * Decide what the assistant bubble should show when a generation finishes,
 * guaranteeing we NEVER render a blank "broken-looking" bubble — the single
 * worst reliability symptom for a paid app.
 *
 * @param cleanedText   the model's reply with markers stripped (may be blank
 *                      when device memory pressure starved generation, or when
 *                      the reply was only a marker / control token).
 * @param isCancelled   the user pressed Stop.
 * @param isError       generation failed (a non-cancellation throwable).
 * @param partialVisible the bubble's current content — holds either the
 *                      streamed-so-far text or the message `.catch` already set.
 *
 * Pure + top-level `internal` so it is unit-testable without the repository.
 */
internal fun resolveAssistantReply(
    cleanedText: String,
    isCancelled: Boolean,
    isError: Boolean,
    partialVisible: String,
    // Localized fallbacks (English defaults keep the pure unit tests valid).
    errorText: String = "Something went wrong generating a reply. Please try again.",
    stoppedText: String = "Stopped.",
    emptyText: String = "I couldn't generate a reply just now. Please try again — if it keeps " +
        "happening on this device, switch to a lighter model in Settings → Models.",
): String {
    if (cleanedText.isNotBlank()) return cleanedText
    // No usable generated text below this point.
    if (isError) {
        // Keep whatever .catch surfaced; otherwise a generic, non-scary notice.
        return partialVisible.ifBlank { errorText }
    }
    if (isCancelled) {
        // User stopped — keep any partial text; otherwise a brief note.
        return partialVisible.ifBlank { stoppedText }
    }
    // Normal completion but the model produced nothing — almost always device
    // memory pressure (2–3 tokens at <1 tok/s in the logs). Give an actionable
    // next step instead of a blank bubble.
    return emptyText
}
