package com.saarthi.app.navigation

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saarthi.app.BuildConfig
import com.saarthi.core.ui.components.SaarthiTopBar
import com.saarthi.core.ui.theme.SaarthiColors

private const val SUPPORT_EMAIL = "inerd1412@gmail.com"

/**
 * Help & support screen (P0 #6). Gives users a real, low-friction way to reach
 * support and report problems — required for trust and Play submission. Opens a
 * pre-filled email with device/app diagnostics; no FileProvider needed (the
 * user attaches the debug log from Downloads, whose location is shown here).
 */
@Composable
fun SupportScreen(onBack: () -> Unit, language: com.saarthi.core.i18n.SupportedLanguage) {
    val context = LocalContext.current

    fun emailSupport(subject: String) {
        val diagnostics = buildString {
            append("\n\n———\nApp: Saarthi ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("(Tip: attach Downloads/saarthi_debug.log if you can.)")
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, diagnostics)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Email support")) }
    }

    Column(modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg)) {
        SaarthiTopBar(title = "Help & support", subtitle = "We're a small team — we read every message", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SupportCard(
                icon = { Icon(Icons.Outlined.Email, null, tint = SaarthiColors.Gold, modifier = Modifier.size(22.dp)) },
                title = "Contact support",
                body = "Questions, feedback, or anything else. Your device details are added automatically so we can help faster.",
            ) {
                Button(
                    onClick = { emailSupport("Saarthi — support") },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SaarthiColors.Marigold),
                ) {
                    Text("Email $SUPPORT_EMAIL", color = SaarthiColors.OnMarigold, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }

            SupportCard(
                icon = { Icon(Icons.Outlined.BugReport, null, tint = SaarthiColors.Gold, modifier = Modifier.size(22.dp)) },
                title = "Report a problem",
                body = "If a reply came out wrong or the app misbehaved, tell us what happened. Attaching Downloads/saarthi_debug.log helps us trace it.",
            ) {
                Button(
                    onClick = { emailSupport("Saarthi — problem report") },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SaarthiColors.Surface),
                ) {
                    Text(language.reportIssue, color = SaarthiColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }

            // Refund/purchase copy is shown ONLY when Saarthi Pro is live. In v1
            // there is no billing (see FeatureFlags.PRO_ENABLED), so a refund
            // policy would be misleading — hide it until Pro ships.
            if (com.saarthi.core.i18n.FeatureFlags.PRO_ENABLED) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SaarthiColors.Surface)
                        .border(1.dp, SaarthiColors.Border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Text(
                        "Refunds: not happy with Saarthi Pro? Email us within 7 days of purchase and we'll sort it out. " +
                            "Google Play purchases also follow Play's standard refund policy.",
                        style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3, fontSize = 12.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold, color = SaarthiColors.Text, fontSize = 16.sp,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text2, fontSize = 13.sp))
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}
