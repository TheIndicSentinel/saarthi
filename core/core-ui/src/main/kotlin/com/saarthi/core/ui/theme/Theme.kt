package com.saarthi.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private fun darkScheme() = darkColorScheme(
    primary          = DarkPalette.marigold,
    onPrimary        = DarkPalette.onMarigold,
    primaryContainer = DarkPalette.marigoldDim,
    onPrimaryContainer = DarkPalette.text,
    secondary        = DarkPalette.terracotta,
    onSecondary      = DarkPalette.onMarigold,
    tertiary         = DarkPalette.indigo,
    onTertiary       = DarkPalette.text,
    background       = DarkPalette.bg,
    onBackground     = DarkPalette.text,
    surface          = DarkPalette.surface,
    onSurface        = DarkPalette.text,
    surfaceVariant   = DarkPalette.surfaceHi,
    onSurfaceVariant = DarkPalette.text2,
    error            = DarkPalette.rose,
    onError          = DarkPalette.text,
    outline          = DarkPalette.border,
    outlineVariant   = DarkPalette.borderHi,
)

private fun lightScheme() = lightColorScheme(
    primary          = LightPalette.marigold,
    onPrimary        = LightPalette.onMarigold,
    primaryContainer = LightPalette.marigoldDim,
    onPrimaryContainer = LightPalette.text,
    secondary        = LightPalette.terracotta,
    onSecondary      = LightPalette.onMarigold,
    tertiary         = LightPalette.indigo,
    onTertiary       = LightPalette.text,
    background       = LightPalette.bg,
    onBackground     = LightPalette.text,
    surface          = LightPalette.surface,
    onSurface        = LightPalette.text,
    surfaceVariant   = LightPalette.surfaceHi,
    onSurfaceVariant = LightPalette.text2,
    error            = LightPalette.rose,
    onError          = LightPalette.onMarigold,
    outline          = LightPalette.border,
    outlineVariant   = LightPalette.borderHi,
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
        marigold     = DarkPalette.marigold,
        marigoldDim  = DarkPalette.marigoldDim,
        marigoldSoft = DarkPalette.marigoldSoft,
        terracotta   = DarkPalette.terracotta,
        indigo       = DarkPalette.indigo,
        jade         = DarkPalette.jade,
        rose         = DarkPalette.rose,
        text         = DarkPalette.text,
        text2        = DarkPalette.text2,
        text3        = DarkPalette.text3,
        text4        = DarkPalette.text4,
        goldAccent      = DarkPalette.marigold,
        cyberTeal       = DarkPalette.jade,
        glassSurface    = DarkPalette.surface,
        glassBorder     = DarkPalette.border,
        textMuted       = DarkPalette.text3,
        knowledgePurple = DarkPalette.indigo,
        moneyGreen      = DarkPalette.jade,
        kisanEarth      = DarkPalette.terracotta,
        fieldBlue       = DarkPalette.indigo,
    )
}

/**
 * Apply the Saarthi theme. The active palette is keyed off [mode]; flipping
 * it re-runs [SaarthiColors.applyPalette] and the snapshot system propagates
 * the new colors through every composable that reads from `SaarthiColors`.
 */
@Composable
fun SaarthiTheme(
    mode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(mode) {
        SaarthiColors.applyPalette(if (mode == ThemeMode.DARK) DarkPalette else LightPalette)
    }
    val colorScheme = if (mode == ThemeMode.DARK) darkScheme() else lightScheme()
    CompositionLocalProvider(LocalSaarthiColors provides LocalSaarthiColors.current) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = SaarthiTypography,
        ) {
            // Surface paints the window with the warm background so navigation
            // transitions never flash a contrasting color.
            Surface(color = if (mode == ThemeMode.DARK) DarkPalette.bg else LightPalette.bg, content = content)
        }
    }
}

val MaterialTheme.saarthiColors: SaarthiExtendedColors
    @Composable get() = LocalSaarthiColors.current
