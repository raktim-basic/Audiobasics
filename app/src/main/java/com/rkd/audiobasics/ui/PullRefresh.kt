package com.rkd.audiobasics.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

// One look for every refresh indicator in the app: a round chip with the red accent spinner.

private val RefreshAccent = Color.Red

private fun refreshChipColor(isDarkMode: Boolean): Color =
    if (isDarkMode) Color(0xFF2A2A2A) else Color.White

/**
 * Pull down at the top of a list to refresh. [isRefreshing] is driven by the caller, so the
 * indicator also shows when something else (the launch refresh) is already running.
 * Put the list inside; give the list `Modifier.fillMaxSize()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = refreshChipColor(isDarkMode),
                color = RefreshAccent
            )
        },
        content = content
    )
}

/**
 * The mirror image for bottom-anchored lists (`reverseLayout = true`, like Search): swipe up
 * at the end of the results. The built-in pull-to-refresh only knows the top edge, so this
 * listens for the leftover upward drag the list couldn't use.
 *
 * [bottomInset] keeps the indicator above anything floating over the bottom (the search pill).
 */
@Composable
fun BottomRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 72.dp.toPx() }
    val maxPullPx = thresholdPx * 1.5f
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val refreshingNow by rememberUpdatedState(isRefreshing)

    // Tracked synchronously from the scroll callbacks (no coroutine per drag event), so the
    // value is always current when the finger lifts.
    var pull by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val connection = remember(thresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Dragging back down while pulled lets the pull out first.
                if (source == NestedScrollSource.UserInput && pull > 0f && available.y > 0f) {
                    val take = minOf(pull, available.y)
                    pull -= take
                    return Offset(0f, take)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Whatever upward drag is left over means the list is already at its end.
                if (source == NestedScrollSource.UserInput && available.y < 0f && !refreshingNow) {
                    dragging = true
                    pull = (pull - available.y * 0.5f).coerceAtMost(maxPullPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull <= 0f && !dragging) return Velocity.Zero
                val fire = pull >= thresholdPx && !refreshingNow
                dragging = false
                pull = 0f
                if (fire) currentOnRefresh()
                return available
            }
        }
    }

    val target = when {
        dragging -> pull
        isRefreshing -> thresholdPx
        else -> 0f
    }
    val shown by animateFloatAsState(
        targetValue = target,
        animationSpec = if (dragging) snap() else spring(stiffness = 400f),
        label = "bottomPull"
    )
    val progress = (shown / thresholdPx).coerceIn(0f, 1f)

    Box(modifier = modifier.nestedScroll(connection)) {
        content()
        if (progress > 0.01f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInset + 12.dp)
                    .size(40.dp)
                    .graphicsLayer {
                        alpha = progress
                        translationY = (1f - progress) * 48.dp.toPx()
                    },
                shape = CircleShape,
                color = refreshChipColor(isDarkMode),
                shadowElevation = 6.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = RefreshAccent
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = RefreshAccent
                        )
                    }
                }
            }
        }
    }
}

/**
 * The same chip, for screens that have no list to pull (Home, Queue, ...) while the launch
 * refresh is running. Place it at the top of a Box with `Modifier.align(Alignment.TopCenter)`.
 */
@Composable
fun LibraryRefreshOverlay(
    visible: Boolean,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(top = 10.dp),
        enter = fadeIn() + scaleIn(initialScale = 0.6f),
        exit = fadeOut() + scaleOut(targetScale = 0.6f)
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = refreshChipColor(isDarkMode),
            shadowElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                    color = RefreshAccent
                )
            }
        }
    }
}
