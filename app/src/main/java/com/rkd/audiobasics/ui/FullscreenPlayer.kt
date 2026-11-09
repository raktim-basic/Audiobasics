package com.rkd.audiobasics.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rkd.audiobasics.cache.CacheManager
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.AudiobasicsLinks
import com.rkd.audiobasics.utils.HapticUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Fullscreen player (Settings > Appearance > Player design > Fullscreen)
//
// The sheet below is ViMusic's BottomSheet (ui/components/BottomSheet.kt, GPL-3.0 like this
// project): a full-screen box that is offset down so only its top `collapsedBound` shows, with the
// player bar drawn as the "collapsed" content. Dragging it moves the whole sheet under the
// finger, a fling or a release past the halfway point settles it (spring), and a tap on the bar
// or the back button animates it with a 300ms tween. The expanded content only exists while the
// sheet is not fully collapsed, and the collapsed bar fades out within the first 1/16th of the
// travel, exactly like ViMusic.
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** Height of the mini player bar when it is the collapsed content of the sheet. */
val PlayerBarHeight = 64.dp

private const val SheetExpandedAnchor = 2
private const val SheetCollapsedAnchor = 1

@Stable
class PlayerSheetState internal constructor(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    private val onAnchorChanged: (Int) -> Unit,
    val collapsedBound: Dp,
) : DraggableState by draggableState {
    val expandedBound: Dp
        get() = animatable.upperBound!!

    val value by animatable.asState()

    val isCollapsed by derivedStateOf { value == collapsedBound }

    val isExpanded by derivedStateOf { value == animatable.upperBound }

    val progress by derivedStateOf {
        1f - (animatable.upperBound!! - animatable.value) / (animatable.upperBound!! - collapsedBound)
    }

    fun collapse(animationSpec: AnimationSpec<Dp>) {
        onAnchorChanged(SheetCollapsedAnchor)
        coroutineScope.launch { animatable.animateTo(collapsedBound, animationSpec) }
    }

    fun expand(animationSpec: AnimationSpec<Dp>) {
        onAnchorChanged(SheetExpandedAnchor)
        coroutineScope.launch { animatable.animateTo(animatable.upperBound!!, animationSpec) }
    }

    private fun collapse() = collapse(SpringSpec())

    private fun expand() = expand(SpringSpec())

    fun collapseSoft() = collapse(tween(300))

    fun expandSoft() = expand(tween(300))

    fun snapTo(value: Dp) {
        coroutineScope.launch { animatable.snapTo(value) }
    }

    /**
     * ViMusic's preUpPostDownNestedScrollConnection: lets a scrolling child (the lyrics) hand a
     * downward drag at its very top over to the sheet, so swiping down from the top of the
     * lyrics closes the player, and drags the sheet back up if the finger reverses.
     */
    val preUpPostDownNestedScrollConnection: NestedScrollConnection
        get() = object : NestedScrollConnection {
            var isTopReached = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isExpanded && available.y < 0) isTopReached = false
                return if (isTopReached && available.y < 0 && source == NestedScrollSource.UserInput) {
                    dispatchRawDelta(available.y)
                    available
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!isTopReached) isTopReached = consumed.y == 0f && available.y > 0
                return if (isTopReached && source == NestedScrollSource.UserInput) {
                    dispatchRawDelta(available.y)
                    available
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return if (isTopReached) {
                    performFling(-available.y)
                    available
                } else {
                    Velocity.Zero
                }
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                isTopReached = false
                return Velocity.Zero
            }
        }

    /** [velocity] is positive when the finger moved up (towards expanding). */
    fun performFling(velocity: Float) {
        if (velocity > 250) {
            expand()
        } else if (velocity < -250) {
            collapse()
        } else {
            // Same halfway rule as ViMusic: past (expanded - collapsed) / 2 opens, else closes.
            if (value > (expandedBound - collapsedBound) / 2) expand() else collapse()
        }
    }
}

