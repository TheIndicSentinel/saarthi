package com.saarthi.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.saarthi.core.ui.components.GlassmorphicCard
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.core.ui.theme.saarthiColors

@Composable
fun HomeScreen(onNavigate: (Route) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaarthiColors.DeepSpace)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "नमस्ते 🪔",
            style = MaterialTheme.typography.displayLarge,
            color = SaarthiColors.Gold,
        )
        Text(
            "What would you like to explore?",
            style = MaterialTheme.typography.bodyLarge,
            color = SaarthiColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))

        PackItem(
            emoji = "💬",
            title = "Saarthi Assistant",
            subtitle = "Your personal AI — ask anything",
            accentColor = SaarthiColors.Gold,
            onClick = { onNavigate(Route.Assistant) },
        )
        PackItem(
            emoji = "💰",
            title = "Money Mentor",
            subtitle = "Track spending · Private SMS analysis",
            accentColor = MaterialTheme.saarthiColors.moneyGreen,
            onClick = { onNavigate(Route.MoneyMentor) },
        )
        PackItem(
            emoji = "🌾",
            title = "Kisan Saathi",
            subtitle = "Farming guidance · Offline crop advisory",
            accentColor = MaterialTheme.saarthiColors.kisanEarth,
            onClick = { onNavigate(Route.KisanSaathi) },
        )
        PackItem(
            emoji = "📚",
            title = "Knowledge Pack",
            subtitle = "NCERT · Science · General knowledge",
            accentColor = MaterialTheme.saarthiColors.knowledgePurple,
            onClick = { onNavigate(Route.Knowledge) },
        )
        PackItem(
            emoji = "🔧",
            title = "Field Expert",
            subtitle = "Technical manuals · Error codes · Guides",
            accentColor = MaterialTheme.saarthiColors.fieldBlue,
            onClick = { onNavigate(Route.FieldExpert) },
        )
    }
}

@Composable
private fun PackItem(
    emoji: String,
    title: String,
    subtitle: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        accentColor = accentColor,
        onClick = onClick,
    ) {
        Column {
            Text(emoji, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = SaarthiColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SaarthiColors.TextMuted)
        }
    }
}

// Feature pack placeholder screens (each will be fully implemented in their own modules)
@Composable fun MoneyMentorPlaceholder(onBack: () -> Unit) = PackPlaceholder("💰", "Money Mentor", SaarthiColors.MoneyGreen, onBack)
@Composable fun KisanSaathiPlaceholder(onBack: () -> Unit) = PackPlaceholder("🌾", "Kisan Saathi", SaarthiColors.KisanEarth, onBack)
@Composable fun KnowledgePlaceholder(onBack: () -> Unit) = PackPlaceholder("📚", "Knowledge Pack", SaarthiColors.KnowledgePurple, onBack)
@Composable fun FieldExpertPlaceholder(onBack: () -> Unit) = PackPlaceholder("🔧", "Field Expert", SaarthiColors.FieldBlue, onBack)

@Composable
private fun PackPlaceholder(emoji: String, name: String, accent: androidx.compose.ui.graphics.Color, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(SaarthiColors.DeepSpace).padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text(name, style = MaterialTheme.typography.headlineMedium, color = accent)
        Spacer(Modifier.height(8.dp))
        Text("Coming in the next sprint.", style = MaterialTheme.typography.bodyLarge, color = SaarthiColors.TextSecondary)
    }
}
