package com.saarthi.core.i18n

/**
 * Free vs Pro feature policy — pure, dependency-free, single source of truth for
 * the free-tier limits, so gate decisions are unit-testable and consistent
 * across every call site.
 *
 * **Gating is DORMANT during the beta.** [ENFORCED] is `false`, so every gate
 * returns "allowed" / unlimited for everyone regardless of Pro status — current
 * beta testers are never degraded by this code landing. Flip [ENFORCED] to
 * `true` on the day real Play Billing ships and the free-tier limits below begin
 * to apply to non-Pro users.
 *
 * The per-call `enforced` parameter defaults to [ENFORCED] so production callers
 * just pass `isPro`, while tests can exercise both the enforced and dormant
 * branches explicitly (independent of the current flag value).
 */
object Entitlements {

    /**
     * Master switch. While `false` (beta) all gates are dormant. Set `true` when
     * Play Billing is live so the free-tier limits start applying to free users.
     */
    const val ENFORCED = false

    // ── Free-tier limits (apply only when ENFORCED and the user is NOT Pro) ──
    /** Free users may keep the PDF/doc "wow" — one document, generous page cap. */
    const val FREE_MAX_DOCUMENTS = 1
    const val FREE_MAX_DOC_PAGES = 15

    /** True when [feature] is available to a user with the given Pro status. */
    fun isAllowed(
        feature: ProFeature,
        isPro: Boolean,
        enforced: Boolean = ENFORCED,
    ): Boolean = isPro || !enforced

    /** Max documents the user may index in one chat (unlimited for Pro / dormant). */
    fun maxDocuments(isPro: Boolean, enforced: Boolean = ENFORCED): Int =
        if (isPro || !enforced) Int.MAX_VALUE else FREE_MAX_DOCUMENTS

    /** Max pages indexed per document (unlimited for Pro / dormant). */
    fun maxDocPages(isPro: Boolean, enforced: Boolean = ENFORCED): Int =
        if (isPro || !enforced) Int.MAX_VALUE else FREE_MAX_DOC_PAGES
}

/** The features that sit behind Saarthi Pro (per the founder-plan decision). */
enum class ProFeature {
    /** Attach beyond the free-trial document / page allowance, persisted across restarts. */
    UNLIMITED_DOCUMENTS,
    /** Full long-reply read-aloud + hands-free voice mode. */
    VOICE_READ_ALOUD,
    /** More saved memories + export / import / local backup. */
    RICHER_MEMORY,
}
