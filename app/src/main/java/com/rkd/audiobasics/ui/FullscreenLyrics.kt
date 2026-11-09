package com.rkd.audiobasics.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.cache.CacheManager
import com.rkd.audiobasics.lyrics.LyricsCache
import com.rkd.audiobasics.lyrics.LyricsOutcome
import com.rkd.audiobasics.lyrics.LyricsRepository
import com.rkd.audiobasics.lyrics.LyricsResult
import com.rkd.audiobasics.ui.theme.BodyFont
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.ui.theme.animatedBodyWeight
import com.rkd.audiobasics.ui.theme.bodyFontAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Lyrics inside the fullscreen player. Same lookup, cache, real-time/static modes and typography
// as LyricsScreen.kt's card, but drawn straight on the player's background, with the rows fading
// out the further they are from the line being sung, and the top and bottom edges fading away.
// ─────────────────────────────────────────────────────────────────────────────────────────────

private val TopFade = 64.dp
private val BottomFade = 104.dp

/** How visible a row is, by how many rows it is away from the active one. */
private fun lyricRowAlpha(distance: Int): Float = when {
    distance <= 0 -> 1f
    distance == 1 -> 0.55f
    distance == 2 -> 0.38f
    distance == 3 -> 0.28f
    else -> 0.2f
}

/**
 * The lyrics themselves (no controls). [userScrolling] / [onUserScrolling] let the floating pill
 * hide while the person is scrolling the lyrics and come back by itself afterwards.
 * [sheetNestedScroll] hands a downward overscroll at the top of the list to the player sheet, so
 * swiping down from the top of the lyrics still closes the player.
 */
