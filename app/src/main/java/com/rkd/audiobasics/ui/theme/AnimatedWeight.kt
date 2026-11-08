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

/**
 * Smooth typography state for enabled/disabled settings rows.
 * Disabled settings stay thin; enabled settings use a visibly heavier medium weight.
 */
@Composable
fun animatedSettingWeight(
    enabled: Boolean,
    enabledWeight: Int = 500,
    disabledWeight: Int = 200,
    durationMs: Int = 220,
): Int {
    val w by animateIntAsState(
        targetValue = if (enabled) enabledWeight else disabledWeight,
        animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        label = "settingWeight",
    )
    return ((w + 5) / 10) * 10
}

/**
 * Weight for rows that can be reordered. While nothing is being reordered every row keeps its
 * normal [base] weight. Once a row is picked up ([armed]) it glides to a heavy [armedWeight] and
 * every other row ([reorderActive] but not armed) glides to a thin [thinWeight], so the picked
 * row stands out. Same rounding as [animatedBodyWeight].
 */
@Composable
fun animatedReorderWeight(
    armed: Boolean,
    reorderActive: Boolean,
    base: Int = 700,
    armedWeight: Int = 900,
    thinWeight: Int = 300,
    durationMs: Int = 300,
): Int {
    val w by animateIntAsState(
        targetValue = when {
            armed -> armedWeight
            reorderActive -> thinWeight
            else -> base
        },
        animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        label = "reorderWeight",
    )
    return ((w + 5) / 10) * 10
}

/**
 * Weight for the brief "just played" pulse (the reverse of reorder mode): while the pulse is
 * [pulseActive] the pressed row glides to a thin [pressedWeight] and every other row to a heavy
 * [othersWeight]; otherwise [base]. Quicker than the reorder glide on purpose.
 */
@Composable
fun animatedPulseWeight(
    pressed: Boolean,
    pulseActive: Boolean,
    base: Int = 700,
    pressedWeight: Int = 300,
    othersWeight: Int = 900,
    durationMs: Int = 110,
): Int {
    val w by animateIntAsState(
        targetValue = when {
            !pulseActive -> base
            pressed -> pressedWeight
            else -> othersWeight
        },
        animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        label = "pulseWeight",
    )
    return ((w + 5) / 10) * 10
}
