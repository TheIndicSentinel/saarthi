package com.saarthi.app.packs

import androidx.work.NetworkType

/**
 * Whether to enqueue the 24h [PackUpdateWorker], and on which network.
 *
 * An empty [BuildConfig.KISAN_PACK_MANIFEST_URL] used to still enqueue a
 * CONNECTED + battery-not-low periodic job that only ever returned
 * [com.saarthi.feature.assistant.data.PackUpdateOutcome.Unavailable]. That
 * woke WorkManager (and the radio, when constraints lined up) for a no-op
 * poll. Skip enqueue when there is nothing to fetch; cancel any leftover
 * unique work from older builds so it does not keep firing after upgrade.
 *
 * [requiredNetworkType] is [NetworkType.CONNECTED] (Wi-Fi if connected,
 * otherwise mobile). [NetworkType.UNMETERED] would stall rural / mobile-only
 * users who never join Wi-Fi.
 */
object PackUpdateSchedulePolicy {
    fun shouldEnqueue(manifestUrl: String): Boolean = manifestUrl.isNotBlank()

    val requiredNetworkType: NetworkType = NetworkType.CONNECTED
}
