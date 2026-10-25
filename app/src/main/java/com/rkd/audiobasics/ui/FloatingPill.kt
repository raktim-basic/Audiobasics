package com.rkd.audiobasics.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Floating navigation pill
//
// Every screen used to carry its own full-width bar docked above the mini player. They are now
// small rounded pills that FLOAT over the screen's content (the list runs down to the mini
// player and scrolls underneath), hug their buttons, resize with state, and slide away while the
// list is scrolled down.
//
// Per screen: wrap the screen in [FloatingBarHost], pass the pill as `bar` and the screen's list
// state as `listState`. Lists need no extra bottom padding: the pill hides as the list scrolls
// down, so the last row is always fully visible at the end. Screens that don't scroll (Home)
// reserve [FloatingBarClearance] under their content instead.
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** Space a NON-scrolling screen (Home) keeps free at its bottom so content sits above the pill. */
val FloatingBarClearance: Dp = 84.dp

/** Gap between the pill and the docked mini player underneath it. */
private val PillGapAboveMiniPlayer: Dp = 12.dp

private val PillColorDark = Color(0xFF1E1E1E)
private val PillColorLight = Color(0xFFE8E8E8)

private fun pillColor(isDarkMode: Boolean): Color = if (isDarkMode) PillColorDark else PillColorLight

/**
 * Pill surface with the same lift as the 3-dots menu (see MorphOverlay):
 *  - light theme: a standard 8dp drop shadow;
 *  - dark theme: no drop shadow (invisible on dark) but a soft ambient light halo, built from a
 *    few expanding, increasingly transparent rounded-rect layers drawn behind the surface.
 * [cornerRadius] null = fully rounded (a true pill).
 */
fun Modifier.pillSurface(color: Color, cornerRadius: Dp? = null): Modifier {
    val shape = if (cornerRadius == null) RoundedCornerShape(percent = 50) else RoundedCornerShape(cornerRadius)
    val isDark = color.luminance() < 0.5f
    val lifted = if (isDark) {
        this.drawBehind {
            val glowLayers = 5
            val maxSpread = 10.dp.toPx()
            val peakAlpha = 0.03f
            val radius = cornerRadius?.toPx() ?: (size.height / 2f)
            for (i in glowLayers downTo 1) {
                val t = i / glowLayers.toFloat()
                val spread = maxSpread * t
                val layerAlpha = peakAlpha * (1f - t)
                if (layerAlpha <= 0f) continue
                drawRoundRect(
                    color = Color(0xFFE8E8E8),
                    topLeft = Offset(-spread, -spread),
                    size = Size(size.width + spread * 2f, size.height + spread * 2f),
                    cornerRadius = CornerRadius(radius + spread),
                    alpha = layerAlpha
                )
            }
        }
    } else {
        this.shadow(elevation = 8.dp, shape = shape, clip = false)
    }
    return lifted.clip(shape).background(color)
}

/** Inset rounded bar for the one screen (Search) that still sits in its own row. */
fun Modifier.floatingPill(color: Color): Modifier =
    this.padding(horizontal = 16.dp, vertical = 8.dp).pillSurface(color)

// ── Hide on scroll ───────────────────────────────────────────────────────────────────────────

@Stable
class FloatingBarState {
    var hidden by mutableStateOf(false)

    /** Standard Android behaviour: hide while scrolling down, come back on any scroll up. */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y < -SCROLL_SLOP) hidden = true
            else if (available.y > SCROLL_SLOP) hidden = false
            return Offset.Zero
        }
    }

    private companion object {
        const val SCROLL_SLOP = 2f
    }
}

