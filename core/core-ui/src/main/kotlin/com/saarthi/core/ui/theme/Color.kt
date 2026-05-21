package com.saarthi.core.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Snapshot of every Saarthi color token at a single point in time.
 * Two snapshots are defined below — DarkPalette and LightPalette — and the
 * live [SaarthiColors] object swaps between them when the user toggles the
 * theme. Composables that read `SaarthiColors.Marigold` etc. are tracked
 * through Compose's snapshot system, so flipping the palette recomposes the
 * whole UI automatically.
 */
data class SaarthiPalette(
    val bg: Color,
    val bg2: Color,
    val bg3: Color,
    val surface: Color,
    val surfaceHi: Color,
    val border: Color,
    val borderHi: Color,
    val marigold: Color,
    val marigoldDim: Color,
    val marigoldSoft: Color,
    val marigoldGlow: Color,
    val marigoldBd: Color,
    val terracotta: Color,
    val terracottaSoft: Color,
    val terracottaBd: Color,
    val indigo: Color,
    val indigoSoft: Color,
    val indigoBd: Color,
    val jade: Color,
    val jadeSoft: Color,
    val jadeBd: Color,
    val rose: Color,
    val roseSoft: Color,
    val roseBd: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val text4: Color,
    val onMarigold: Color,
    val glassSurface: Color,
)

/** Warm dark ink — the default brand experience. */
val DarkPalette = SaarthiPalette(
    bg            = Color(0xFF0E0B07),
    bg2           = Color(0xFF161310),
    bg3           = Color(0xFF1F1A14),
    surface       = Color(0xFF1B1611),
    surfaceHi     = Color(0xFF241E16),
    border        = Color(0x12F4EEE3),
    borderHi      = Color(0x24F4EEE3),
    marigold      = Color(0xFFF4A52E),
    marigoldDim   = Color(0xFFC8841E),
    marigoldSoft  = Color(0x1FF4A52E),
    marigoldGlow  = Color(0x38F4A52E),
    marigoldBd    = Color(0x40F4A52E),
    terracotta    = Color(0xFFD67152),
    terracottaSoft = Color(0x24D67152),
    terracottaBd  = Color(0x40D67152),
    indigo        = Color(0xFF7A8DE8),
    indigoSoft    = Color(0x247A8DE8),
    indigoBd      = Color(0x407A8DE8),
    jade          = Color(0xFF5DD3A8),
    jadeSoft      = Color(0x245DD3A8),
    jadeBd        = Color(0x405DD3A8),
    rose          = Color(0xFFE07A8A),
    roseSoft      = Color(0x24E07A8A),
    roseBd        = Color(0x40E07A8A),
    text          = Color(0xFFF5EEE3),
    text2         = Color(0xB3F5EEE3),
    text3         = Color(0x73F5EEE3),
    text4         = Color(0x47F5EEE3),
    onMarigold    = Color(0xFF1A1206),
    glassSurface  = Color(0x0DFFFFFF),
)

/** Modern professional light — warm ivory bg, deeper marigold accent, dark ink. */
val LightPalette = SaarthiPalette(
    bg            = Color(0xFFFBF6EC),
    bg2           = Color(0xFFF4ECDD),
    bg3           = Color(0xFFEEE3CE),
    surface       = Color(0xFFFFFFFF),
    surfaceHi     = Color(0xFFFAF3E4),
    border        = Color(0x141F1A0F),
    borderHi      = Color(0x241F1A0F),
    marigold      = Color(0xFFC8841E),
    marigoldDim   = Color(0xFFA56A0E),
    marigoldSoft  = Color(0x1FC8841E),
    marigoldGlow  = Color(0x38C8841E),
    marigoldBd    = Color(0x40C8841E),
    terracotta    = Color(0xFFB05432),
    terracottaSoft = Color(0x1FB05432),
    terracottaBd  = Color(0x40B05432),
    indigo        = Color(0xFF4F61C9),
    indigoSoft    = Color(0x1F4F61C9),
    indigoBd      = Color(0x404F61C9),
    jade          = Color(0xFF1FA77B),
    jadeSoft      = Color(0x1F1FA77B),
    jadeBd        = Color(0x401FA77B),
    rose          = Color(0xFFC9576A),
    roseSoft      = Color(0x1FC9576A),
    roseBd        = Color(0x40C9576A),
    text          = Color(0xFF1F1A0F),
    text2         = Color(0xB31F1A0F),
    text3         = Color(0x801F1A0F),
    text4         = Color(0x551F1A0F),
    onMarigold    = Color(0xFFFFFFFF),
    glassSurface  = Color(0x0D1F1A0F),
)

/**
 * Public color tokens — these are var-backed by Compose state, so reads from
 * any @Composable are tracked. [SaarthiTheme] flips the active palette via
 * [applyPalette] and the entire UI recomposes with the new colors.
 *
 * The legacy aliases (Gold, NavyMid, …) are still here for any code that
 * hasn't migrated to the new names.
 */
object SaarthiColors {
    private var palette by mutableStateOf(DarkPalette)

    internal fun applyPalette(p: SaarthiPalette) { palette = p }

    val Bg        get() = palette.bg
    val Bg2       get() = palette.bg2
    val Bg3       get() = palette.bg3
    val Surface   get() = palette.surface
    val SurfaceHi get() = palette.surfaceHi
    val Border    get() = palette.border
    val BorderHi  get() = palette.borderHi

    val Marigold     get() = palette.marigold
    val MarigoldDim  get() = palette.marigoldDim
    val MarigoldSoft get() = palette.marigoldSoft
    val MarigoldGlow get() = palette.marigoldGlow
    val MarigoldBd   get() = palette.marigoldBd

    val Terracotta     get() = palette.terracotta
    val TerracottaSoft get() = palette.terracottaSoft
    val TerracottaBd   get() = palette.terracottaBd

    val Indigo     get() = palette.indigo
    val IndigoSoft get() = palette.indigoSoft
    val IndigoBd   get() = palette.indigoBd

    val Jade     get() = palette.jade
    val JadeSoft get() = palette.jadeSoft
    val JadeBd   get() = palette.jadeBd

    val Rose     get() = palette.rose
    val RoseSoft get() = palette.roseSoft
    val RoseBd   get() = palette.roseBd

    val Text  get() = palette.text
    val Text2 get() = palette.text2
    val Text3 get() = palette.text3
    val Text4 get() = palette.text4

    val Error   get() = palette.rose
    val Success get() = palette.jade
    val Warning get() = palette.marigold

    val OnMarigold get() = palette.onMarigold

    // ── Legacy aliases ──────────────────────────────────────────────────
    val DeepSpace        get() = palette.bg
    val NavyDark         get() = palette.bg2
    val NavyMid          get() = palette.surface
    val NavyLight        get() = palette.surfaceHi
    val Gold             get() = palette.marigold
    val GoldLight        get() = palette.marigold
    val GoldDim          get() = palette.marigoldDim
    val CyberTeal        get() = palette.jade
    val CyberTealDim     get() = palette.jade
    val KnowledgePurple  get() = palette.indigo
    val MoneyGreen       get() = palette.jade
    val KisanEarth       get() = palette.terracotta
    val FieldBlue        get() = palette.indigo
    val TextPrimary      get() = palette.text
    val TextSecondary    get() = palette.text2
    val TextMuted        get() = palette.text3
    val GlassSurface     get() = palette.glassSurface
    val GlassBorder      get() = palette.border
    val GlassSurfaceHover get() = palette.borderHi
    val Saffron          get() = palette.marigold
    val TulsiGreen       get() = palette.jade
}
