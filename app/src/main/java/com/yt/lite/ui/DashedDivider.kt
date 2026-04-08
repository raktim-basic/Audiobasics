package com.yt.lite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp

@Composable
fun DashedDivider(
    modifier: Modifier = Modifier,
    isDarkMode: Boolean,
    scrollProgress: Float
) {
    val grayColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val redColor = Color.Red
    val strokeWidth = 2f
    val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)

    Canvas(modifier = modifier.height(2.dp)) {
        val width = size.width
        val y = size.height / 2

        // Full gray dashed line (static background)
        drawLine(
            color = grayColor,
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = strokeWidth,
            pathEffect = dashPathEffect
        )

        // Red fill – grows from left to right (solid line)
        val fillWidth = width * scrollProgress.coerceIn(0f, 1f)
        if (fillWidth > 0f) {
            drawLine(
                color = redColor,
                start = Offset(0f, y),
                end = Offset(fillWidth, y),
                strokeWidth = strokeWidth,
                pathEffect = null  // solid red fill
            )
        }
    }
}
