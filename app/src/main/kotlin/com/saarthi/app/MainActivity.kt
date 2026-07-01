package com.saarthi.app

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.saarthi.app.navigation.SaarthiNavHost
import com.saarthi.core.i18n.LanguageManager
import com.saarthi.core.ui.theme.SaarthiTheme
import com.saarthi.core.ui.theme.ThemeMode
import com.saarthi.feature.onboarding.domain.OnboardingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var onboardingRepository: OnboardingRepository
    @Inject lateinit var languageManager: LanguageManager

    /**
     * Drives the in-app exact-alarm rationale dialog. Set true (once, after
     * onboarding, only if the permission is missing) so we can explain WHY
     * before Android's bare "Alarms & reminders" system screen appears.
     */
    private var showExactAlarmRationale by mutableStateOf(false)

    /**
     * Android 13+ (API 33+) requires runtime grant of POST_NOTIFICATIONS
     * before any `NotificationManagerCompat.notify(...)` will actually
     * surface a notification. Without this:
     *  • Download progress notifications never appear (ModelDownloadService
     *    sets a Foreground notification but the system silently suppresses it).
     *  • Daily wisdom alarms fire on schedule but the post call is a no-op.
     *  • Reminders fire but the user sees nothing.
     *
     * We ignore the result — the receivers all gate on the permission
     * before posting, so a denial degrades gracefully. The user can grant
     * it later from system Settings; we only ask once per install.
     */
    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op: graceful degradation if denied */ }

    /**
     * Exact-alarm ("Alarms & reminders") access request. On Android 13+ this
     * special access is denied by default, and without it chat reminders fall
     * back to inexact alarms that aggressive OEM battery management (Samsung One
     * UI, MIUI) defers indefinitely — so the notification never fires on time.
     * We send the user to the system toggle ONCE, after onboarding, and ignore
     * the result: reminders degrade to best-effort if they decline.
     */
    private val exactAlarmLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* no-op: reminders degrade gracefully if not granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureNotificationPermission()
        ensureExactAlarmAccess()
        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val themeMode by themeViewModel.mode.collectAsStateWithLifecycle()
            SaarthiTheme(mode = themeMode) {
                // enableEdgeToEdge() makes the status/navigation bars
                // transparent and picks icon contrast from the *system*
                // dark-mode flag — so when the app is in LIGHT theme but the
                // phone is in dark mode, the bar icons (clock, battery,
                // signal) stay white and vanish over our cream background.
                // Re-derive the contrast from the in-app theme instead:
                // dark icons on the light theme, light icons on the dark one.
                val lightBars = themeMode == ThemeMode.LIGHT
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                SaarthiNavHost()

                if (showExactAlarmRationale) {
                    val lang by languageManager.selectedLanguage.collectAsStateWithLifecycle()
                    AlertDialog(
                        onDismissRequest = { showExactAlarmRationale = false },
                        title = { Text(lang.remindersPermTitle) },
                        text = { Text(lang.remindersPermBody) },
                        confirmButton = {
                            TextButton(onClick = {
                                showExactAlarmRationale = false
                                launchExactAlarmSettings()
                            }) { Text(lang.remindersPermContinue) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExactAlarmRationale = false }) {
                                Text(lang.remindersPermLater)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Ask for exact-alarm access exactly once, and only AFTER onboarding is
     * complete so the system settings screen never interrupts first run. On
     * Android ≤ 12 the access is granted by default, so there is nothing to do.
     */
    private fun ensureExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        lifecycleScope.launch {
            onboardingRepository.isOnboardingComplete().first { it }
            val prefs = getSharedPreferences(PERMS_PREFS, MODE_PRIVATE)
            if (prefs.getBoolean(KEY_EXACT_ALARM_ASKED, false)) return@launch
            val am = getSystemService(AlarmManager::class.java) ?: return@launch
            if (am.canScheduleExactAlarms()) return@launch
            // Mark asked first — we only ever prompt here once, even if the user
            // dismisses the rationale or backs out without granting.
            prefs.edit().putBoolean(KEY_EXACT_ALARM_ASKED, true).apply()
            // Show the in-app rationale; the system screen is launched only if
            // the user taps "Continue" (see the dialog in onCreate).
            showExactAlarmRationale = true
        }
    }

    /** Open Android's "Alarms & reminders" system screen for this app. */
    private fun launchExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            exactAlarmLauncher.launch(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }
    }

    private companion object {
        const val PERMS_PREFS = "saarthi_permissions"
        const val KEY_EXACT_ALARM_ASKED = "exact_alarm_asked"
    }
}