@Composable
fun rememberPlayerSheetState(collapsedBound: Dp, expandedBound: Dp): PlayerSheetState {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var previousAnchor by rememberSaveable { mutableStateOf(SheetCollapsedAnchor) }

    return remember(expandedBound, collapsedBound, coroutineScope) {
        val initialValue = if (previousAnchor == SheetExpandedAnchor) expandedBound else collapsedBound
        val animatable = Animatable(initialValue, Dp.VectorConverter).also {
            it.updateBounds(collapsedBound.coerceAtMost(expandedBound), expandedBound)
        }
        PlayerSheetState(
            draggableState = DraggableState { delta ->
                coroutineScope.launch {
                    animatable.snapTo(animatable.value - with(density) { delta.toDp() })
                }
            },
            onAnchorChanged = { previousAnchor = it },
            coroutineScope = coroutineScope,
            animatable = animatable,
            collapsedBound = collapsedBound
        )
    }
}

/**
 * The fullscreen player: the player bar at the bottom of the screen that slides up into the
 * full player (tap it, or drag it up). Swipe down or press back to close.
 *
 * Place it as a sibling above the app's content, filling the whole window. The app content must
 * leave [PlayerBarHeight] free at the bottom (a plain spacer) for the bar to sit in.
 *
 * [collapseSignal] closes the sheet whenever it changes (pass the current navigation key, so
 * navigating to an artist, an album or the queue from the player reveals that screen).
 */
@Composable
fun FullscreenPlayerSheet(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    collapseSignal: Any?,
    onAddTo: (Song) -> Unit,
    onNavigateQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSong by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val hasSong = currentSong != null

    val density = LocalDensity.current
    val navBars = WindowInsets.navigationBars
    val ime = WindowInsets.ime

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val navBottom = with(density) { navBars.getBottom(density).toDp() }
        val state = rememberPlayerSheetState(
            collapsedBound = PlayerBarHeight + navBottom,
            expandedBound = maxHeight
        )

        // Navigating somewhere (artist, album, queue...) from the player closes it.
        LaunchedEffect(collapseSignal) {
            if (!state.isCollapsed) state.collapseSoft()
        }
        // The song went away (queue cleared): nothing to show, so close without animating.
        LaunchedEffect(hasSong) {
            if (!hasSong && !state.isCollapsed) state.snapTo(state.collapsedBound)
        }

        Box(
            modifier = Modifier
                .offset {
                    val y = (state.expandedBound - state.value).roundToPx().coerceAtLeast(0)
                    // While collapsed, the bar rides above the keyboard like it always did (the
                    // rest of the app shrinks with imePadding); the lift fades out as it opens.
                    val lift = ((ime.getBottom(this) - navBars.getBottom(this)).coerceAtLeast(0) *
                        (1f - state.progress.coerceIn(0f, 1f))).roundToInt()
                    IntOffset(x = 0, y = y - lift)
                }
                .pointerInput(state, hasSong) {
                    if (!hasSong) return@pointerInput
                    val velocityTracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            velocityTracker.addPointerInputChange(change)
                            state.dispatchRawDelta(dragAmount)
                        },
                        onDragCancel = {
                            velocityTracker.resetTracking()
                            state.performFling(0f)
                        },
                        onDragEnd = {
                            val velocity = -velocityTracker.calculateVelocity().y
                            velocityTracker.resetTracking()
                            state.performFling(velocity)
                        }
                    )
                }
                .fillMaxSize()
        ) {
            if (!state.isCollapsed && hasSong) {
                BackHandler(onBack = state::collapseSoft)
                val sheetNestedScroll = remember(state) { state.preUpPostDownNestedScrollConnection }
                FullscreenPlayerContent(
                    vm = vm,
                    isDarkMode = isDarkMode,
                    sheetNestedScroll = sheetNestedScroll,
                    onCollapse = state::collapseSoft,
                    onNavigateQueue = onNavigateQueue
                )
            }

            if (!state.isExpanded) {
                Box(
                    modifier = Modifier
                        .graphicsLayer { alpha = 1f - (state.progress * 16).coerceAtMost(1f) }
                        .fillMaxWidth()
                        .height(state.collapsedBound),
                    contentAlignment = Alignment.TopCenter
                ) {
                    PlayerBar(
                        vm = vm,
                        song = currentSong,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        isDarkMode = isDarkMode,
                        onToggle = vm::togglePlayPause,
                        onAddTo = { currentSong?.let(onAddTo) },
                        onTap = state::expandSoft,
                        modifier = Modifier.height(PlayerBarHeight)
                    )
                }
            }
        }
    }
}