/**
 * Lays [content] out full-size and floats [bar] over its bottom edge (just above the mini
 * player, which lives outside this host). The bar slides down and fades while the content is
 * scrolled down and returns on scroll up; [lockVisible] keeps it on screen (e.g. while the
 * search field is open).
 *
 * Passing the screen's [listState] makes hiding smart: the bar may hide when the list can
 * scroll, or when the list is too short to scroll but its last row sits underneath the bar
 * (a swipe then tucks the bar away so the row can be reached). If the list is short and nothing
 * is under the bar, the bar simply stays — and comes back on its own if it was hidden when the
 * list was longer (e.g. after switching tabs). Without a [listState] the bar never hides.
 */
@Composable
fun FloatingBarHost(
    modifier: Modifier = Modifier,
    lockVisible: Boolean = false,
    barAlignment: Alignment = Alignment.BottomCenter,
    listState: LazyListState? = null,
    bar: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val state = remember { FloatingBarState() }
    var barHeightPx by remember { mutableStateOf(0) }

    val hideAllowed by remember(listState) {
        derivedStateOf {
            val ls = listState
            when {
                ls == null -> false
                ls.canScrollForward || ls.canScrollBackward -> true
                else -> {
                    // Everything fits on screen: only worth hiding if the bar covers some of it.
                    val info = ls.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    val contentBottom = if (last == null) 0 else last.offset + last.size
                    contentBottom > info.viewportEndOffset - barHeightPx
                }
            }
        }
    }

    LaunchedEffect(lockVisible) {
        if (lockVisible) state.hidden = false
    }
    val hideFraction by animateFloatAsState(
        targetValue = if (state.hidden && hideAllowed && !lockVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "floatingBarHide"
    )
    Box(modifier = modifier.fillMaxSize().nestedScroll(state.connection)) {
        content()
        Box(
            modifier = Modifier
                .align(barAlignment)
                .onSizeChanged { barHeightPx = it.height }
                .graphicsLayer {
                    translationY = hideFraction * (size.height + 32.dp.toPx())
                    alpha = 1f - hideFraction
                }
        ) {
            bar()
        }
    }
}

// ── Building blocks ──────────────────────────────────────────────────────────────────────────

@Composable
private fun PillIconButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    hapticsEnabled: Boolean,
    context: Context,
    onClick: () -> Unit
) {
    IconButton(onClick = {
        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
        onClick()
    }) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** A compact pill that hugs its content and animates when that content changes size. */
@Composable
private fun PillRow(
    isDarkMode: Boolean,
    spacing: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .padding(bottom = PillGapAboveMiniPlayer)
            .pillSurface(pillColor(isDarkMode))
            .animateContentSize(animationSpec = tween(220, easing = FastOutSlowInEasing))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

// ── Pill variants ────────────────────────────────────────────────────────────────────────────

/** Home: settings, search, queue. */
@Composable
fun HomeNavPill(
    isDarkMode: Boolean,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onQueue: () -> Unit,
    hapticsEnabled: Boolean,
    context: Context
) {
    val iconColor = if (isDarkMode) Color.White else Color.Black
    PillRow(isDarkMode, spacing = 12.dp) {
        PillIconButton(Icons.Default.Settings, "Settings", iconColor, hapticsEnabled, context, onSettings)
        PillIconButton(Icons.Default.Search, "Search", iconColor, hapticsEnabled, context, onSearch)
        PillIconButton(Icons.Default.QueueMusic, "Queue", iconColor, hapticsEnabled, context, onQueue)
    }
}

/** Two buttons: back and queue. */
@Composable
fun BackQueuePill(
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onQueue: () -> Unit,
    hapticsEnabled: Boolean,
    context: Context
) {
    val iconColor = if (isDarkMode) Color.White else Color.Black
    PillRow(isDarkMode, spacing = 20.dp) {
        PillIconButton(Icons.Default.ArrowBack, "Back", iconColor, hapticsEnabled, context, onBack)
        PillIconButton(Icons.Default.QueueMusic, "Queue", iconColor, hapticsEnabled, context, onQueue)
    }
}

/** One button: back, as a round button on the left. */
@Composable
fun BackOnlyPill(
    isDarkMode: Boolean,
    onBack: () -> Unit,
    hapticsEnabled: Boolean,
    context: Context
) {
    val iconColor = if (isDarkMode) Color.White else Color.Black
    Box(
        modifier = Modifier
            .padding(start = 16.dp, bottom = PillGapAboveMiniPlayer)
            .pillSurface(pillColor(isDarkMode))
            .size(56.dp),
        contentAlignment = Alignment.Center
    ) {
        PillIconButton(Icons.Default.ArrowBack, "Back", iconColor, hapticsEnabled, context, onBack)
    }
}

/**
 * Queue screen: back, sleep timer (just the moon; while a timer runs it turns red and shows the
 * remaining time beside it, and the pill grows to fit) and repeat mode.
 */
@Composable
fun QueueNavPill(
    isDarkMode: Boolean,
    timerText: String?,
    repeatMode: Int,
    onBack: () -> Unit,
    onSleepTimer: () -> Unit,
    onToggleRepeat: () -> Unit,
    hapticsEnabled: Boolean,
    context: Context
) {
    val iconColor = if (isDarkMode) Color.White else Color.Black
    PillRow(isDarkMode, spacing = 8.dp) {
        PillIconButton(Icons.Default.ArrowBack, "Back", iconColor, hapticsEnabled, context, onBack)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .clickable {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    onSleepTimer()
                }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = "Sleep timer",
                tint = if (timerText != null) Color.Red else iconColor,
                modifier = Modifier.size(24.dp)
            )
            if (timerText != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = timerText,
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.Red,
                    maxLines = 1
                )
            }
        }
        PillIconButton(
            icon = if (repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
            description = "Repeat Mode",
            tint = if (repeatMode == 0) iconColor else Color.Red,
            hapticsEnabled = hapticsEnabled,
            context = context,
            onClick = onToggleRepeat
        )
    }
}

