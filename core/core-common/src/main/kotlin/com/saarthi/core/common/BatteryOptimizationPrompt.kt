package com.saarthi.core.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers around the OS battery-optimization whitelist.
 *
 * When Saarthi is generating, the model runs natively on the GPU/CPU for tens
 * of seconds at a time. On Samsung OneUI / Xiaomi MIUI / Oppo ColorOS, Doze
 * and Battery Saver aggressively kill processes that aren't whitelisted —
 * even with a foreground service. The result the user sees is a half-streamed
 * reply that just stops and the FGS notification staying stuck.
 *
 * Whitelisting via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is the only
 * documented mitigation. We ask **once** (the `shown` flag persists), with a
 * clear explanation, and never nag again.
 */
object BatteryOptimizationPrompt {
    private const val PREFS = "saarthi_app_prefs"
    private const val KEY_PROMPTED = "battery_opt_prompted"

    /** True if the app is *not* on the OS battery-optimization whitelist. */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun hasPrompted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PROMPTED, false)

    fun markPrompted(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    /** Launch the system dialog. Best-effort — falls back to the app's
     *  general battery settings if the direct intent isn't available. */
    fun requestWhitelist(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }.onFailure {
            // Fall back to the user-facing list — they have to tap "All apps" → Saarthi → Don't optimize
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
    }
}
