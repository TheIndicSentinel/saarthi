package com.saarthi.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import com.saarthi.core.ui.theme.SaarthiColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Saarthi brand mandala — lotus petals + tick ring + center bindu.
 *
 * Static mode: `progress = null` draws the full mark.
 * Progress mode: `progress` in 0..1 fills the 48 outer ticks clockwise
 * from 12 o'clock and pulses the center bindu — used as the download
 * indicator on the onboarding screen.
 */
@Composable
fun SaarthiLogo(
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = SaarthiColors.Marigold,
    dim: Color = Color(0x1AF5EEE3),
    progress: Float? = null,
) {
    val isDownloading = progress != null && progress < 1f
    val infinite = rememberInfiniteTransition(label = "bindu")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = if (isDownloading) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bindu-pulse",
    )

    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val unit = s / 100f
        val center = Offset(cx, cy)

        // Ambient glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0f)),
                center = center,
                radius = 48f * unit,
            ),
            radius = 48f * unit,
            center = center,
        )

        // Outer hairline ring
        drawCircle(
            color = color.copy(alpha = 0.20f),
            radius = 46f * unit,
            center = center,
            style = Stroke(width = 0.4f * unit),
        )

        // 48 tick ring — doubles as progress
        val ticks = 48
        val filled = if (progress != null) (ticks * progress.coerceIn(0f, 1f)).toInt() else ticks
        for (i in 0 until ticks) {
            val a = (i * 360.0 / ticks - 90.0) * PI / 180.0
            val x1 = cx + (cos(a) * 40.5 * unit).toFloat()
            val y1 = cy + (sin(a) * 40.5 * unit).toFloat()
            val x2 = cx + (cos(a) * 45.0 * unit).toFloat()
            val y2 = cy + (sin(a) * 45.0 * unit).toFloat()
            val isOn = i < filled
            drawLine(
                color = if (isOn) color else dim,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = if (isOn) 1.3f * unit else 0.7f * unit,
                cap = StrokeCap.Round,
            )
        }

        // Inner decorative ring
        drawCircle(
            color = color.copy(alpha = 0.22f),
            radius = 37f * unit,
            center = center,
            style = Stroke(width = 0.4f * unit),
        )

        // 16 small accent dots
        for (i in 0 until 16) {
            val a = (i * 360.0 / 16.0 - 90.0 + 11.25) * PI / 180.0
            val x = cx + (cos(a) * 34.0 * unit).toFloat()
            val y = cy + (sin(a) * 34.0 * unit).toFloat()
            drawCircle(
                color = color.copy(alpha = 0.5f),
                radius = 0.55f * unit,
                center = Offset(x, y),
            )
        }

        // 8 lotus petals (r=14 → r=28)
        for (angleDeg in intArrayOf(0, 45, 90, 135, 180, 225, 270, 315)) {
            drawPetal(cx, cy, unit, angleDeg.toFloat(), tipR = 28f, baseR = 14f, width = 5.5f,
                fill = color.copy(alpha = 0.14f), stroke = color.copy(alpha = 0.75f),
                strokeWidth = 0.65f * unit)
        }

        // 8 inner petals (offset 22.5°)
        for (off in floatArrayOf(22.5f, 67.5f, 112.5f, 157.5f, 202.5f, 247.5f, 292.5f, 337.5f)) {
            drawPetal(cx, cy, unit, off, tipR = 20f, baseR = 13.5f, width = 2.2f,
                fill = color.copy(alpha = 0.35f), stroke = Color.Transparent,
                strokeWidth = 0f)
        }

        // Middle solid circle (background for bindu)
        drawCircle(
            color = color.copy(alpha = 0.06f),
            radius = 11f * unit,
            center = center,
        )
        drawCircle(
            color = color.copy(alpha = 0.65f),
            radius = 11f * unit,
            center = center,
            style = Stroke(width = 0.7f * unit),
        )

        // 8-line star inside middle
        for (deg in intArrayOf(0, 45, 90, 135)) {
            val a = deg * PI / 180.0
            val x1 = cx + (cos(a) * 9.5 * unit).toFloat()
            val y1 = cy + (sin(a) * 9.5 * unit).toFloat()
            val x2 = cx - (cos(a) * 9.5 * unit).toFloat()
            val y2 = cy - (sin(a) * 9.5 * unit).toFloat()
            drawLine(
                color = color.copy(alpha = 0.35f),
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = 0.4f * unit,
            )
        }

        // Center bindu — pulses when downloading
        val binduR = if (isDownloading) 3.2f + (pulse * 1f) else 3.2f
        drawCircle(color = color, radius = binduR * unit, center = center)
        if (isDownloading) {
            val ripple = 3.2f + (pulse * 5.8f)
            drawCircle(
                color = color.copy(alpha = (1f - pulse) * 0.7f),
                radius = ripple * unit,
                center = center,
                style = Stroke(width = 0.8f * unit),
            )
        }
    }
}

/** Teardrop petal — base at radius [baseR], tip at radius [tipR], side width [width]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPetal(
    cx: Float,
    cy: Float,
    unit: Float,
    angleDeg: Float,
    tipR: Float,
    baseR: Float,
    width: Float,
    fill: Color,
    stroke: Color,
    strokeWidth: Float,
) {
    val rad = (angleDeg - 90f) * PI.toFloat() / 180f
    val tipX = cx + cos(rad) * tipR * unit
    val tipY = cy + sin(rad) * tipR * unit
    val baseX = cx + cos(rad) * baseR * unit
    val baseY = cy + sin(rad) * baseR * unit
    val perp = rad + (PI.toFloat() / 2f)
    val c1x = baseX + cos(perp) * width * unit
    val c1y = baseY + sin(perp) * width * unit
    val c2x = baseX - cos(perp) * width * unit
    val c2y = baseY - sin(perp) * width * unit
    val path = Path().apply {
        moveTo(baseX, baseY)
        quadraticBezierTo(c1x, c1y, tipX, tipY)
        quadraticBezierTo(c2x, c2y, baseX, baseY)
        close()
    }
    if (fill != Color.Transparent) drawPath(path, color = fill)
    if (stroke != Color.Transparent && strokeWidth > 0f) {
        drawPath(path, color = stroke, style = Stroke(width = strokeWidth))
    }
}