@Composable
fun FullscreenLyricsArea(
    vm: MusicViewModel,
    textColor: Color,
    isRealTime: Boolean,
    refreshKey: Int,
    userScrolling: Boolean,
    onUserScrolling: (Boolean) -> Unit,
    sheetNestedScroll: NestedScrollConnection,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentSong by vm.currentSong.collectAsState()
    val currentPosition by vm.currentPosition.collectAsState()
    val duration by vm.duration.collectAsState()

    var lyricsResult by remember { mutableStateOf<LyricsResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    // hasError = the lookup itself failed (offline, timeout...); a clean "the server has nothing
    // for this song" leaves it false and lyricsResult null.
    var hasError by remember { mutableStateOf(false) }

    val syncedState = rememberLazyListState()
    val staticState = rememberLazyListState()

    // Cache first, network second. The pill's refresh button deletes the cached file and bumps
    // [refreshKey], which re-runs this (and, with the file gone, goes to the network).
    LaunchedEffect(currentSong?.id, refreshKey) {
        val song = currentSong ?: return@LaunchedEffect
        isLoading = true
        hasError = false
        lyricsResult = null
        try {
            val cached = CacheManager.loadLyrics(context, song.id)
            if (cached != null) {
                lyricsResult = LyricsCache.deserialize(cached)
                isLoading = false
                return@LaunchedEffect
            }
            when (val outcome = LyricsRepository.fetchLyrics(
                title = song.title,
                artist = song.artist,
                duration = duration
            )) {
                is LyricsOutcome.Found -> {
                    lyricsResult = outcome.result
                    CacheManager.saveLyrics(context, song.id, LyricsCache.serialize(outcome.result))
                }
                LyricsOutcome.NotFound -> Unit
                LyricsOutcome.Error -> hasError = true
            }
        } catch (_: Exception) {
            hasError = true
        } finally {
            isLoading = false
        }
    }

    val result = lyricsResult
    val showSynced = isRealTime && result?.hasSynced == true
    val activeState = if (showSynced) syncedState else staticState

    // User scrolling (a finger on the list, then its fling) hides the pill; it returns on its own
    // once the list has been still for a moment. Auto-scrolling to the next line doesn't count.
    LaunchedEffect(activeState) {
        activeState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) onUserScrolling(true)
        }
    }
    LaunchedEffect(activeState) {
        snapshotFlow { activeState.isScrollInProgress }.collectLatest { moving ->
            if (!moving) {
                delay(600)
                onUserScrolling(false)
            }
        }
    }

    val currentLineIndex = remember(currentPosition, lyricsResult) {
        val lines = lyricsResult?.syncedLines ?: return@remember -1
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= currentPosition) idx = i else break
        }
        idx
    }

    // Glide the active line to about a third of the way down, as LyricsScreen does. Paused while
    // the person is scrolling by hand; when they let go it glides back to the current line.
    LaunchedEffect(currentLineIndex, showSynced, userScrolling) {
        if (showSynced && currentLineIndex >= 0 && !userScrolling) {
            val info = syncedState.layoutInfo
            val target = info.visibleItemsInfo.firstOrNull { it.index == currentLineIndex }
            if (target != null) {
                val viewportH = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                val desiredTop = viewportH * 0.33f - target.size / 2f
                syncedState.animateScrollBy(
                    value = target.offset - desiredTop,
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                )
            } else {
                syncedState.animateScrollToItem((currentLineIndex - 2).coerceAtLeast(0))
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Powered by LRCLIB",
            fontFamily = NothingFont,
            fontSize = 12.sp,
            color = textColor.copy(alpha = 0.58f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            textAlign = TextAlign.Center
        )
        Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val h = size.height
                if (h > 0f) {
                    val top = (TopFade.toPx() / h).coerceIn(0f, 0.45f)
                    val bottom = (BottomFade.toPx() / h).coerceIn(0f, 0.45f)
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                top to Color.Black,
                                (1f - bottom) to Color.Black,
                                1f to Color.Transparent
                            )
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
    ) {
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Red)
                }
            }
            hasError || result == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        // A failed fetch and "no lyrics exist" can't be told apart, so one message covers both.
                        text = "Couldn't load lyrics.\nPerhaps listen the old school way :)\nTap refresh to try again.",
                        fontFamily = BodyFont,
                        fontSize = 16.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
            showSynced -> {
                val syncedLines = result!!.syncedLines
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    val textMeasurer = rememberTextMeasurer()
                    val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                    // Wrapping is decided once, at the heaviest weight and the grown size, so a
                    // line never jumps from one row to two as it becomes the active one.
                    val wrapped = remember(syncedLines, widthPx) {
                        val style = TextStyle(
                            fontFamily = bodyFontAt(LYRIC_ACTIVE_WEIGHT),
                            fontWeight = FontWeight(LYRIC_ACTIVE_WEIGHT),
                            fontSize = LYRIC_FONT_SIZE.sp,
                            textAlign = TextAlign.Center
                        )
                        syncedLines.map { line ->
                            prewrapLyric(line.text, style, textMeasurer, (widthPx / LYRIC_ACTIVE_SCALE).toInt())
                        }
                    }
                    LazyColumn(
                        state = syncedState,
                        modifier = Modifier.fillMaxSize().nestedScroll(sheetNestedScroll),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 32.dp, bottom = 112.dp)
                    ) {
                        itemsIndexed(syncedLines) { index, _ ->
                            val isCurrent = index == currentLineIndex
                            val t by animateFloatAsState(
                                targetValue = if (isCurrent) 1f else 0f,
                                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                                label = "lyricActive"
                            )
                            // Rows fade the further they are from the line being sung.
                            val rowAlpha by animateFloatAsState(
                                targetValue = lyricRowAlpha(kotlin.math.abs(index - currentLineIndex)),
                                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                                label = "lyricRowAlpha"
                            )
                            val weight = (((LYRIC_THIN_WEIGHT + (LYRIC_ACTIVE_WEIGHT - LYRIC_THIN_WEIGHT) * t) + 5f) / 10f).toInt() * 10
                            Text(
                                text = wrapped.getOrElse(index) { " " },
                                fontFamily = bodyFontAt(weight),
                                fontWeight = FontWeight(weight),
                                fontSize = LYRIC_FONT_SIZE.sp,
                                color = textColor.copy(alpha = rowAlpha),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        val scale = 1f + (LYRIC_ACTIVE_SCALE - 1f) * t
                                        scaleX = scale
                                        scaleY = scale
                                    }
                            )
                        }
                    }
                }
            }
            else -> {
                val lines = result!!.plainText.lines()
                LazyColumn(
                    state = staticState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .nestedScroll(sheetNestedScroll),
                    contentPadding = PaddingValues(top = 32.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(lines) { _, line ->
                        Text(
                            text = line.ifBlank { " " },
                            fontFamily = BodyFont,
                            fontSize = 17.sp,
                            color = textColor.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * The floating pill over the bottom of the lyrics: back, Real time / Static, refresh.
 */
@Composable
fun LyricsPill(
    isDarkMode: Boolean,
    isRealTime: Boolean,
    onBack: () -> Unit,
    onRealTime: () -> Unit,
    onStatic: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = if (isDarkMode) Color.White else Color.Black
    val pillColor = if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE8E8E8)
    Row(
        modifier = modifier
            .fillMaxWidth(0.8f)
            .widthIn(max = 320.dp)
            .height(46.dp)
            // Drop shadow in light mode, soft light halo in dark: the same lift as the app's
            // other floating pills.
            .pillSurface(pillColor)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor, modifier = Modifier.size(22.dp))
        }
        val realTimeWeight = animatedBodyWeight(isRealTime)
        val staticWeight = animatedBodyWeight(!isRealTime)
        Text(
            text = "Real time",
            fontFamily = bodyFontAt(realTimeWeight),
            fontWeight = FontWeight(realTimeWeight),
            fontSize = 16.sp,
            color = if (isRealTime) Color.Red else textColor,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRealTime)
                .padding(horizontal = 6.dp, vertical = 8.dp)
        )
        Text(
            text = "Static",
            fontFamily = bodyFontAt(staticWeight),
            fontWeight = FontWeight(staticWeight),
            fontSize = 16.sp,
            color = if (!isRealTime) Color.Red else textColor,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onStatic)
                .padding(horizontal = 6.dp, vertical = 8.dp)
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh lyrics", tint = textColor, modifier = Modifier.size(22.dp))
        }
    }
}
