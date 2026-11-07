package com.rkd.audiobasics.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.rkd.audiobasics.ui.theme.animatedBodyWeight
import com.rkd.audiobasics.ui.theme.bodyFontAt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.rkd.audiobasics.utils.HapticUtils
import kotlinx.coroutines.launch

/**
 * The lyrics face of the player's flip card. This used to be its own floating Dialog window;
 * it's now plain content, drawn inline on the back of [PlayerDialog]'s card once it's flipped
 * there — same background/corner-clip/edge-to-edge treatment as the front (player) face, all
 * supplied by the shared card container in PlayerDialog. [onBack] flips back to the player face
 * rather than closing anything.
 */
@Composable
fun LyricsCardContent(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val currentSong by vm.currentSong.collectAsState()
    val currentPosition by vm.currentPosition.collectAsState()
    val duration by vm.duration.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()

    var isRealTime by remember { mutableStateOf(true) }
    var lyricsResult by remember { mutableStateOf<LyricsResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    // hasError = the lookup itself failed (offline, timeout...); a clean "the server has nothing
    // for this song" leaves it false and lyricsResult null.
    var hasError by remember { mutableStateOf(false) }
    var forceRefresh by remember { mutableStateOf(0) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF2A2A2A) else Color.White

    // Load lyrics — check cache first, fetch from network if needed
    LaunchedEffect(currentSong?.id, forceRefresh) {
        val song = currentSong ?: return@LaunchedEffect
        isLoading = true
        hasError = false
        lyricsResult = null

        try {
            // If not a forced refresh, check cache first
            if (forceRefresh == 0) {
                val cached = CacheManager.loadLyrics(context, song.id)
                if (cached != null) {
                    lyricsResult = LyricsCache.deserialize(cached)
                    isLoading = false
                    return@LaunchedEffect
                }
            }

            // Fetch from network
            when (val outcome = LyricsRepository.fetchLyrics(
                title = song.title,
                artist = song.artist,
                duration = duration
            )) {
                is LyricsOutcome.Found -> {
                    lyricsResult = outcome.result
                    // Cache the result if found
                    val json = LyricsCache.serialize(outcome.result)
                    CacheManager.saveLyrics(context, song.id, json)
                }
                LyricsOutcome.NotFound -> Unit // lyricsResult stays null, hasError stays false
                LyricsOutcome.Error -> hasError = true
            }
        } catch (_: Exception) {
            hasError = true
        } finally {
            isLoading = false
        }
    }

    val currentLineIndex = remember(currentPosition, lyricsResult) {
        val lines = lyricsResult?.syncedLines ?: return@remember -1
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= currentPosition) idx = i
            else break
        }
        idx
    }

    // Glide the active line to a fixed spot (about a third of the way down) with a quick ease, instead of
    // snapping. A new line cancels the previous glide and carries on from where it is, so it stays smooth.
    LaunchedEffect(currentLineIndex) {
        if (isRealTime && currentLineIndex >= 0) {
            val info = listState.layoutInfo
            val target = info.visibleItemsInfo.firstOrNull { it.index == currentLineIndex }
            if (target != null) {
                val viewportH = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                val desiredTop = viewportH * 0.33f - target.size / 2f
                listState.animateScrollBy(
                    value = target.offset - desiredTop,
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                )
            } else {
                // Far away (first open, or a seek): jump close first.
                listState.animateScrollToItem((currentLineIndex - 2).coerceAtLeast(0))
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Tabs + Refresh ──────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val realTimeWeight = animatedBodyWeight(isRealTime)
            val staticWeight = animatedBodyWeight(!isRealTime)
            Text(
                text = "Real-time",
                fontFamily = bodyFontAt(realTimeWeight),
                fontWeight = FontWeight(realTimeWeight),
                fontSize = 16.sp,
                color = if (isRealTime) Color.Red else Color.Gray,
                modifier = Modifier.clickable {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    isRealTime = true
                }
            )
            Spacer(Modifier.width(24.dp))
            Text(
                text = "Static",
                fontFamily = bodyFontAt(staticWeight),
                fontWeight = FontWeight(staticWeight),
                fontSize = 16.sp,
                color = if (!isRealTime) Color.Red else Color.Gray,
                modifier = Modifier.clickable {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    isRealTime = false
                }
            )
            Spacer(Modifier.weight(1f))
            // Refresh button
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                // Delete cached lyrics and re-fetch
                currentSong?.let { song ->
                    CacheManager.getLyricsFile(context, song.id).delete()
                }
                forceRefresh++
            }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh lyrics",
                    tint = textColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Dashed divider
        Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            drawLine(
                color = Color.Gray,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
            )
        }

        // ── Lyrics content ──────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.Red)
                    }
                }
                hasError || lyricsResult == null -> {
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
                isRealTime && lyricsResult!!.hasSynced -> {
                    val syncedLines = lyricsResult!!.syncedLines
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val textMeasurer = rememberTextMeasurer()
                        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                        // Decide every line's wrapping once, using the heaviest weight and the grown size,
                        // and bake it into the text. A line then has the same number of lines whether it is
                        // thin or active, so it never jumps from one line to two when it grows.
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
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(vertical = 16.dp)
                        ) {
                            itemsIndexed(syncedLines) { index, _ ->
                                val isCurrent = index == currentLineIndex
                                val t by animateFloatAsState(
                                    targetValue = if (isCurrent) 1f else 0f,
                                    animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                                    label = "lyricActive"
                                )
                                val weight = (((LYRIC_THIN_WEIGHT + (LYRIC_ACTIVE_WEIGHT - LYRIC_THIN_WEIGHT) * t) + 5f) / 10f).toInt() * 10
                                Text(
                                    text = wrapped.getOrElse(index) { " " },
                                    fontFamily = bodyFontAt(weight),
                                    fontWeight = FontWeight(weight),
                                    fontSize = LYRIC_FONT_SIZE.sp,
                                    color = lerp(Color.Gray, textColor, t),
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
                    val lines = lyricsResult!!.plainText.lines()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(lines) { _, line ->
                            Text(
                                text = line.ifBlank { " " },
                                fontFamily = BodyFont,
                                fontSize = 17.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // ── Bottom bar ──────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(surfaceColor)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onBack()
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
            }
            Text(
                text = "Powered by LRCLIB",
                fontFamily = NothingFont,
                fontSize = 12.sp,
                color = Color.Gray
            )
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                vm.togglePlayPause()
            }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = textColor,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

// ── Lyric look: tweak these to taste ────────────────────────────
private const val LYRIC_FONT_SIZE = 18
private const val LYRIC_THIN_WEIGHT = 350      // idle lines
private const val LYRIC_ACTIVE_WEIGHT = 800    // the line being sung
private const val LYRIC_ACTIVE_SCALE = 1.20f   // the active line also grows by this factor

/** Inserts line breaks where [text] would wrap at [widthPx] with [style], so the wrapping is fixed. */
private fun prewrapLyric(text: String, style: TextStyle, measurer: TextMeasurer, widthPx: Int): String {
    if (text.isBlank()) return " "
    if (widthPx <= 0) return text
    val result = measurer.measure(AnnotatedString(text), style, constraints = Constraints(maxWidth = widthPx))
    if (result.lineCount <= 1) return text
    val sb = StringBuilder()
    var start = 0
    for (i in 0 until result.lineCount) {
        val end = result.getLineEnd(i)
        sb.append(text.substring(start, end).trimEnd())
        if (i < result.lineCount - 1) sb.append('\n')
        start = end
    }
    return sb.toString()
}
