package com.saarthi.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.i18n.EntitlementManager
import com.saarthi.core.inference.FunnelEvent
import com.saarthi.core.inference.FunnelTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Saarthi Pro paywall screen.
 *
 * The unlock is currently a LOCAL toggle (beta) so the Pro state is real and
 * testable end-to-end without Google Play Billing. When the Play Console in-app
 * product exists, [unlock] / [restore] are the exact seams the `BillingClient`
 * purchase + restore callbacks plug into — they already funnel through
 * [EntitlementManager.setProUnlocked], so no UI changes are needed then.
 */
@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val entitlements: EntitlementManager,
    private val funnel: FunnelTracker,
) : ViewModel() {

    init {
        // The user reached the paywall — a key conversion-funnel milestone.
        funnel.track(FunnelEvent.PAYWALL_VIEWED)
    }

    val isPro: StateFlow<Boolean> = entitlements.isPro

    /** Local beta unlock — replaced by the verified Play purchase callback later. */
    fun unlock() = viewModelScope.launch {
        entitlements.setProUnlocked(true)
        funnel.track(FunnelEvent.PRO_UNLOCKED)
    }

    /** Testing affordance to drop back to the free state. */
    fun lock() = viewModelScope.launch { entitlements.setProUnlocked(false) }

    /**
     * Restore a previous purchase. Local build: re-affirms the cached
     * entitlement (no-op beyond reading state). With billing: queries
     * `queryPurchasesAsync` and calls [EntitlementManager.setProUnlocked].
     */
    fun restore() = viewModelScope.launch { /* no-op until BillingClient is wired */ }
}
