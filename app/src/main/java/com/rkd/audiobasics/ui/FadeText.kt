package com.rkd.audiobasics.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Width of the trailing fade, the same for every title in the app. */
val FadeEdgeWidth: Dp = 24.dp

/**
 * Single-line text that fades out at its trailing edge instead of ending in "...".
 *
 * Why not TextOverflow.Ellipsis: the ellipsis is re-computed every time the text width changes,
 * and with the animated Roboto Flex weight the width changes on every frame, so letters kept
 * popping in and out at the end of long titles. Here the text is simply clipped (softWrap off,
 * TextOverflow.Clip) and the clipped edge is faded to transparent, so the glyphs never change
 * while the weight glides.
 *
 * The fade only shows when the text really is wider than its box, and it ramps in over the first
 * [fadeRampPx] of overflow, so it never pops on at the moment a title starts to overflow.
 * Alignment, colour and font are untouched — pass the same parameters as for [Text].
 */
@Composable
fun FadeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    fadeWidth: Dp = FadeEdgeWidth,
) {
    // How far (px) the text runs past its box. Only read while drawing, so updating it while the
    // weight animates redraws the fade but never recomposes.
    var overflowPx by remember { mutableFloatStateOf(0f) }
    Text(
        text = text,
        modifier = modifier.fadeTrailingEdge(fadeWidth) { overflowPx },
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout ->
            val lineWidth = layout.getLineRight(0) - layout.getLineLeft(0)
            overflowPx = lineWidth - layout.size.width
        },
    )
}

/** Fades the trailing [fadeWidth] of the content to transparent, in proportion to [overflow]. */
private fun Modifier.fadeTrailingEdge(fadeWidth: Dp, overflow: () -> Float): Modifier = this
    .graphicsLayer {
        // Needed so BlendMode.DstIn below only affects this text, not what is drawn behind it.
        compositingStrategy =
            if (overflow() > 0.5f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val over = overflow()
        if (over > 0.5f && size.width > 0f) {
            val fadePx = fadeWidth.toPx().coerceAtMost(size.width)
            val strength = (over / (fadePx * 0.5f)).coerceIn(0f, 1f)
            val solidUntil = 1f - fadePx / size.width
            val end = Color.Black.copy(alpha = 1f - strength)
            val rtl = layoutDirection == LayoutDirection.Rtl
            val brush = if (!rtl) {
                Brush.horizontalGradient(
                    colorStops = arrayOf(0f to Color.Black, solidUntil to Color.Black, 1f to end),
                    startX = 0f,
                    endX = size.width,
                )
            } else {
                Brush.horizontalGradient(
                    colorStops = arrayOf(0f to end, (1f - solidUntil) to Color.Black, 1f to Color.Black),
                    startX = 0f,
                    endX = size.width,
                )
            }
            drawRect(brush = brush, blendMode = BlendMode.DstIn)
        }
    }
