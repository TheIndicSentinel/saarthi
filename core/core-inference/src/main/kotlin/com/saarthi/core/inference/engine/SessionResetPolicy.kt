package com.saarthi.core.inference.engine

/**
 * Session-reset decisions extracted so they can be unit-tested without LiteRT
 * or Room.
 *
 * [LiteRTInferenceEngine.generateStream] already recycles the Conversation
 * immediately before every `sendMessageAsync` (a second send on a live
 * Conversation SIGKILLs SM8550 / Android 16). A chat switch therefore must
 * **not** JNI-create a replacement Conversation — that paid for a native
 * session every drawer tap, then the next send closed it again
 * (`[SESSION] Session reset` bursts). Close-only (or skip) is enough: the
 * next generate creates the Conversation if needed.
 */

/**
 * Whether [LiteRTInferenceEngine.resetSession] should close the live
 * Conversation. False when there is nothing to close (engine unloaded, or
 * already released after the last turn's `onDone`).
 */
fun shouldCloseConversationOnSessionReset(
    engineLoaded: Boolean,
    hasActiveConversation: Boolean,
): Boolean = engineLoaded && hasActiveConversation

/**
 * Whether switching the UI to [targetSessionId] should call
 * [InferenceEngine.resetSession]. Re-tapping the already-open chat in the
 * drawer is a no-op for KV state.
 */
fun shouldResetEngineOnSessionSwitch(
    currentSessionId: String,
    targetSessionId: String,
): Boolean = currentSessionId != targetSessionId
