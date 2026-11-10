package com.rkd.audiobasics.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/**
 * True while Settings > Appearance > "Immersive text" is on (the default). When it is off, the
 * weight changes that react to taps and long presses (play pulse, context-menu open, reorder
 * mode) are skipped, so list rows keep their normal weight and never re-lay-out their text while
 * a finger is down. Provided once by MainActivity.
 */
val LocalImmersiveText = staticCompositionLocalOf { true }

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
    val immersive = LocalImmersiveText.current
    val w by animateIntAsState(
        targetValue = when {
            !immersive -> base
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
    val immersive = LocalImmersiveText.current
    val w by animateIntAsState(
        targetValue = when {
            !immersive || !pulseActive -> base
            pressed -> pressedWeight
            else -> othersWeight
        },
        animationSpec = tween(durationMs, easing = FastOutSlowInEasing),
        label = "pulseWeight",
    )
    return ((w + 5) / 10) * 10
}


/**
 * Tracks where one list row sits on screen so its weight animation can be skipped when the row is
 * far outside the viewport. Not Compose state: it is read once, when the row's target changes, so
 * scrolling never recomposes anything.
 */
@Stable
class WeightWindow {
    internal var coords: LayoutCoordinates? = null

    /** True when the row is on screen or within [bufferRows] row-heights above or below it. */
    fun isNear(bufferRows: Int = 2): Boolean {
        val c = coords ?: return true
        if (!c.isAttached) return false
        val h = c.size.height
        val top = c.positionInWindow().y
        val rootH = c.findRootCoordinates().size.height
        return top + h > -bufferRows * h && top < rootH + bufferRows * h
    }
}

@Composable
fun rememberWeightWindow(): WeightWindow = remember { WeightWindow() }

/** Put on the row's outermost modifier, before any padding, so the height is the whole row. */
fun Modifier.trackWeightWindow(window: WeightWindow): Modifier =
    onGloballyPositioned { window.coords = it }

// Glide time per mode, in the order menu, reorder, pulse. Returning to normal uses the one left.
private val RowDurations = intArrayOf(280, 300, 110)

/**
 * One weight animation for a list row, replacing the separate menu, reorder and pulse ones.
 * Priority: [menuOpen], then [reorderActive], then [pulseActive], then [base]. A row near the
 * viewport (see [WeightWindow]) glides to its target; a row far outside it jumps there.
 */
@Composable
fun animatedRowWeight(
    window: WeightWindow,
    menuOpen: Boolean = false,
    reorderActive: Boolean = false,
    armed: Boolean = false,
    pulseActive: Boolean = false,
    pulsed: Boolean = false,
    base: Int = 700,
    menuWeight: Int = 900,
    armedWeight: Int = 900,
    thinWeight: Int = 300,
    pulsedWeight: Int = 300,
    othersWeight: Int = 900,
): Int {
    val immersive = LocalImmersiveText.current
    val mode = when {
        !immersive -> 3
        menuOpen -> 0
        reorderActive -> 1
        pulseActive -> 2
        else -> 3
    }
    val target = when (mode) {
        0 -> menuWeight
        1 -> if (armed) armedWeight else thinWeight
        2 -> if (pulsed) pulsedWeight else othersWeight
        else -> base
    }
    val anim = remember { Animatable(target.toFloat()) }
    val lastMode = remember { intArrayOf(2) }
    LaunchedEffect(mode, target) {
        if (mode != 3) lastMode[0] = mode
        if (anim.value == target.toFloat()) return@LaunchedEffect
        if (window.isNear()) {
            anim.animateTo(
                target.toFloat(),
                tween(RowDurations[lastMode[0]], easing = FastOutSlowInEasing)
            )
        } else {
            anim.snapTo(target.toFloat())
        }
    }
    return ((anim.value.toInt() + 5) / 10) * 10
}
