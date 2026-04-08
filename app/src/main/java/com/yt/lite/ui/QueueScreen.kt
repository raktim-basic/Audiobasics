package com.yt.lite.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yt.lite.ui.theme.NothingFont
import kotlinx.coroutines.delay

private fun formatTotalTime(totalMs: Long): String {
    val totalSec = totalMs / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

private fun formatCountdown(remainingMs: Long): String {
    val totalSec = (remainingMs / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@Composable
fun QueueScreen(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val queue by vm.queue.collectAsState()
    val currentSong by vm.currentSong.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val currentPosition by vm.currentPosition.collectAsState()
    val duration by vm.duration.collectAsState()

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var itemHeightPx by remember { mutableStateOf(80f) }

    // Sleep timer
    var sleepTimerEndsAt by remember { mutableStateOf<Long?>(null) }
    var sleepTimerRemaining by remember { mutableStateOf(0L) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var endOfSongMode by remember { mutableStateOf(false) }

    var timerPausedAt by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(isPlaying, sleepTimerEndsAt) {
        val endAt = sleepTimerEndsAt ?: return@LaunchedEffect
        if (!isPlaying) {
            timerPausedAt = endAt - System.currentTimeMillis()
            return@LaunchedEffect
        }
        val pausedRemaining = timerPausedAt
        if (pausedRemaining != null) {
            sleepTimerEndsAt = System.currentTimeMillis() + pausedRemaining
            timerPausedAt = null
        }
    }

    LaunchedEffect(sleepTimerEndsAt, isPlaying) {
        val endAt = sleepTimerEndsAt ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            val remaining = endAt - System.currentTimeMillis()
            if (remaining <= 0) {
                sleepTimerRemaining = 0L
                sleepTimerEndsAt = null
                endOfSongMode = false
                if (isPlaying) vm.togglePlayPause()
                break
            }
            sleepTimerRemaining = remaining
            delay(1000)
        }
    }

    val currentSongId = currentSong?.id
    LaunchedEffect(currentSongId) {
        if (endOfSongMode && sleepTimerEndsAt != null) {
            sleepTimerEndsAt = null
            sleepTimerRemaining = 0L
            endOfSongMode = false
        }
    }

    val totalMs = remember(queue) { queue.sumOf { it.duration } }

    val listState = rememberLazyListState()
    val scrollProgress by remember(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset
    ) {
        derivedStateOf {
            if (queue.size <= 1) 0f
            else (listState.firstVisibleItemIndex.toFloat() / (queue.size - 1).toFloat())
                .coerceIn(0f, 1f)
        }
    }

    LaunchedEffect(currentSong, queue) {
        val idx = queue.indexOfFirst { it.id == currentSong?.id }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    val visualQueue = remember(queue, draggingIndex, dragOffsetY, itemHeightPx) {
        val dragging = draggingIndex ?: return@remember queue
        val h = itemHeightPx.takeIf { it > 0 } ?: 80f
        val targetIndex = (dragging + (dragOffsetY / h).toInt())
            .coerceIn(0, queue.size - 1)
        if (targetIndex == dragging) return@remember queue
        val mutable = queue.toMutableList()
        val item = mutable.removeAt(dragging)
        mutable.add(targetIndex, item)
        mutable
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            isDarkMode = isDarkMode,
            onDismiss = { showSleepDialog = false },
            onEndOfSong = {
                showSleepDialog = false
                val remaining = (duration - currentPosition).coerceAtLeast(1000L)
                sleepTimerEndsAt = System.currentTimeMillis() + remaining
                endOfSongMode = true
            },
            onCustom = { minutes ->
                showSleepDialog = false
                sleepTimerEndsAt = System.currentTimeMillis() + minutes * 60_000L
                endOfSongMode = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.QueueMusic,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Queue",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = textColor
            )
            Spacer(Modifier.weight(1f))
            val validTotal = queue.isNotEmpty() && totalMs > 0
            if (validTotal) {
                Text(
                    text = "(${formatTotalTime(totalMs)})  ${queue.size} songs",
                    fontFamily = NothingFont,
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            } else {
                Text(
                    text = "${queue.size} songs",
                    fontFamily = NothingFont,
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }
        }

        // Scroll-progress divider
        DashedDivider(
            modifier = Modifier.fillMaxWidth(),
            isDarkMode = isDarkMode,
            scrollProgress = scrollProgress
        )

        if (queue.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Queue is empty\nSearch for songs to play",
                    fontFamily = NothingFont,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState
            ) {
                itemsIndexed(
                    items = visualQueue,
                    key = { _, song -> song.id }
                ) { index, song ->
                    val isCurrentSong = song.id == currentSong?.id
                    val isLiked = likedSongs.any { it.id == song.id }
                    val isDragging = draggingIndex == index

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                if (index == 0) itemHeightPx = coords.size.height.toFloat()
                            }
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffsetY else 0f
                                alpha = if (isDragging) 0.85f else 1f
                                scaleX = if (isDragging) 1.02f else 1f
                                scaleY = if (isDragging) 1.02f else 1f
                            }
                            .background(
                                when {
                                    isDragging -> if (isDarkMode)
                                        Color.White.copy(alpha = 0.1f)
                                    else Color.Black.copy(alpha = 0.06f)
                                    isCurrentSong -> if (isDarkMode)
                                        Color.White.copy(alpha = 0.05f)
                                    else Color.Black.copy(alpha = 0.04f)
                                    else -> Color.Transparent
                                }
                            )
                            .then(
                                if (isDragging) {
                                    Modifier.pointerInput(index) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { dragOffsetY = 0f },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffsetY += dragAmount.y
                                            },
                                            onDragEnd = {
                                                val from = draggingIndex
                                                val h = itemHeightPx.takeIf { it > 0 } ?: 80f
                                                val to = from?.let {
                                                    (it + (dragOffsetY / h).toInt())
                                                        .coerceIn(0, queue.size - 1)
                                                }
                                                if (from != null && to != null && from != to)
                                                    vm.reorderQueue(from, to)
                                                draggingIndex = null
                                                dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                draggingIndex = null
                                                dragOffsetY = 0f
                                            }
                                        )
                                    }
                                } else Modifier
                            )
                    ) {
                        if (isCurrentSong) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .width(3.dp)
                                    .height(52.dp)
                                    .background(Color.Red)
                            )
                        }

                        SongItem(
                            song = song,
                            isDarkMode = isDarkMode,
                            isLiked = isLiked,
                            isInQueue = true,
                            isPlaying = isCurrentSong,
                            onClick = { vm.playWithQueue(song, queue) },
                            onLike = { vm.toggleLike(song) },
                            onShare = {},
                            onRemoveFromQueue = { vm.removeFromQueue(song) },
                            onReorder = {
                                if (draggingIndex == index) {
                                    draggingIndex = null
                                    dragOffsetY = 0f
                                } else {
                                    draggingIndex = index
                                    dragOffsetY = 0f
                                }
                            },
                            onRetryCache = { vm.retryCache(song) },
                            onRemoveLike = { vm.toggleLike(song) }
                        )
                    }
                }
            }
        }

        // Thin divider above bottom bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFDDDDDD))
        )

        // Bottom bar with sleep timer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .padding(vertical = 4.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = textColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            val timerActive = sleepTimerEndsAt != null
            val queueNotEmpty = queue.isNotEmpty()
            Text(
                text = if (timerActive) "Sleep timer (${formatCountdown(sleepTimerRemaining)})" else "Sleep timer",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (timerActive) Color.Red else textColor,
                modifier = Modifier.clickable {
                    if (!queueNotEmpty) {
                        Toast.makeText(context, "Nothing is playing", Toast.LENGTH_SHORT).show()
                        return@clickable
                    }
                    if (timerActive) {
                        sleepTimerEndsAt = null
                        sleepTimerRemaining = 0L
                        endOfSongMode = false
                        timerPausedAt = null
                    } else {
                        showSleepDialog = true
                    }
                }
            )

            Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
