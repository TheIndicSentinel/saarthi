package com.saarthi.app.navigation

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saarthi.app.PaywallViewModel
import com.saarthi.core.ui.components.SaarthiTopBar
import com.saarthi.core.ui.theme.SaarthiColors

/**
 * Saarthi Pro paywall / value screen.
 *
 * During the beta the gates are dormant (see [com.saarthi.core.i18n.Entitlements]),
 * so this is primarily a value-preview + a local unlock that lets you test the
 * Pro state end-to-end. The "Unlock" / "Restore" buttons are the seams the Play
 * `BillingClient` callbacks replace once the in-app product exists.
 */
@Composable
fun PaywallScreen(
    onBack: () -> Unit,
    language: com.saarthi.core.i18n.SupportedLanguage,
    viewModel: PaywallViewModel = hiltViewModel(),
) {
    val isPro by viewModel.isPro.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(SaarthiColors.Bg)) {
        SaarthiTopBar(title = "Saarthi Pro", subtitle = "Founder's Lifetime", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Hero ────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                SaarthiColors.Marigold.copy(alpha = 0.14f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .border(1.dp, SaarthiColors.MarigoldBd.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(20.dp),
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(SaarthiColors.Marigold.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = SaarthiColors.Gold, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Unlock the full assistant",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SaarthiColors.Text,
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Your private, offline assistant — fully unlocked, forever. One payment, no subscription, no account.",
                        style = MaterialTheme.typography.bodyLarge.copy(color = SaarthiColors.Text2, fontSize = 13.sp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "₹199",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = 30.sp, fontWeight = FontWeight.Bold, color = SaarthiColors.Gold,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "one-time · lifetime",
                            modifier = Modifier.padding(bottom = 4.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(color = SaarthiColors.Text3),
                        )
                    }
                }
            }

            // ── What's included ─────────────────────────────────────────────
            Text(
                "WHAT'S INCLUDED",
                style = MaterialTheme.typography.labelSmall.copy(color = SaarthiColors.Text3, letterSpacing = 1.2.sp),
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            ProBenefit("Unlimited document Q&A", "Ask across many PDFs, larger files, kept across restarts")
            ProBenefit("Voice read-aloud", "Full long-reply read-aloud and hands-free voice mode")
            ProBenefit("Richer memory", "More saved facts, plus export / import / on-device backup")
            ProBenefit("Every Pro feature during beta", "New Pro capabilities unlock automatically as they ship")

            // ── Beta note ───────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SaarthiColors.Surface)
                    .border(1.dp, SaarthiColors.Border, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Text(
                    "Beta: every feature is currently unlocked for testers. " +
                        "Google Play purchase arrives in an upcoming update — your founder price is locked.",
                    style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3, fontSize = 12.sp),
                )
            }

            Spacer(Modifier.height(4.dp))

            // ── Action ──────────────────────────────────────────────────────
            if (isPro) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SaarthiColors.Jade.copy(alpha = 0.14f))
                        .border(1.dp, SaarthiColors.Jade.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, null, tint = SaarthiColors.Jade, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(language.proActive, color = SaarthiColors.Text, fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = { viewModel.lock() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove unlock (testing)", color = SaarthiColors.Text3)
                }
            } else {
                Button(
                    onClick = { viewModel.unlock() },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SaarthiColors.Marigold),
                ) {
                    Text(language.unlockBeta, color = SaarthiColors.OnMarigold, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { viewModel.restore() }, modifier = Modifier.fillMaxWidth()) {
                    Text(language.restorePurchase, color = SaarthiColors.Text3)
                }
            }
        }
    }
}

@Composable
private fun ProBenefit(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape).background(SaarthiColors.Marigold.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, null, tint = SaarthiColors.Gold, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold, color = SaarthiColors.Text, fontSize = 14.sp,
                ),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(color = SaarthiColors.Text3, fontSize = 12.sp),
            )
        }
    }
}
