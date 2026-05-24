package com.saarthi.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saarthi.app.navigation.SaarthiNavHost
import com.saarthi.core.ui.theme.SaarthiTheme
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
