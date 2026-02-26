package com.yt.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yt.lite.lyrics.LyricsRepository
import com.yt.lite.lyrics.LyricLine
import com.yt.lite.ui.theme.NothingFont
import kotlinx.coroutines.launch

@Composable
fun LyricsScreen(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit
) {
    val currentSong by vm.currentSong.collectAsState()
    val currentPosition by vm.currentPosition.collectAsState()
    val duration by vm.duration.collectAsState()

    var isRealTime by remember { mutableStateOf(true) }
    var lyricsResult by remember { mutableStateOf<com.yt.lite.lyrics.LyricsResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White

    // Load lyrics when song changes
    LaunchedEffect(currentSong?.id) {
        val song = currentSong ?: return@LaunchedEffect
        isLoading = true
        hasError = false
        lyricsResult = null
        try {
            val result = LyricsRepository.getLyrics(
                title = song.title,
                artist = song.artist,
                duration = duration
            )
            lyricsResult = result
            hasError = result == null
        } catch (e: Exception) {
            hasError = true
        } finally {
            isLoading = false
        }
    }

    // Auto scroll to current line in real-time mode
    val currentLineIndex = remember(currentPosition, lyricsResult) {
        val lines = lyricsResult?.syncedLines ?: return@remember -1
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= currentPosition) idx = i
            else break
        }
        idx
    }

    LaunchedEffect(currentLineIndex) {
        if (isRealTime && currentLineIndex >= 0) {
            scope.launch {
                listState.animateScrollToItem(
                    (currentLineIndex - 2).coerceAtLeast(0)
                )
            }
        }
    }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.75f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .clickable(enabled = false) {}
            ) {
                Column(modifier = Modifier.fillMaxSize()) {

                    // Tabs — Real-time / Static
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Text(
                            text = "Real-time",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (isRealTime) Color.Red else Color.Gray,
                            modifier = Modifier.clickable { isRealTime = true }
                        )
                        Spacer(Modifier.width(24.dp))
                        Text(
                            text = "Static",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (!isRealTime) Color.Red else Color.Gray,
                            modifier = Modifier.clickable { isRealTime = false }
                        )
                    }

                    // Grey dashed divider
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    ) {
                        drawLine(
                            color = Color.Gray,
                            start = androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                            strokeWidth = 2f,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(12f, 8f), 0f
                            )
                        )
                    }

                    // Lyrics content
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    ) {
                        when {
                            isLoading -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = Color.Red)
                                }
                            }
                            hasError || lyricsResult == null -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Lyrics not found",
                                        fontFamily = NothingFont,
                                        fontSize = 16.sp,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            isRealTime && lyricsResult!!.hasSynced -> {
                                // Real-time synced lyrics
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(vertical = 16.dp)
                                ) {
                                    itemsIndexed(lyricsResult!!.syncedLines) { index, line ->
                                        val isCurrent = index == currentLineIndex
                                        Text(
                                            text = line.text.ifBlank { " " },
                                            fontFamily = NothingFont,
                                            fontWeight = if (isCurrent) FontWeight.Bold
                                            else FontWeight.Normal,
                                            fontSize = if (isCurrent) 18.sp else 15.sp,
                                            color = if (isCurrent) Color.Red else Color.Gray,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                            else -> {
                                // Static lyrics — plain text scrollable
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    val lines = lyricsResult!!.plainText.lines()
                                    itemsIndexed(lines) { _, line ->
                                        Text(
                                            text = line.ifBlank { " " },
                                            fontFamily = NothingFont,
                                            fontSize = 15.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Bottom bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(surfaceColor)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = textColor
                            )
                        }
                        Text(
                            text = "Powered by LRCLIB",
                            fontFamily = NothingFont,
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        Spacer(Modifier.width(48.dp))
                    }
                }
            }
        }
    }
}