/**
 * Back, search (with the screen's label, e.g. "(LIKED)") and queue. Tapping search morphs the
 * pill wider into a search field with a clear/close button; closing morphs it back.
 */
@Composable
fun ContextNavPill(
    isDarkMode: Boolean,
    contextLabel: String,
    isSearching: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchPlaceholder: String,
    focusRequester: FocusRequester,
    onBack: () -> Unit,
    onStartSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    onQueue: () -> Unit,
    hapticsEnabled: Boolean,
    context: Context
) {
    val textColor = if (isDarkMode) Color.White else Color.Black
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = PillGapAboveMiniPlayer)
            .pillSurface(pillColor(isDarkMode))
    ) {
        AnimatedContent(
            targetState = isSearching,
            transitionSpec = {
                ContentTransform(
                    targetContentEnter = fadeIn(tween(160, delayMillis = 90)),
                    initialContentExit = fadeOut(tween(90)),
                    sizeTransform = SizeTransform(clip = true) { _, _ ->
                        tween(durationMillis = 280, easing = FastOutSlowInEasing)
                    }
                )
            },
            contentAlignment = Alignment.Center,
            label = "pillSearchMorph"
        ) { searching ->
            if (searching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 22.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = NothingFont,
                            color = textColor,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(Color.Red),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = searchPlaceholder,
                                        fontFamily = NothingFont,
                                        color = Color.Gray,
                                        fontSize = 15.sp,
                                        maxLines = 1
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    PillIconButton(
                        Icons.Default.Close, "Cancel search", textColor,
                        hapticsEnabled, context, onCancelSearch
                    )
                }
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PillIconButton(Icons.Default.ArrowBack, "Back", textColor, hapticsEnabled, context, onBack)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .clickable {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                onStartSearch()
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = textColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = contextLabel,
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = textColor,
                            maxLines = 1
                        )
                    }
                    PillIconButton(Icons.Default.QueueMusic, "Queue", textColor, hapticsEnabled, context, onQueue)
                }
            }
        }
    }
}
