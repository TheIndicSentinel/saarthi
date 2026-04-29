package com.saarthi.core.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.saarthi.core.ui.theme.SaarthiColors

@Composable
fun ShimmerLoading(
    modifier: Modifier = Modifier,
    width: Modifier = Modifier.fillMaxWidth(),
    height: androidx.compose.ui.unit.Dp = 20.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp)
) {
    val shimmerColors = listOf(
        SaarthiColors.NavyLight.copy(alpha = 0.6f),
        SaarthiColors.NavyLight.copy(alpha = 0.2f),
        SaarthiColors.NavyLight.copy(alpha = 0.6f),
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim.value, y = translateAnim.value)
    )

    Box(
        modifier = modifier
            .then(width)
            .height(height)
            .clip(shape)
            .background(brush)
    )
}

@Composable
fun ShimmerMessagePlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ShimmerLoading(width = Modifier.width(120.dp), height = 16.dp)
        ShimmerLoading(width = Modifier.fillMaxWidth(0.9f), height = 24.dp)
        ShimmerLoading(width = Modifier.fillMaxWidth(0.7f), height = 24.dp)
    }
}
