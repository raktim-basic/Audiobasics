package com.rkd.audiobasics.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Single-line text for the fullscreen player's title. A short title is simply centred. A title
 * wider than its box can be dragged sideways with a finger; once it has been left alone for
 * [returnDelayMs] (no finger, no fling) it glides back to the start. Edges fade only on the side
 * that has more text hidden, so the start of the title is never faded.
 */
@Composable
fun ScrollReturnText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    returnDelayMs: Long = 1500L,
    fadeWidth: Dp = 24.dp,
) {
    val scroll = rememberScrollState()

    // A new song starts at the beginning.
    LaunchedEffect(text) { scroll.scrollTo(0) }

    // Every time scrolling stops (finger up and fling finished), wait, then go back to the start.
    // collectLatest cancels the wait as soon as the person touches the title again.
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collectLatest { moving ->
            if (!moving && scroll.value > 0) {
                delay(returnDelayMs)
                scroll.animateScrollTo(0, tween(450, easing = FastOutSlowInEasing))
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .graphicsLayer {
                    // Offscreen so the DstIn fade below only touches this text.
                    compositingStrategy =
                        if (scroll.maxValue > 0) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .drawWithContent {
                    drawContent()
                    val max = scroll.maxValue
                    if (max > 0 && size.width > 0f) {
                        val fadePx = fadeWidth.toPx().coerceAtMost(size.width / 2f)
                        val lead = (scroll.value / fadePx).coerceIn(0f, 1f)
                        val trail = ((max - scroll.value) / fadePx).coerceIn(0f, 1f)
                        val f = fadePx / size.width
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Black.copy(alpha = 1f - lead),
                                    f to Color.Black,
                                    (1f - f) to Color.Black,
                                    1f to Color.Black.copy(alpha = 1f - trail),
                                ),
                                startX = 0f,
                                endX = size.width,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
                .horizontalScroll(scroll)
        )
    }
}