// ── Expanded content ─────────────────────────────────────────────────────────────────────────

@Composable
private fun FullscreenPlayerContent(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    sheetNestedScroll: NestedScrollConnection,
    onCollapse: () -> Unit,
    onNavigateQueue: () -> Unit
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val song by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val position by vm.currentPosition.collectAsState()
    val duration by vm.duration.collectAsState()
    val customPlaylists by vm.customPlaylists.collectAsState()
    val sleepTimerMode by vm.sleepTimerMode.collectAsState()
    val tempoPitchSpeed by vm.currentSpeed.collectAsState()
    val tempoPitchPitch by vm.currentPitch.collectAsState()
    val openSongInfo = LocalSongInfo.current

    var showAddToSheet by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showTempoPitchDialog by remember { mutableStateOf(false) }
    var showShareChoice by remember { mutableStateOf(false) }
    // Lyrics mode: the artwork gives way to the lyrics, the bottom row slides away (taking the
    // title and controls down with it) and a floating pill takes over (back, mode, refresh).
    var lyricsMode by remember { mutableStateOf(false) }
    var isRealTime by remember { mutableStateOf(true) }
    var lyricsRefresh by remember { mutableStateOf(0) }
    var lyricsUserScrolling by remember { mutableStateOf(false) }
    val lyricsProgress = animateFloatAsState(
        targetValue = if (lyricsMode) 1f else 0f,
        animationSpec = tween(350),
        label = "fullscreenLyricsProgress"
    )
    val lyricsVisible by remember { derivedStateOf { lyricsProgress.value > 0f } }
    var dragPosition by remember { mutableStateOf<Long?>(null) }

    // Light: the soft grey of the design. Dark: pure black, like the reference player.
    val baseBg = if (isDarkMode) Color.Black else Color(0xFFE8E8E8)
    val lyricsBg = if (isDarkMode) Color.Black else Color.White
    val fg = if (isDarkMode) Color.White else Color.Black
    val subColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val basePillBg = if (isDarkMode) Color(0xFF2A2A2A) else Color.White
    val lyricsPillBg = if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE8E8E8)
    val bgColor = lerp(baseBg, lyricsBg, lyricsProgress.value)
    val pillBg = lerp(basePillBg, lyricsPillBg, lyricsProgress.value)

    // Back closes the lyrics first, then (via the sheet's own handler) the player.
    BackHandler(enabled = lyricsMode) { lyricsMode = false }

    // The three dots turn red while a sleep timer or a tempo/pitch change is active, replacing
    // the little indicator icons the top bar used to carry.
    val featureActive = sleepTimerMode != MusicViewModel.SLEEP_TIMER_OFF ||
        tempoPitchSpeed != 1.0f || tempoPitchPitch != 0

    // Same composition as ViMusic's player: a fixed top margin, then the artwork (weight 1.25)
    // above the controls block (weight 1) whose spacers share the leftover height. The gap
    // between the playback controls and the bottom row is Audiobasics' own, and the whole
    // bottom row is lifted a little off the screen edge.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(top = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Artwork: square, shrinks on short screens ───────────────────────────────────
        Box(modifier = Modifier.weight(1.25f).fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - lyricsProgress.value }
                .padding(start = 32.dp, end = 32.dp, top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            val side = minOf(maxWidth, maxHeight)
            val cachedThumbPath = remember(song?.id) {
                song?.id?.let { CacheManager.getCachedThumbPath(context, it) }
            }
            Crossfade(
                targetState = song?.id to (cachedThumbPath ?: song?.thumbnail),
                animationSpec = tween(260),
                label = "fullscreenPlayerArt"
            ) { (_, model) ->
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(side)
                        .shadow(10.dp, RoundedCornerShape(6.dp))
                        .clip(RoundedCornerShape(6.dp))
                )
            }
        }

        if (lyricsVisible) {
            FullscreenLyricsArea(
                vm = vm,
                textColor = fg,
                isRealTime = isRealTime,
                refreshKey = lyricsRefresh,
                userScrolling = lyricsUserScrolling,
                onUserScrolling = { lyricsUserScrolling = it },
                sheetNestedScroll = sheetNestedScroll,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = lyricsProgress.value }
            )
            // The pill hides while the lyrics are being scrolled and comes back by itself.
            AnimatedVisibility(
                visible = lyricsMode && !lyricsUserScrolling,
                enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
            ) {
                LyricsPill(
                    isDarkMode = isDarkMode,
                    isRealTime = isRealTime,
                    onBack = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        lyricsMode = false
                    },
                    onRealTime = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        isRealTime = true
                    },
                    onStatic = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        isRealTime = false
                    },
                    onRefresh = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        song?.let { CacheManager.getLyricsFile(context, it.id).delete() }
                        lyricsRefresh++
                    }
                )
            }
        }
        }

        // ── Controls block (ViMusic: weight 1, spacers 1 / 0.5 / 1 around the content) ───
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        // Gap 1 (artwork -> title) and gap 2 (artist -> progress bar) share the same weight, so
        // they are always the same size. The extra 8dp here makes them look equal too: the
        // progress row has about that much more empty space around its bar than the title has
        // above its letters.
        Spacer(Modifier.weight(0.5f))
        Spacer(Modifier.height(8.dp))

        // ── Title / artist, centered. Long titles fade out instead of ending in "..." ────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            FadeText(
                text = song?.title ?: "Song Name",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                color = fg
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (song?.isExplicit == true) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (isDarkMode) Color(0xFF444444) else Color(0xFFC4C4C4))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "E",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = if (isDarkMode) Color(0xFFCCCCCC) else Color(0xFF555555)
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            FadeText(
                text = song?.artist ?: "Artist",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                color = subColor,
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        // Gap 2: same weight as gap 1 above.
        Spacer(Modifier.weight(0.5f))

        // ── Progress ────────────────────────────────────────────────────────────────────
        val displayPosition = dragPosition ?: position
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTime(displayPosition),
                fontFamily = NothingFont,
                fontWeight = FontWeight.Normal,
                fontSize = 17.sp,
                color = fg
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                DashedProgressBar(
                    progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                    onSeek = { seekProgress ->
                        vm.seekTo((seekProgress * duration).toLong())
                        dragPosition = null
                    },
                    onDragging = { dragProgress -> dragPosition = (dragProgress * duration).toLong() },
                    modifier = Modifier.fillMaxWidth(),
                    hapticsEnabled = hapticsEnabled,
                    context = context,
                    isDarkMode = isDarkMode
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = formatTime(duration),
                fontFamily = NothingFont,
                fontWeight = FontWeight.Normal,
                fontSize = 17.sp,
                color = fg
            )
        }

        Spacer(Modifier.weight(1f))

        // ── Playback controls: previous · play/pause pill · next ────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        vm.skipToPrevious()
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Previous",
                    tint = fg,
                    modifier = Modifier.padding(start = 2.5.dp).size(43.dp)
                )
            }

            Box(
                modifier = Modifier
                    .width(168.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(pillBg)
                    .clickable {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        vm.togglePlayPause()
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = fg)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = fg,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = if (isPlaying) "Pause" else "Play",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                            color = fg
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        vm.skipToNext()
                    },
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = "Next",
                    tint = fg,
                    modifier = Modifier.padding(end = 2.5.dp).size(43.dp)
                )
            }
        }

        }

        // The spacer, the bottom row and the lift below it share one box that shrinks to nothing
        // in lyrics mode, which is what slides the title and controls down.
        Column(
            modifier = Modifier
                .graphicsLayer { alpha = 1f - lyricsProgress.value }
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val h = (placeable.height * (1f - lyricsProgress.value)).toInt()
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
        ) {
        // Space between the playback controls and the bottom row (was 10dp; a little more moves
        // the controls up).
        Spacer(Modifier.height(22.dp))

        // ── Bottom row: info · add · LYRICS · more · speakers ───────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Song info and lyrics just pop up for now (a smooth transition comes later).
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                song?.let(openSongInfo)
            }) {
                Icon(Icons.Default.Info, contentDescription = "Song info", tint = fg, modifier = Modifier.size(28.dp))
            }

            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                showAddToSheet = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add to playlist", tint = fg, modifier = Modifier.size(31.dp))
            }

            Text(
                text = "LYRICS",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = fg,
                modifier = Modifier.clickable {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    lyricsMode = true
                }
            )

            PlayerOverflowMenuButton(
                vm = vm,
                context = context,
                hapticsEnabled = hapticsEnabled,
                song = song,
                iconTint = if (featureActive) Color.Red else fg,
                onDismiss = onCollapse,
                onNavigateQueue = onNavigateQueue,
                onShowShareChoice = { showShareChoice = true },
                onShowSleepDialog = { showSleepDialog = true },
                onShowTempoPitchDialog = { showTempoPitchDialog = true }
            )

            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.SpeakerGroup, contentDescription = null, tint = fg, modifier = Modifier.size(28.dp))
            }
        }

        // Lifts the bottom row off the screen edge.
        Spacer(Modifier.height(16.dp))
        }
    }

    // ── Popups: same ones the card player opens, stacked above the sheet ─────────────────
    if (showAddToSheet && song != null) {
        AddToPlaylistSheet(
            song = song!!,
            vm = vm,
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
            context = context,
            onDismiss = { showAddToSheet = false },
            onCreateNew = { showCreatePlaylist = true }
        )
    }
    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
            existingNames = customPlaylists.map { it.name },
            onDismiss = { showCreatePlaylist = false },
            onCreate = { name, emoji -> vm.createPlaylistAwait(name, emoji) }
        )
    }
    if (showShareChoice && song != null) {
        ShareChoiceDialog(
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
            context = context,
            onAudiobasicsLink = {
                AudiobasicsLinks.shareText(context, AudiobasicsLinks.songLink(song!!.id), "Share song")
            },
            onYoutubeLink = {
                AudiobasicsLinks.shareText(context, "https://www.youtube.com/watch?v=${song!!.id}", "Share song")
            },
            onDismiss = { showShareChoice = false }
        )
    }
    if (showSleepDialog) {
        SleepTimerDialog(
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
            context = context,
            onDismiss = { showSleepDialog = false },
            onEndOfSong = {
                showSleepDialog = false
                vm.startEndOfSongSleepTimer()
            },
            onCustom = { minutes ->
                showSleepDialog = false
                vm.startCustomSleepTimer(minutes)
            }
        )
    }
    if (showTempoPitchDialog) {
        TempoPitchDialog(
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
            context = context,
            speed = tempoPitchSpeed,
            pitchSemitones = tempoPitchPitch,
            onSpeedChange = { vm.setTempoSpeed(it) },
            onPitchChange = { vm.setTempoPitch(it) },
            onReset = { vm.resetTempoPitch() },
            onDismiss = { showTempoPitchDialog = false }
        )
    }
}
