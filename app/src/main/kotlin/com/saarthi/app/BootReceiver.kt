package com.saarthi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saarthi.app.wisdom.WisdomNotificationScheduler
import com.saarthi.core.i18n.WisdomNotificationPreference
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-arms the daily wisdom alarm after device reboot.
 *
 * AlarmManager alarms are cleared on reboot; without this receiver the
 * user would silently stop getting the daily wisdom until the next time
 * they open the app. Listening for `BOOT_COMPLETED` and re-enabling the
 * scheduler keeps the experience consistent across reboots without
 * requiring the user to launch anything.
 *
 * Reads the preference instead of unconditionally arming — if the user
 * has turned the toggle off we honour that.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var preference: WisdomNotificationPreference
    @Inject lateinit var scheduler: WisdomNotificationScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (preference.enabled.first()) scheduler.enable()
            } finally {
                pending.finish()
            }
        }
    }
}