fun SleepTimerDialog(
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onEndOfSong: () -> Unit,
    onCustom: (Long) -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF2A2A2A) else Color.White

    var showCustomInput by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Text(
                    text = "Sleep timer",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = textColor
                )

                Spacer(Modifier.height(20.dp))

                if (showCustomInput) {
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { if (it.length <= 3) customMinutes = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("Minutes...", fontFamily = NothingFont, color = Color.Gray)
                        },
                        textStyle = TextStyle(fontFamily = NothingFont, color = textColor),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Red,
                            unfocusedBorderColor = Color.Gray,
                            focusedContainerColor = surfaceColor,
                            unfocusedContainerColor = surfaceColor
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showCustomInput = false }) {
                            Text("Back", fontFamily = NothingFont, color = Color.Gray)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(Color.Red, RoundedCornerShape(8.dp))
                                .clickable {
                                    val mins = customMinutes.toLongOrNull()
                                    if (mins != null && mins > 0) onCustom(mins)
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                "Set",
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(surfaceColor, RoundedCornerShape(8.dp))
                            .clickable { onDismiss() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Cancel",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(surfaceColor, RoundedCornerShape(8.dp))
                            .clickable { onEndOfSong() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "End of this song",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Red, RoundedCornerShape(8.dp))
                            .clickable { showCustomInput = true }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Custom timer",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
