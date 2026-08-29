package com.saarthi.core.inference

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable set of funnel event ids that have already logged `(first)`.
 *
 * [FunnelTracker.trackOnce] used an in-process [ConcurrentHashMap], so a
 * process death (swipe-away, LMK, "=== Saarthi start session ===") made
 * `first_chat_sent (first)` / `first_doc_attached (first)` fire again.
 * Implementations must be safe to call from the main thread.
 */
interface FunnelOnceStore {
    /**
     * @return true if [id] had never been recorded — caller should log `(first)`.
     */
    fun recordOnce(id: String): Boolean
}

/**
 * JVM / unit-test store. Pass [initiallyFired] to simulate a process restart
 * that already persisted those ids.
 */
class InMemoryFunnelOnceStore(
    initiallyFired: Collection<String> = emptySet(),
) : FunnelOnceStore {
    private val fired = ConcurrentHashMap.newKeySet<String>().apply { addAll(initiallyFired) }

    override fun recordOnce(id: String): Boolean = fired.add(id)
}

// Own file — DataStore forbids two delegates on the same name in one process
// (see DownloadFailureStore / HuggingFaceTokenManager).
private val Context.funnelDataStore: DataStore<Preferences> by preferencesDataStore("saarthi_funnel")
private val FIRED_ONCE_KEY = stringSetPreferencesKey("fired_once_ids")

private const val HYDRATE_TIMEOUT_MS = 1_500L

/**
 * Production store: hydrates the in-memory set from DataStore, then treats
 * [recordOnce] as a sync de-dupe against that set and persists new ids.
 *
 * First chat is after onboarding, so hydrate has almost always finished
 * before [recordOnce] runs. If it has not, we wait briefly (not forever)
 * so a slow disk read cannot ANR the send path.
 */
@Singleton
class DataStoreFunnelOnceStore @Inject constructor(
    @ApplicationContext context: Context,
) : FunnelOnceStore {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fired = ConcurrentHashMap.newKeySet<String>()
    private val hydrated = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                val prefs = appContext.funnelDataStore.data.first()
                fired.addAll(prefs[FIRED_ONCE_KEY].orEmpty())
            } finally {
                hydrated.complete(Unit)
            }
        }
    }

    override fun recordOnce(id: String): Boolean {
        if (!hydrated.isCompleted) {
            runBlocking {
                withTimeoutOrNull(HYDRATE_TIMEOUT_MS) { hydrated.await() }
            }
        }
        val added = fired.add(id)
        if (added) {
            // Read the live set inside edit so a slower persist of an earlier
            // id cannot overwrite a later id that was already added in memory.
            scope.launch {
                runCatching {
                    appContext.funnelDataStore.edit { it[FIRED_ONCE_KEY] = fired.toSet() }
                }
            }
        }
        return added
    }
}
