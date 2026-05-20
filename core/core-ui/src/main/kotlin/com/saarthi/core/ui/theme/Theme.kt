package com.saarthi.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val SaarthiDarkColorScheme = darkColorScheme(
    primary          = SaarthiColors.Marigold,
    onPrimary        = SaarthiColors.OnMarigold,
    primaryContainer = SaarthiColors.MarigoldDim,
    onPrimaryContainer = SaarthiColors.Text,
    secondary        = SaarthiColors.Terracotta,
    onSecondary      = SaarthiColors.OnMarigold,
    tertiary         = SaarthiColors.Indigo,
    onTertiary       = SaarthiColors.Text,
    background       = SaarthiColors.Bg,
    onBackground     = SaarthiColors.Text,
    surface          = SaarthiColors.Surface,
    onSurface        = SaarthiColors.Text,
    surfaceVariant   = SaarthiColors.SurfaceHi,
    onSurfaceVariant = SaarthiColors.Text2,
    error            = SaarthiColors.Rose,
    onError          = SaarthiColors.Text,
    outline          = SaarthiColors.Border,
    outlineVariant   = SaarthiColors.BorderHi,
)

data class SaarthiExtendedColors(
    val marigold: Color,
    val marigoldDim: Color,
    val marigoldSoft: Color,
    val terracotta: Color,
    val indigo: Color,
    val jade: Color,
    val rose: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val text4: Color,
    // Legacy back-compat fields
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
        marigold     = SaarthiColors.Marigold,
        marigoldDim  = SaarthiColors.MarigoldDim,
        marigoldSoft = SaarthiColors.MarigoldSoft,
        terracotta   = SaarthiColors.Terracotta,
        indigo       = SaarthiColors.Indigo,
        jade         = SaarthiColors.Jade,
        rose         = SaarthiColors.Rose,
        text         = SaarthiColors.Text,
        text2        = SaarthiColors.Text2,
        text3        = SaarthiColors.Text3,
        text4        = SaarthiColors.Text4,
        // Legacy
        goldAccent      = SaarthiColors.Marigold,
        cyberTeal       = SaarthiColors.Jade,
        glassSurface    = SaarthiColors.Surface,
        glassBorder     = SaarthiColors.Border,
        textMuted       = SaarthiColors.Text3,
        knowledgePurple = SaarthiColors.Indigo,
        moneyGreen      = SaarthiColors.Jade,
        kisanEarth      = SaarthiColors.Terracotta,
        fieldBlue       = SaarthiColors.Indigo,
    )
}

@Composable
fun SaarthiTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSaarthiColors provides LocalSaarthiColors.current) {
        MaterialTheme(
            colorScheme = SaarthiDarkColorScheme,
            typography  = SaarthiTypography,
        ) {
            // Surface paints the window with the warm dark ink so navigation
            // transitions never flash a lighter background.
            Surface(color = SaarthiColors.Bg, content = content)
        }
    }
}

// Convenience accessor
val MaterialTheme.saarthiColors: SaarthiExtendedColors
    @Composable get() = LocalSaarthiColors.current
