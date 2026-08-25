package com.rkd.audiobasics.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private fun <T> MutableList<T>.move(fromIndex: Int, toIndex: Int) {
    add(toIndex, removeAt(fromIndex))
}

fun formatCountdown(remainingMs: Long): String {
    val totalSec = (remainingMs / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@Composable
fun QueueScreen(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onAddTo: (com.rkd.audiobasics.data.Song) -> Unit = {}
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val queue by vm.queue.collectAsState()
    val currentSong by vm.currentSong.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()
    val cachingSongIds by vm.cachingSongIds.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val currentPosition by vm.currentPosition.collectAsState()
    val duration by vm.duration.collectAsState()
    val repeatMode by vm.repeatMode.collectAsState()

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White

    var draggingIndex by remember { mutableStateOf<Int?>(null) }

    val sleepTimerMode by vm.sleepTimerMode.collectAsState()
    val sleepTimerRemaining by vm.sleepTimerRemaining.collectAsState()
    var showSleepDialog by remember { mutableStateOf(false) }

    val initialQueueIndex = remember {
        queue.indexOfFirst { it.id == currentSong?.id }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialQueueIndex)
    var hasHandledInitialScroll by remember { mutableStateOf(false) }

    // Local, live-editable copy of the queue. The LazyColumn renders this list, so
    // dragging an armed item reorders it instantly (with animated reflow of the
    // other rows) without waiting for the ViewModel/controller round-trip. It's
    // kept in sync with the real queue whenever that changes for any other reason.
    val liveQueue = remember { mutableStateListOf<Song>().apply { addAll(queue) } }
    LaunchedEffect(queue) {
        liveQueue.clear()
        liveQueue.addAll(queue)
    }

    var pendingReorder by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val current = pendingReorder
        pendingReorder = if (current == null) from.index to to.index else current.first to to.index
        liveQueue.move(from.index, to.index)
    }

    // Only commit to the ViewModel (and unarm) once the finger actually lifts —
    // this is what "settles" the drag, matching Metrolist's approach. Reading
    // isAnyItemDragging (rather than watching liveQueue) means a reorder never
    // fires from anything except a real drag release.
    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            pendingReorder?.let { (from, to) ->
                if (from != to) vm.reorderQueue(from, to)
            }
            pendingReorder = null
            draggingIndex = null
        }
    }

    val scrollProgress = remember(listState, queue.size) {
        derivedStateOf {
            if (queue.size <= 1) return@derivedStateOf 0f
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            (lastVisibleIndex.toFloat() / (queue.size - 1).toFloat()).coerceIn(0f, 1f)
        }
    }

    LaunchedEffect(currentSong?.id) {
        val idx = queue.indexOfFirst { it.id == currentSong?.id }
        if (idx < 0) return@LaunchedEffect
        if (!hasHandledInitialScroll) {
            // Screen just opened — listState was already seeded to this index,
            // so no animation here; just mark it handled.
            hasHandledInitialScroll = true
        } else {
            // The currently playing song actually changed (e.g. track advanced/
            // skipped) — animate to its new position. Reordering the queue does
            // NOT change currentSong?.id, so it never re-triggers this effect.
            listState.animateScrollToItem(idx)
        }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
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
                text = if (draggingIndex != null) "Drag and drop" else "Queue",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = textColor
            )
            Spacer(Modifier.weight(1f))
            if (draggingIndex == null) {
                Text(
                    text = "${queue.size} songs",
                    fontFamily = NothingFont,
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }
        }

        DashedDivider(
            modifier = Modifier.fillMaxWidth(),
            isDarkMode = isDarkMode,
            scrollProgress = scrollProgress.value
        )

        if (queue.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Queue is empty",
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
                    items = liveQueue,
                    key = { _, song -> song.id }
                ) { index, song ->
                    val isCurrentSong = song.id == currentSong?.id
                    val isLiked = likedSongs.any { it.id == song.id }
                    val armed = draggingIndex == index

                    ReorderableItem(
                        state = reorderableState,
                        key = song.id
                    ) { isActivelyDragging ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .graphicsLayer {
                                    val s = if (isActivelyDragging) 1.02f else 1f
                                    scaleX = s
                                    scaleY = s
                                    alpha = if (isActivelyDragging) 0.9f else 1f
                                }
                                .then(
                                    if (armed) {
                                        Modifier.longPressDraggableHandle(
                                            onDragStarted = {
                                                HapticUtils.performStrongHaptic(context)
                                            }
                                        )
                                    } else Modifier
                                )
                                .background(
                                    when {
                                        isActivelyDragging -> if (isDarkMode)
                                            Color.White.copy(alpha = 0.1f)
                                        else Color.Black.copy(alpha = 0.06f)
                                        armed -> if (isDarkMode)
                                            Color.White.copy(alpha = 0.06f)
                                        else Color.Black.copy(alpha = 0.05f)
                                        isCurrentSong -> if (isDarkMode)
                                            Color.White.copy(alpha = 0.05f)
                                        else Color.Black.copy(alpha = 0.04f)
                                        else -> Color.Transparent
                                    }
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
                                hapticsEnabled = hapticsEnabled,
                                context = context,
                                onClick = {
                                    // While any item is armed for reorder, taps must not change
                                    // playback — a press-and-release-without-moving can otherwise
                                    // race the drag gesture and fire this as a normal click.
                                    if (draggingIndex == null) {
                                        vm.playWithQueue(song, queue)
                                    }
                                },
                                onLike = { vm.toggleLike(song) },
                                onShare = {},
                                onRemoveFromQueue = { vm.removeFromQueue(song) },
                                onReorder = {
                                    draggingIndex = if (armed) null else index
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                },
                                onRetryCache = { vm.retryCache(song) },
                                onRemoveLike = { vm.toggleLike(song) },
                                onAddTo = { onAddTo(song) },
                                isDragging = armed,
                                isCaching = song.id in cachingSongIds
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFDDDDDD))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .padding(vertical = 4.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onBack()
            }) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = textColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            val timerActive = sleepTimerMode != MusicViewModel.SLEEP_TIMER_OFF
            val queueNotEmpty = queue.isNotEmpty()
            Text(
                text = when (sleepTimerMode) {
                    MusicViewModel.SLEEP_TIMER_END_OF_SONG -> "Sleep timer (end of this song)"
                    MusicViewModel.SLEEP_TIMER_CUSTOM -> "Sleep timer (${formatCountdown(sleepTimerRemaining)})"
                    else -> "Sleep timer"
                },
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (timerActive) Color.Red else textColor,
                modifier = Modifier.clickable {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    if (!queueNotEmpty) {
                        Toast.makeText(context, "Nothing is playing", Toast.LENGTH_SHORT).show()
                        return@clickable
                    }
                    if (timerActive) {
                        vm.cancelSleepTimer()
                    } else {
                        showSleepDialog = true
                    }
                }
            )

            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                vm.toggleRepeatMode()
            }) {
                Icon(
                    imageVector = if (repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = "Repeat Mode",
                    tint = if (repeatMode == 0) textColor else Color.Red,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}
