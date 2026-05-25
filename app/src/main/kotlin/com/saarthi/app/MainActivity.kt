package com.saarthi.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saarthi.app.navigation.SaarthiNavHost
import com.saarthi.core.ui.theme.SaarthiTheme
import com.saarthi.core.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Android 13+ (API 33+) requires runtime grant of POST_NOTIFICATIONS
     * before any `NotificationManagerCompat.notify(...)` will actually
     * surface a notification. Without this:
     *  • Download progress notifications never appear (ModelDownloadWorker
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureNotificationPermission()
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
}
