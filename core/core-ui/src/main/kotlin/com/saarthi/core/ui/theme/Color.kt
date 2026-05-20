package com.saarthi.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Marigold-dark palette — warm dark ink with saffron primary.
 * Names mirror the design spec tokens 1:1 (see SAARTHI_HANDOFF.md §2).
 * Legacy "deep space / gold" names are kept as aliases at the bottom
 * for back-compat with screens not yet migrated.
 */
object SaarthiColors {
    // ── Surfaces — warm dark ink ──────────────────────────────────────────
    val Bg        = Color(0xFF0E0B07)  // deepest app background
    val Bg2       = Color(0xFF161310)  // modal / sheet background
    val Bg3       = Color(0xFF1F1A14)  // input fields, pressed surfaces
    val Surface   = Color(0xFF1B1611)  // card background
    val SurfaceHi = Color(0xFF241E16)  // card hover / pressed
    val Border    = Color(0x12F4EEE3)  // ivory @ 7%
    val BorderHi  = Color(0x24F4EEE3)  // ivory @ 14%

    // ── Brand — marigold ──────────────────────────────────────────────────
    val Marigold     = Color(0xFFF4A52E)  // primary CTA
    val MarigoldDim  = Color(0xFFC8841E)  // pressed / hover
    val MarigoldSoft = Color(0x1FF4A52E)  // marigold @ 12% — tinted bg
    val MarigoldGlow = Color(0x38F4A52E)  // marigold @ 22% — drop-shadow glow
    val MarigoldBd   = Color(0x40F4A52E)  // marigold @ 25% — borders

    // ── Semantic accents (single chroma family) ───────────────────────────
    val Terracotta     = Color(0xFFD67152)
    val TerracottaSoft = Color(0x24D67152)  // 14%
    val TerracottaBd   = Color(0x40D67152)

    val Indigo     = Color(0xFF7A8DE8)
    val IndigoSoft = Color(0x247A8DE8)
    val IndigoBd   = Color(0x407A8DE8)

    val Jade     = Color(0xFF5DD3A8)
    val JadeSoft = Color(0x245DD3A8)
    val JadeBd   = Color(0x405DD3A8)

    val Rose     = Color(0xFFE07A8A)
    val RoseSoft = Color(0x24E07A8A)
    val RoseBd   = Color(0x40E07A8A)

    // ── Text — ivory ──────────────────────────────────────────────────────
    val Text  = Color(0xFFF5EEE3)
    val Text2 = Color(0xB3F5EEE3)  // 70%
    val Text3 = Color(0x73F5EEE3)  // 45%
    val Text4 = Color(0x47F5EEE3)  // 28%

    // ── Status ────────────────────────────────────────────────────────────
    val Error   = Rose
    val Success = Jade
    val Warning = Marigold

    // ── Dark ink (for text on marigold buttons) ───────────────────────────
    val OnMarigold = Color(0xFF1A1206)

    // ──────────────────────────────────────────────────────────────────────
    // Legacy aliases — keep old screens compiling while we migrate.
    // ──────────────────────────────────────────────────────────────────────
    val DeepSpace        = Bg
    val NavyDark         = Bg2
    val NavyMid          = Surface
    val NavyLight        = SurfaceHi
    val Gold             = Marigold
    val GoldLight        = Color(0xFFFFC274)
    val GoldDim          = MarigoldDim
    val CyberTeal        = Jade
    val CyberTealDim     = Color(0xFF3DA886)
    val KnowledgePurple  = Indigo
    val MoneyGreen       = Jade
    val KisanEarth       = Terracotta
    val FieldBlue        = Indigo
    val TextPrimary      = Text
    val TextSecondary    = Text2
    val TextMuted        = Text3
    val GlassSurface     = Color(0x0DFFFFFF)
    val GlassBorder      = Border
    val GlassSurfaceHover = BorderHi
    val Saffron          = Marigold
    val TulsiGreen       = Jade
}
