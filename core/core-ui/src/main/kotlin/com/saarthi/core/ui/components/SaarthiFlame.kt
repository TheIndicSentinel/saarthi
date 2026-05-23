package com.saarthi.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp

/**
 * Saarthi flame — the brand mark at rest.
 *
 * Renders the "Flame & Petals" identity (Saarthi Diya Identity FINAL). The
 * stroke is the lit flame only — no bowl, no base. Five tonal layers stack
 * from a soft ambient halo at the edge to a white-hot core just above the
 * wick, the same way a real diya looks when you focus on the light and the
 * clay disappears.
 *
 * Used in:
 *   • Chat avatar for assistant messages (when NOT streaming) — see
 *     [SaarthiPetalLoader] for the streaming counterpart.
 *   • Any in-app surface that wants the resting brand mark.
 *
 * NOT used for:
 *   • Download progress on the onboarding screen — that still uses the
 *     mandala (`SaarthiLogo`) because its concentric tick ring is what
 *     animates the progress fill.
 *
 * The launcher icon and splash use the equivalent XML at
 * `res/drawable/ic_saarthi_logo.xml`; the two are kept visually identical
 * but live in separate files because the launcher is an adaptive-icon
 * vector drawable while this one is a Compose canvas.
 */
@Composable
fun SaarthiFlame(
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        // The design viewport is 100 × 100. Scale all coordinates by `u`
        // so the flame fits whatever `size` the caller passed without
        // distortion. Centered horizontally + vertically.
        val u = s / 100f
        fun x(v: Float) = (this.size.width - s) / 2f + v * u
        fun y(v: Float) = (this.size.height - s) / 2f + v * u
        fun r(v: Float) = v * u

        // ── 1 · Ambient halo (wide soft warm glow) ──
        drawOval(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to Color(0x8CFFB94B),
                    0.4f to Color(0x3FF4A52E),
                    1.0f to Color(0x00F4A52E),
                ),
                center = Offset(x(50f), y(60f)),
                radius = r(48f),
            ),
            topLeft = Offset(x(2f), y(20f)),
            size = Size(r(96f), r(80f)),
        )

        // ── 2 · Outer halo (warmer ring around the flame) ──
        drawOval(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to Color(0xBFFFD580),
                    0.6f to Color(0x52FF8A3C),
                    1.0f to Color(0x00FF8A3C),
                ),
                center = Offset(x(50f), y(65f)),
                radius = r(36f),
            ),
            topLeft = Offset(x(18f), y(29f)),
            size = Size(r(64f), r(72f)),
        )

        // ── 3 · Outer flame body (ember edge → deep amber base) ──
        val outer = flamePath(x = ::x, y = ::y, top = 6f, side = 24f, bottomY = 92f, waistY = 60f)
        drawPath(
            path = outer,
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f  to Color(0xFFFF8A3C),
                    0.55f to Color(0xFFF4A52E),
                    1.0f  to Color(0xFFB8580C),
                ),
                startY = y(6f),
                endY = y(92f),
            ),
        )

        // ── 4 · Mid flame body (marigold heart) ──
        val mid = flamePath(x = ::x, y = ::y, top = 18f, side = 32f, bottomY = 86f, waistY = 60f)
        drawPath(
            path = mid,
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color(0xFFFFD580),
                    0.6f to Color(0xFFF4A52E),
                    1.0f to Color(0xFFE27349),
                ),
                startY = y(18f),
                endY = y(86f),
            ),
        )

        // ── 5 · Inner hot zone (cream radial) ──
        val inner = flamePath(x = ::x, y = ::y, top = 32f, side = 38f, bottomY = 84f, waistY = 64f)
        drawPath(
            path = inner,
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f  to Color(0xFFFFF8E1),
                    0.35f to Color(0xFFFFEDC4),
                    0.8f  to Color(0x99FFD580),
                    1.0f  to Color(0x00FFD580),
                ),
                center = Offset(x(50f), y(65f)),
                radius = r(28f),
            ),
        )

        // ── 6a · White-hot core (small radial ellipse near the wick) ──
        drawOval(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to Color(0xFFFFFFFF),
                    0.5f to Color(0xFFFFF8E1),
                    1.0f to Color(0x00FFEDC4),
                ),
                center = Offset(x(50f), y(76f)),
                radius = r(11f),
            ),
            topLeft = Offset(x(43f), y(63f)),
            size = Size(r(14f), r(22f)),
        )

        // ── 6b · Pinpoint (the white spark) ──
        drawCircle(
            color = Color(0xD9FFFFFF),
            radius = r(2.4f),
            center = Offset(x(50f), y(78f)),
        )
    }
}

/**
 * Builds one flame-body teardrop path matching the design's cubic curve:
 *   M [top]
 *   C ([-side], [waistY]), ([+side], [waistY]), ([bottomY])
 * Drawn as two symmetric Beziers from the wick tip down to the rounded base.
 */
private fun flamePath(
    x: (Float) -> Float,
    y: (Float) -> Float,
    top: Float,
    side: Float,
    bottomY: Float,
    waistY: Float,
): Path = Path().apply {
    val cx = 50f
    val leftX = side
    val rightX = 100f - side
    // Bezier control geometry mirrored across the vertical axis.
    val ctrl1Yl = top + (waistY - top) * 0.40f      // shoulder
    val ctrl2Yl = top + (waistY - top) * 1.00f      // waist
    val ctrl1Yb = waistY + (bottomY - waistY) * 0.5f
    val ctrl2Yb = bottomY

    moveTo(x(cx), y(top))
    // Left side: top → waist (shoulder curve)
    cubicTo(
        x(cx - (cx - leftX) * 0.50f), y(ctrl1Yl),
        x(leftX), y(ctrl2Yl - (waistY - top) * 0.30f),
        x(leftX), y(waistY),
    )
    // Left side: waist → bottom (round base)
    cubicTo(
        x(leftX), y(ctrl1Yb),
        x(cx - (cx - leftX) * 0.50f), y(ctrl2Yb),
        x(cx), y(bottomY),
    )
    // Right side: bottom → waist (round base, mirrored)
    cubicTo(
        x(cx + (rightX - cx) * 0.50f), y(ctrl2Yb),
        x(rightX), y(ctrl1Yb),
        x(rightX), y(waistY),
    )
    // Right side: waist → top (shoulder curve, mirrored)
    cubicTo(
        x(rightX), y(ctrl2Yl - (waistY - top) * 0.30f),
        x(cx + (rightX - cx) * 0.50f), y(ctrl1Yl),
        x(cx), y(top),
    )
    close()
}
