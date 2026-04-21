package com.saarthi.app.navigation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saarthi.core.ui.components.GlassmorphicCard
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.core.ui.theme.saarthiColors

@Composable
fun HomeScreen(
    onNavigate: (Route) -> Unit,
    onChangeModel: () -> Unit = {},
    greeting: String = "नमस्ते 🪔",
    exploreSubtitle: String = "What would you like to explore?",
) {
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaarthiColors.DeepSpace)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    greeting,
                    style = MaterialTheme.typography.headlineMedium,
                    color = SaarthiColors.Gold,
                )
                Text(
                    exploreSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SaarthiColors.TextSecondary,
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Settings",
                        tint = SaarthiColors.TextSecondary,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Change AI Model", color = SaarthiColors.TextPrimary) },
                        onClick = { showMenu = false; onChangeModel() },
                    )
                }
            }
        }

        // Pack items
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PackItem(
                emoji = "💬",
                title = "Saarthi Assistant",
                subtitle = "Your personal AI — ask anything, attach files, voice input",
                accentColor = SaarthiColors.Gold,
                onClick = { onNavigate(Route.Assistant) },
            )
            PackItem(
                emoji = "💰",
                title = "Money Mentor",
                subtitle = "Track spending · Private SMS analysis",
                accentColor = MaterialTheme.saarthiColors.moneyGreen,
                badge = "Coming Soon",
                onClick = { onNavigate(Route.MoneyMentor) },
            )
            PackItem(
                emoji = "🌾",
                title = "Kisan Saathi",
                subtitle = "Farming guidance · Offline crop advisory",
                accentColor = MaterialTheme.saarthiColors.kisanEarth,
                badge = "Coming Soon",
                onClick = { onNavigate(Route.KisanSaathi) },
            )
            PackItem(
                emoji = "📚",
                title = "Knowledge Pack",
                subtitle = "NCERT · Science · General knowledge",
                accentColor = MaterialTheme.saarthiColors.knowledgePurple,
                badge = "Coming Soon",
                onClick = { onNavigate(Route.Knowledge) },
            )
            PackItem(
                emoji = "🔧",
                title = "Field Expert",
                subtitle = "Technical manuals · Error codes · Guides",
                accentColor = MaterialTheme.saarthiColors.fieldBlue,
                badge = "Coming Soon",
                onClick = { onNavigate(Route.FieldExpert) },
            )
        }
    }
}

@Composable
private fun PackItem(
    emoji: String,
    title: String,
    subtitle: String,
    accentColor: androidx.compose.ui.graphics.Color,
    badge: String? = null,
    onClick: () -> Unit,
) {
    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        accentColor = accentColor,
        onClick = onClick,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, style = MaterialTheme.typography.headlineMedium)
                if (badge != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = SaarthiColors.TextMuted,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = SaarthiColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SaarthiColors.TextMuted)
        }
    }
}

@Composable fun MoneyMentorPlaceholder(onBack: () -> Unit) = PackPlaceholder("💰", "Money Mentor", SaarthiColors.MoneyGreen, onBack)
@Composable fun KisanSaathiPlaceholder(onBack: () -> Unit) = PackPlaceholder("🌾", "Kisan Saathi", SaarthiColors.KisanEarth, onBack)
@Composable fun KnowledgePlaceholder(onBack: () -> Unit) = PackPlaceholder("📚", "Knowledge Pack", SaarthiColors.KnowledgePurple, onBack)
@Composable fun FieldExpertPlaceholder(onBack: () -> Unit) = PackPlaceholder("🔧", "Field Expert", SaarthiColors.FieldBlue, onBack)

@Composable
private fun PackPlaceholder(
    emoji: String,
    name: String,
    accent: androidx.compose.ui.graphics.Color,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaarthiColors.DeepSpace)
            .statusBarsPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text(name, style = MaterialTheme.typography.headlineMedium, color = accent)
        Spacer(Modifier.height(8.dp))
        Text(
            "Coming soon — this feature is in development.",
            style = MaterialTheme.typography.bodyLarge,
            color = SaarthiColors.TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
        androidx.compose.material3.TextButton(onClick = onBack) {
            Text("← Back", color = SaarthiColors.Gold)
        }
    }
}
