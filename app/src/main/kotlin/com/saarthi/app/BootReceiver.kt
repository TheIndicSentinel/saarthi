package com.saarthi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saarthi.app.wisdom.WisdomNotificationScheduler
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.i18n.WisdomNotificationPreference
import com.saarthi.core.inference.ModelCatalog
import com.saarthi.core.inference.ModelDownloadManager
import com.saarthi.core.inference.ModelDownloadService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-arms the daily wisdom alarm after device reboot, and nudges the user
 * to resume an interrupted model download.
 *
 * AlarmManager alarms are cleared on reboot; without this receiver the
 * user would silently stop getting the daily wisdom until the next time
 * they open the app. Listening for `BOOT_COMPLETED` and re-enabling the
 * scheduler keeps the experience consistent across reboots without
 * requiring the user to launch anything.
 *
 * Model downloads cannot be restarted from here: Android 12+ rejects
 * startForegroundService from a [BOOT_COMPLETED] receiver. A leftover
 * tmp file is detected and a tap-to-open notification is posted instead;
 * opening the app runs [ModelDownloadManager.reattachActiveDownloads].
 *
 * Reads the wisdom preference instead of unconditionally arming — if the
 * user has turned the toggle off we honour that.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var preference: WisdomNotificationPreference
    @Inject lateinit var scheduler: WisdomNotificationScheduler
    @Inject lateinit var downloadManager: ModelDownloadManager
    @Inject lateinit var modelCatalog: ModelCatalog
    @Inject lateinit var languageManager: LanguageManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (preference.enabled.first()) scheduler.enable()
                val partials = downloadManager.findResumablePartials(modelCatalog.allModels)
                if (partials.isNotEmpty()) {
                    ModelDownloadService.notifyResumeOnOpen(
                        context,
                        languageManager.selectedLanguage.first(),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
