package com.saarthi.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp

/**
 * Saarthi petal loader — the "Saarthi is thinking" mark.
 *
 * Twelve teardrop petals at 30° intervals around a small spoked hub and a
 * marigold bindu (center dot). Each petal lights up in a clockwise wave;
 * the hub slowly rotates; the bindu pulses in unison.
 *
 * Geometry & motion are exact translations of the FINAL design spec
 * (Saarthi Diya Identity FINAL.html):
 *   • Petals: opacity 0.25 → 1 → 0.25, scale 0.85 → 1.08 → 0.85, 2.4 s
 *     cycle, 200 ms stagger per petal → a wave orbits clockwise.
 *   • Hub: 360° linear rotation every 10 s.
 *   • Bindu: opacity 1 ↔ 0.7, scale 1 ↔ 1.15, 2.4 s ease-in-out.
 *
 * A single shared phase float drives all 12 petals (one infinite transition,
 * no per-petal animation state) — recomposition cost is flat regardless of
 * how many petal sprites are on screen.
 */
@Composable
fun SaarthiPetalLoader(
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFF4A52E),
) {
    val transition = rememberInfiniteTransition(label = "petal-loader")

    // Petal wave — phase ∈ [0, 1), one full orbit per 2.4 s.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
        ),
        label = "petal-phase",
    )

    // Hub rotation — 360° / 10 s linear.
    val hubAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10_000, easing = LinearEasing),
        ),
        label = "hub-spin",
    )

    // Bindu breath — 1 → 0 → 1 ramp interpolated to opacity/scale in draw.
    val binduT by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bindu-pulse",
    )

    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val u = s / 100f                                 // design unit
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        fun r(v: Float) = v * u

        // ── 12 petals ──
        for (i in 0 until 12) {
            // Per-petal phase, wrapped into [0, 1).
            val local = ((phase - i / 12f) + 1f) % 1f
            // Keyframes from the spec: [0%, 20%, 40%, 60%, 100%]
            //   opacity 0.25, 1.00, 1.00, 0.25, 0.25
            //   scale   0.85, 1.08, 1.08, 0.85, 0.85
            val alpha: Float
            val scaleF: Float
            when {
                local < 0.20f -> {
                    val t = local / 0.20f
                    alpha  = lerp(0.25f, 1.00f, t)
                    scaleF = lerp(0.85f, 1.08f, t)
                }
                local < 0.40f -> { alpha = 1.00f; scaleF = 1.08f }
                local < 0.60f -> {
                    val t = (local - 0.40f) / 0.20f
                    alpha  = lerp(1.00f, 0.25f, t)
                    scaleF = lerp(1.08f, 0.85f, t)
                }
                else -> { alpha = 0.25f; scaleF = 0.85f }
            }
            rotate(degrees = i * 30f, pivot = Offset(cx, cy)) {
                drawPetal(
                    cx = cx,
                    cy = cy,
                    unit = u,
                    scaleF = scaleF,
                    color = color.copy(alpha = alpha),
                )
            }
        }

        // ── Hub: thin ring + 4 cross spokes, slow linear spin ──
        val hubStroke = r(1.4f)
        val hubColor  = color.copy(alpha = 0.70f)
        rotate(degrees = hubAngle, pivot = Offset(cx, cy)) {
            drawCircle(
                color = hubColor,
                radius = r(11f),
                center = Offset(cx, cy),
                style = Stroke(width = hubStroke),
            )
            val segOut = r(8f)
            val segIn  = r(4f)
            // Vertical spokes
            drawLine(hubColor, Offset(cx, cy - segOut), Offset(cx, cy - segIn), hubStroke, StrokeCap.Round)
            drawLine(hubColor, Offset(cx, cy + segIn),  Offset(cx, cy + segOut), hubStroke, StrokeCap.Round)
            // Horizontal spokes
            drawLine(hubColor, Offset(cx - segOut, cy), Offset(cx - segIn,  cy), hubStroke, StrokeCap.Round)
            drawLine(hubColor, Offset(cx + segIn,  cy), Offset(cx + segOut, cy), hubStroke, StrokeCap.Round)
        }

        // ── Bindu — opacity 1↔0.7, scale 1↔1.15 ──
        drawCircle(
            color  = color.copy(alpha = lerp(1.00f, 0.70f, binduT)),
            radius = r(3.6f) * lerp(1.00f, 1.15f, binduT),
            center = Offset(cx, cy),
        )
    }
}

/**
 * Draws a single teardrop petal pointing UP from canvas centre, scaled
 * around the petal's own mid-point so the wave's grow/shrink reads as
 * pulsing-in-place rather than radial sliding.
 *
 * Path matches the design (M 50 8 → cubic shoulders → M 50 30 → back).
 * In our local coords (origin = canvas centre), the petal occupies
 * y ∈ [-42u, -20u]; pivot is at the midpoint y = -31u.
 */
private fun DrawScope.drawPetal(
    cx: Float,
    cy: Float,
    unit: Float,
    scaleF: Float,
    color: Color,
) {
    val tipY  = -42f * unit
    val sh1Y  = -28f * unit
    val sh2Y  = -24f * unit
    val baseY = -20f * unit
    val midY  = -31f * unit
    val path = Path().apply {
        moveTo(cx, cy + tipY)
        cubicTo(
            cx - 3f * unit, cy + sh1Y,
            cx - 2f * unit, cy + sh2Y,
            cx,             cy + baseY,
        )
        cubicTo(
            cx + 2f * unit, cy + sh2Y,
            cx + 3f * unit, cy + sh1Y,
            cx,             cy + tipY,
        )
        close()
    }
    if (scaleF != 1f) {
        scale(scaleX = scaleF, scaleY = scaleF, pivot = Offset(cx, cy + midY)) {
            drawPath(path, color)
        }
    } else {
        drawPath(path, color)
    }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
