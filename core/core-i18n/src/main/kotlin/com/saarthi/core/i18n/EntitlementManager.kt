package com.saarthi.core.i18n

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted "Saarthi Pro" entitlement — the single source of truth for whether
 * the user has unlocked Pro.
 *
 * Billing-backend agnostic by design. Today [setProUnlocked] is driven by a
 * LOCAL unlock (a redeem/restore/dev path) so the gating architecture compiles
 * and is testable WITHOUT Google Play Billing. When the Play Console in-app
 * product exists, the Play `BillingClient` verification simply calls
 * [setProUnlocked] behind this same interface — no call sites change.
 *
 * Stored in the shared "saarthi_prefs" DataStore (see [dataStore]); the read
 * side is a hot [StateFlow] so feature gates and the paywall react immediately
 * to an unlock.
 */
@Singleton
class EntitlementManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = booleanPreferencesKey("pro_unlocked")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** True when Saarthi Pro is unlocked on this device. Defaults to false. */
    val isPro: StateFlow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[key] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Unlock or revoke Pro. Called by the local unlock now; by the Play
     * `BillingClient` purchase/restore callback once billing is wired.
     */
    suspend fun setProUnlocked(unlocked: Boolean) {
        context.dataStore.edit { it[key] = unlocked }
    }
}
