package com.saarthi.core.i18n

/**
 * Compile-time feature flags — the single source of truth for launch/shipping
 * gating. The app is 100% offline, so there is no remote config: flip a flag,
 * rebuild, ship. Keep this list short and heavily commented so the current
 * launch state is obvious to a future maintainer.
 */
object FeatureFlags {
    /**
     * Saarthi Pro (paid tier) master switch.
     *
     * **FALSE (v1 / first Play release):** there is NO Google Play Billing
     * integration yet, so Pro ships OFF — every feature is FREE
     * ([EntitlementManager.isPro] reports `true`) and the Pro upsell / paywall
     * UI is hidden. This is REQUIRED for Play review: a purchase flow with no
     * real Play Billing (the old "Unlock (beta)" button) is a Play Payments
     * policy violation and a guaranteed rejection. With the flag off there is no
     * purchase UI and no dead-end locked features.
     *
     * **TRUE (future):** once Google Play Billing is wired into
     * [EntitlementManager.setProUnlocked] (BillingClient purchase/restore →
     * setProUnlocked(true)), flip this to `true`. The paywall UI reappears,
     * [isPro] reflects the *verified* purchase, and the existing [Entitlements]
     * free-tier gates apply. No call sites change — only this flag + the billing
     * hook. Remember to also remove any "(beta)/(testing)" debug labels in
     * PaywallScreen before enabling.
     */
    const val PRO_ENABLED = false
}
