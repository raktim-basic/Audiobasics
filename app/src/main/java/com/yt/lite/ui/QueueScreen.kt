package com.yt.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.yt.lite.ui.theme.NothingFont

@Composable
fun QueueScreen(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit
) {
    val queue by vm.queue.collectAsState()
    val currentSong by vm.currentSong.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var targetIndex by remember { mutableStateOf<Int?>(null) }
    var itemHeightPx by remember { mutableStateOf(80f) }

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
            Text(
                text = "${queue.size} songs",
                fontFamily = NothingFont,
                fontSize = 13.sp,
                color = Color.Gray
            )
        }

        DashedDivider(
            modifier = Modifier.fillMaxWidth(),
            isDarkMode = isDarkMode
        )

        if (queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
                state = rememberLazyListState()
            ) {
                itemsIndexed(
                    items = queue,
                    key = { _, song -> song.id }
                ) { index, song ->
                    val isCurrentSong = song.id == currentSong?.id
                    val isLiked = likedSongs.any { it.id == song.id }
                    val isDragging = draggingIndex == index
                    val isTarget = targetIndex == index

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                if (index == 0) {
                                    itemHeightPx = coords.size.height.toFloat()
                                }
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
                                    isTarget -> Color.Red.copy(alpha = 0.08f)
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
                                            onDragStart = {
                                                dragOffsetY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffsetY += dragAmount.y
                                                val h = itemHeightPx.takeIf { it > 0 } ?: 80f
                                                val newTarget = (index + (dragOffsetY / h).toInt())
                                                    .coerceIn(0, queue.size - 1)
                                                targetIndex = newTarget
                                            },
                                            onDragEnd = {
                                                val from = draggingIndex
                                                val to = targetIndex
                                                if (from != null && to != null && from != to) {
                                                    vm.reorderQueue(from, to)
                                                }
                                                draggingIndex = null
                                                dragOffsetY = 0f
                                                targetIndex = null
                                            },
                                            onDragCancel = {
                                                draggingIndex = null
                                                dragOffsetY = 0f
                                                targetIndex = null
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
                            onClick = { vm.playWithQueue(song, queue) },
                            onLike = { vm.toggleLike(song) },
                            onShare = {},
                            onRemoveFromQueue = { vm.removeFromQueue(song) },
                            onReorder = {
                                draggingIndex = index
                                dragOffsetY = 0f
                            },
                            onRetryCache = { vm.retryCache(song) },
                            onRemoveLike = { vm.toggleLike(song) }
                        )
                    }
                }
            }
        }

        QueueBottomBar(
            isDarkMode = isDarkMode,
            onBack = onBack
        )
    }
}

@Composable
fun QueueBottomBar(
    isDarkMode: Boolean,
    onBack: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)
    val iconColor = if (isDarkMode) Color.White else Color.Black

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(vertical = 12.dp, horizontal = 20.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
