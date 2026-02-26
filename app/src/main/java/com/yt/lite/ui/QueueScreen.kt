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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // Drag state
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }

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

        DashedDivider(modifier = Modifier.fillMaxWidth())

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
                itemsIndexed(queue) { index, song ->
                    val isCurrentSong = song.id == currentSong?.id
                    val isLiked = likedSongs.any { it.id == song.id }
                    val isDragging = draggingIndex == index
                    val isTarget = dragTargetIndex == index

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    isDragging -> Color.Red.copy(alpha = 0.15f)
                                    isTarget -> Color.Gray.copy(alpha = 0.15f)
                                    isCurrentSong -> if (isDarkMode)
                                        Color.White.copy(alpha = 0.05f)
                                    else Color.Black.copy(alpha = 0.04f)
                                    else -> Color.Transparent
                                }
                            )
                    ) {
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
                                // Activate drag for this song
                                draggingIndex = index
                            }
                        )

                        // Current song indicator
                        if (isCurrentSong) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .width(3.dp)
                                    .height(40.dp)
                                    .background(Color.Red)
                            )
                        }
                    }
                }
            }
        }

        // Bottom bar
        QueueBottomBar(
            isDarkMode = isDarkMode,
            onBack = onBack
        )
    }

    // Handle drag and drop
    LaunchedEffect(draggingIndex) {
        if (draggingIndex != null) {
            // Auto-exit drag mode after 3 seconds if no action
            kotlinx.coroutines.delay(3000)
            draggingIndex = null
            dragTargetIndex = null
        }
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
