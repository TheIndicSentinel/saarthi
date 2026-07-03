package com.saarthi.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saarthi.core.ui.theme.SaarthiColors

/** 5-tone chip palette — matches `Chip` in tokens.jsx. */
enum class ChipTone { Neutral, Marigold, Indigo, Jade, Terracotta, Rose }

private data class ChipPalette(val bg: Color, val fg: Color, val bd: Color)

@Composable
private fun palette(tone: ChipTone): ChipPalette = when (tone) {
    ChipTone.Neutral    -> ChipPalette(Color(0x0FF5EEE3), SaarthiColors.Text2, Color(0x14F5EEE3))
    ChipTone.Marigold   -> ChipPalette(SaarthiColors.MarigoldSoft, SaarthiColors.Marigold, SaarthiColors.MarigoldBd)
    ChipTone.Indigo     -> ChipPalette(SaarthiColors.IndigoSoft, SaarthiColors.Indigo, SaarthiColors.IndigoBd)
    ChipTone.Jade       -> ChipPalette(SaarthiColors.JadeSoft, SaarthiColors.Jade, SaarthiColors.JadeBd)
    ChipTone.Terracotta -> ChipPalette(SaarthiColors.TerracottaSoft, SaarthiColors.Terracotta, SaarthiColors.TerracottaBd)
    ChipTone.Rose       -> ChipPalette(SaarthiColors.RoseSoft, SaarthiColors.Rose, SaarthiColors.RoseBd)
}

@Composable
fun SaarthiChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.Neutral,
    small: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
) {
    val p = palette(tone)
    val hPad = if (small) 8.dp else 10.dp
    val vPad = if (small) 3.dp else 5.dp
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(p.bg)
            .border(1.dp, p.bd, RoundedCornerShape(999.dp))
            .padding(horizontal = hPad, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (leading != null) leading()
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = if (small) 10.5.sp else 11.5.sp,
                color = p.fg,
            ),
            // A chip is a pill-shaped LABEL — its text must never wrap to a
            // second line inside the pill (that balloons the pill and every
            // row containing it). Ellipsize instead on extreme squeeze.
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * Single-line headline that SHRINKS its font to fit the available width
 * instead of wrapping. Page titles and the home greeting are one visual line
 * by design; localized text (Hindi "अपना AI मॉडल चुनें") or a personalised
 * suffix ("शुभ संध्या, अर्जुन") can exceed the width the English text was
 * designed for — especially at larger system font scales — and a wrapped
 * headline breaks the layout (field report). Standard auto-fit pattern:
 * measure, and step the scale down until nothing overflows (floor at
 * [minScale], then ellipsize as the last resort).
 */
@Composable
fun SingleLineAutoFitText(
    text: androidx.compose.ui.text.AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    minScale: Float = 0.62f,
) {
    var scale by androidx.compose.runtime.remember(text) {
        androidx.compose.runtime.mutableFloatStateOf(1f)
    }
    Text(
        text,
        modifier = modifier,
        style = style.copy(fontSize = style.fontSize * scale),
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && scale > minScale) {
                scale = (scale - 0.05f).coerceAtLeast(minScale)
            }
        },
    )
}

/** [SingleLineAutoFitText] for a plain string. */
@Composable
fun SingleLineAutoFitText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    minScale: Float = 0.62f,
) = SingleLineAutoFitText(androidx.compose.ui.text.AnnotatedString(text), style, modifier, minScale)

@Composable
fun SaarthiPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    val bg = if (enabled) SaarthiColors.Marigold else SaarthiColors.MarigoldDim.copy(alpha = 0.4f)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = SaarthiColors.OnMarigold,
                letterSpacing = (-0.15).sp,
            ),
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun SaarthiGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, SaarthiColors.BorderHi, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = SaarthiColors.Text,
            ),
        )
    }
}

@Composable
fun SaarthiToggle(
    on: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track by animateColorAsState(
        targetValue = if (on) SaarthiColors.Marigold else Color(0x1FF5EEE3),
        label = "toggle-track",
    )
    val thumbColor = if (on) SaarthiColors.OnMarigold else SaarthiColors.Text2
    val thumbOffset by animateDpAsState(
        targetValue = if (on) 16.dp else 2.dp,
        label = "toggle-thumb",
    )
    Box(
        modifier = modifier
            .size(width = 36.dp, height = 22.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(track)
            .clickable(onClick = onToggle),
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset, top = 2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}

/** Small rangoli ornament for section breaks. */
@Composable
fun RangoliDivider(
    modifier: Modifier = Modifier,
    color: Color = SaarthiColors.Text3,
    width: Dp = 120.dp,
) {
    Canvas(modifier = modifier.size(width = width, height = 14.dp)) {
        val w = this.size.width
        val h = this.size.height
        val cy = h / 2f
        // left line
        drawLine(color = color.copy(alpha = 0.5f), start = Offset(0f, cy), end = Offset(w * 0.37f, cy), strokeWidth = 0.8f)
        // right line
        drawLine(color = color.copy(alpha = 0.5f), start = Offset(w * 0.63f, cy), end = Offset(w, cy), strokeWidth = 0.8f)
        val cx = w / 2f
        // center ring
        drawCircle(color = color.copy(alpha = 0.5f), radius = 2.5f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(0.8f))
        // center dot
        drawCircle(color = color, radius = 0.8f, center = Offset(cx, cy))
        // diagonal flourishes
        drawLine(color = color, start = Offset(cx - 8, cy), end = Offset(cx - 5, cy - 3), strokeWidth = 0.8f)
        drawLine(color = color, start = Offset(cx - 8, cy), end = Offset(cx - 5, cy + 3), strokeWidth = 0.8f)
        drawLine(color = color, start = Offset(cx + 8, cy), end = Offset(cx + 5, cy - 3), strokeWidth = 0.8f)
        drawLine(color = color, start = Offset(cx + 8, cy), end = Offset(cx + 5, cy + 3), strokeWidth = 0.8f)
        // side dots
        drawCircle(color = color, radius = 1f, center = Offset(cx - 12, cy))
        drawCircle(color = color, radius = 1f, center = Offset(cx + 12, cy))
    }
}

/** Reusable list-row used in Settings + Privacy + Downloads. */
@Composable
fun SaarthiListRow(
    leadingIcon: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tone: ChipTone = ChipTone.Neutral,
    danger: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val p = palette(tone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SaarthiColors.Surface)
            .border(1.dp, SaarthiColors.Border, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(p.bg),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides p.fg,
            ) { leadingIcon() }
        }
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                    color = if (danger) SaarthiColors.Rose else SaarthiColors.Text,
                ),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.5.sp,
                        color = SaarthiColors.Text3,
                    ),
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** Section header label (uppercase, tracked). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            color = SaarthiColors.Text3,
            letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Bold,
        ),
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

