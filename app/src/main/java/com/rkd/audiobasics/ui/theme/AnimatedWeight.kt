package com.rkd.audiobasics.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/**
 * A font weight that glides between a thin and a thick value when [selected] changes, for tabs and
 * segmented selectors. Use it as:
 *
 *   val w = animatedBodyWeight(isSelected)
 *   Text(..., fontFamily = bodyFontAt(w), fontWeight = FontWeight(w))
 *
 * The value is rounded to a multiple of 10 so the typeface cache in [bodyFontAt] stays small.
 */
@Composable
fun animatedBodyWeight(
    selected: Boolean,
    selectedWeight: Int = 700,
    unselectedWeight: Int = 400,
    durationMs: Int = 300,
): Int {
    val w by animateIntAsState(
        targetValue = if (selected) selectedWeight else unselectedWeight,
        animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        label = "bodyWeight",
    )
    return ((w + 5) / 10) * 10
}
