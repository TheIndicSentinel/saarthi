package com.saarthi.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Plus Jakarta Sans → falls back to SansSerif until font assets are bundled.
// Tiro Devanagari Hindi → Serif renders Devanagari with appropriate weight.
val SaarthiUiFont: FontFamily = FontFamily.SansSerif
val SaarthiDisplayFont: FontFamily = FontFamily.Serif  // Devanagari accent

/**
 * Type scale from SAARTHI_HANDOFF.md §3 — sizes & weights are literal.
 */
val SaarthiTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.75).sp,
        color = SaarthiColors.Text,
    ),
    displayMedium = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
        color = SaarthiColors.Text,
    ),
    headlineLarge = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.5).sp,
        color = SaarthiColors.Text,
    ),
    headlineMedium = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.4).sp,
        color = SaarthiColors.Text,
    ),
    titleLarge = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 20.sp,
        color = SaarthiColors.Text,
    ),
    titleMedium = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.Bold,
        fontSize = 15.5.sp,
        lineHeight = 20.sp,
        color = SaarthiColors.Text,
    ),
    titleSmall = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        color = SaarthiColors.Text,
    ),
    bodyLarge = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        color = SaarthiColors.Text2,
    ),
    bodyMedium = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = SaarthiColors.Text2,
    ),
    bodySmall = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = SaarthiColors.Text3,
    ),
    labelLarge = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        color = SaarthiColors.Text,
    ),
    labelMedium = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
        color = SaarthiColors.Text3,
    ),
    labelSmall = TextStyle(
        fontFamily = SaarthiUiFont,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.3.sp,
        color = SaarthiColors.Text3,
    ),
)

/** Tiro Devanagari Hindi accent — for greetings, blessings, quotes. Never UI labels. */
val DisplayAccent = TextStyle(
    fontFamily = SaarthiDisplayFont,
    fontWeight = FontWeight.Normal,
    fontSize = 18.sp,
    lineHeight = 24.sp,
    letterSpacing = 0.8.sp,
    color = SaarthiColors.Marigold,
)
