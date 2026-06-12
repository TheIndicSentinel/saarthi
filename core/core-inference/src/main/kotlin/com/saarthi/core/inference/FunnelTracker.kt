package com.saarthi.core.inference

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight, privacy-respecting conversion-funnel instrumentation.
 *
 * You cannot improve numbers you cannot see. This records the key funnel
 * milestones (onboarding → download wall → activation → paywall → unlock) into
 * the same `saarthi_debug.log` the founder already reviews — greppable by the
 * `[FUNNEL]` tag. It is **fully local**: no network, no account, and it records
 * only the event NAME and a timestamp — never prompt text, filenames, or any
 * user content. That keeps it consistent with the offline-privacy promise.
 *
 * [trackOnce] de-dupes "first_*" milestones within a process so a single
 * session's activation isn't counted on every send.
 */
@Singleton
class FunnelTracker @Inject constructor() {

    private val firedOnce = ConcurrentHashMap.newKeySet<String>()

    /** Record an event every time it happens. */
    fun track(event: FunnelEvent) {
        DebugLogger.log("FUNNEL", event.id)
    }

    /** Record an event at most once per process — for first-time milestones. */
    fun trackOnce(event: FunnelEvent) {
        if (firedOnce.add(event.id)) {
            DebugLogger.log("FUNNEL", "${event.id} (first)")
        }
    }
}

/** The funnel milestones, ordered from first run to monetisation. */
enum class FunnelEvent(val id: String) {
    ONBOARDING_STARTED("onboarding_started"),
    MODEL_DOWNLOAD_STARTED("model_download_started"),
    MODEL_DOWNLOAD_COMPLETED("model_download_completed"),
    ONBOARDING_COMPLETED("onboarding_completed"),
    FIRST_CHAT_SENT("first_chat_sent"),
    FIRST_DOC_ATTACHED("first_doc_attached"),
    PAYWALL_VIEWED("paywall_viewed"),
    PRO_UNLOCKED("pro_unlocked"),
}
