package com.saarthi.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val SaarthiDarkColorScheme = darkColorScheme(
    primary          = SaarthiColors.Gold,
    onPrimary        = SaarthiColors.DeepSpace,
    primaryContainer = SaarthiColors.GoldDim,
    secondary        = SaarthiColors.CyberTeal,
    onSecondary      = SaarthiColors.DeepSpace,
    background       = SaarthiColors.DeepSpace,
    onBackground     = SaarthiColors.TextPrimary,
    surface          = SaarthiColors.NavyMid,
    onSurface        = SaarthiColors.TextPrimary,
    surfaceVariant   = SaarthiColors.NavyLight,
    onSurfaceVariant = SaarthiColors.TextSecondary,
    error            = SaarthiColors.Error,
    outline          = SaarthiColors.GlassBorder,
)

data class SaarthiExtendedColors(
    val goldAccent: Color,
    val cyberTeal: Color,
    val glassSurface: Color,
    val glassBorder: Color,
    val textMuted: Color,
    val knowledgePurple: Color,
    val moneyGreen: Color,
    val kisanEarth: Color,
    val fieldBlue: Color,
)

val LocalSaarthiColors = staticCompositionLocalOf {
    SaarthiExtendedColors(
        goldAccent      = SaarthiColors.Gold,
        cyberTeal       = SaarthiColors.CyberTeal,
        glassSurface    = SaarthiColors.GlassSurface,
        glassBorder     = SaarthiColors.GlassBorder,
        textMuted       = SaarthiColors.TextMuted,
        knowledgePurple = SaarthiColors.KnowledgePurple,
        moneyGreen      = SaarthiColors.MoneyGreen,
        kisanEarth      = SaarthiColors.KisanEarth,
        fieldBlue       = SaarthiColors.FieldBlue,
    )
}

@Composable
fun SaarthiTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSaarthiColors provides LocalSaarthiColors.current) {
        MaterialTheme(
            colorScheme = SaarthiDarkColorScheme,
            typography  = SaarthiTypography,
            content     = content,
        )
    }
}

// Convenience accessor
val MaterialTheme.saarthiColors: SaarthiExtendedColors
    @Composable get() = LocalSaarthiColors.current
