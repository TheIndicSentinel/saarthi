package com.saarthi.app.packs

/**
 * Whether to enqueue the 24h [PackUpdateWorker].
 *
 * An empty [BuildConfig.KISAN_PACK_MANIFEST_URL] used to still enqueue a
 * CONNECTED + battery-not-low periodic job that only ever returned
 * [com.saarthi.feature.assistant.data.PackUpdateOutcome.Unavailable]. That
 * woke WorkManager (and the radio, when constraints lined up) for a no-op
 * poll. Skip enqueue when there is nothing to fetch; cancel any leftover
 * unique work from older builds so it does not keep firing after upgrade.
 */
object PackUpdateSchedulePolicy {
    fun shouldEnqueue(manifestUrl: String): Boolean = manifestUrl.isNotBlank()
}
