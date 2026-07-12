package com.rkd.audiobasics.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

@Composable
fun PlayerDialog(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onNavigateQueue: () -> Unit,
    onNavigateArtist: (String) -> Unit,
    onNavigateAlbum: (String) -> Unit
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val song by vm.currentSong.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val position by vm.currentPosition.collectAsState()
    val duration by vm.duration.collectAsState()
    val savedAlbums by vm.savedAlbums.collectAsState()
    val customPlaylists by vm.customPlaylists.collectAsState()
    val resolvedAlbumCache by vm.resolvedAlbumCache.collectAsState()
    val sleepTimerMode by vm.sleepTimerMode.collectAsState()
    val sleepTimerRemaining by vm.sleepTimerRemaining.collectAsState()
    val repeatMode by vm.repeatMode.collectAsState()

    var showLyrics by remember { mutableStateOf(false) }
    var showSongInfo by remember { mutableStateOf(false) }
    var showAddToSheet by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showThreeDotMenu by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf<Long?>(null) }

    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF2A2A2A) else Color.White
    val subTextColor = if (isDarkMode) Color(0xFF888888) else Color(0xFF666666)

    if (showLyrics) {
        LyricsScreen(vm = vm, isDarkMode = isDarkMode, onBack = { showLyrics = false })
        return
    }

    Dialog(
        onDismissRequest = { onDismiss() },
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
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .clickable(enabled = false) {}
            ) {
                Column {
                    // ── Top bar ─────────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Speaker/cast (placeholder)
                        IconButton(onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.SpeakerGroup, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
                        }

                        // Song info
                        IconButton(onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            showSongInfo = true
                        }) {
                            Icon(Icons.Default.Info, contentDescription = "Song info", tint = textColor, modifier = Modifier.size(20.dp))
                        }

                        // Close
                        IconButton(onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            onDismiss()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = textColor, modifier = Modifier.size(20.dp))
                        }
                    }

                    // ── Artwork + title ──────────────────────────────────
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    ) {
                        AsyncImage(
                            model = song?.thumbnail,
                            contentDescription = null,
                            modifier = Modifier
                                .size(110.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song?.title ?: "Song Name",
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = textColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = song?.artist ?: "Artist",
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = subTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // ── Progress bar ─────────────────────────────────────
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
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = textColor
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            DashedProgressBar(
                                progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                                onSeek = { seekProgress ->
                                    val newPos = (seekProgress * duration).toLong()
                                    vm.seekTo(newPos)
                                    dragPosition = null
                                },
                                onDragging = { dragProgress ->
                                    dragPosition = (dragProgress * duration).toLong()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                hapticsEnabled = hapticsEnabled,
                                context = context,
                                isDarkMode = isDarkMode
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatTime(duration),
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = textColor
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // ── Playback controls ────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            vm.skipToPrevious()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Previous", tint = textColor, modifier = Modifier.size(36.dp))
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(surfaceColor)
                                .clickable {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    vm.togglePlayPause()
                                }
                                .padding(horizontal = 32.dp, vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = textColor)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = textColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (isPlaying) "Pause" else "Play",
                                        fontFamily = NothingFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = textColor
                                    )
                                }
                            }
                        }

                        IconButton(onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            vm.skipToNext()
                        }) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "Next", tint = textColor, modifier = Modifier.size(36.dp))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── Bottom bar ───────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(surfaceColor)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // + Add to playlist
                        IconButton(onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            showAddToSheet = true
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add to playlist", tint = textColor, modifier = Modifier.size(26.dp))
                        }

                        // Lyrics
                        Text(
                            text = "LYRICS",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = textColor,
                            modifier = Modifier.clickable {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                showLyrics = true
                            }
                        )

                        // 3-dot dropdown
                        Box {
                            IconButton(onClick = {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                showThreeDotMenu = true
                            }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = textColor, modifier = Modifier.size(26.dp))
                            }

                            DropdownMenu(
                                expanded = showThreeDotMenu,
                                onDismissRequest = { showThreeDotMenu = false }
                            ) {
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    text = { Text("Share", fontFamily = NothingFont) },
                                    onClick = {
                                        showThreeDotMenu = false
                                        song?.let { s ->
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=${s.id}")
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share song"))
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                                    text = { Text("Queue", fontFamily = NothingFont) },
                                    onClick = {
                                        showThreeDotMenu = false
                                        onDismiss()
                                        onNavigateQueue()
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Bedtime,
                                            contentDescription = null,
                                            tint = if (sleepTimerMode != MusicViewModel.SLEEP_TIMER_OFF) Color.Red else LocalContentColor.current
                                        )
                                    },
                                    text = {
                                        Text(
                                            text = when (sleepTimerMode) {
                                                MusicViewModel.SLEEP_TIMER_END_OF_SONG -> "Sleep timer (end of this song)"
                                                MusicViewModel.SLEEP_TIMER_CUSTOM -> "Sleep timer (${formatCountdown(sleepTimerRemaining)})"
                                                else -> "Sleep timer"
                                            },
                                            fontFamily = NothingFont,
                                            color = if (sleepTimerMode != MusicViewModel.SLEEP_TIMER_OFF) Color.Red else Color.Unspecified
                                        )
                                    },
                                    onClick = {
                                        showThreeDotMenu = false
                                        onDismiss()
                                        onNavigateQueue()
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            if (repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                            contentDescription = null,
                                            tint = if (repeatMode == 0) LocalContentColor.current else Color.Red
                                        )
                                    },
                                    text = {
                                        Text(
                                            text = when (repeatMode) {
                                                1 -> "Repeat list"
                                                2 -> "Repeat one"
                                                else -> "Repeat"
                                            },
                                            fontFamily = NothingFont,
                                            color = if (repeatMode == 0) Color.Unspecified else Color.Red
                                        )
                                    },
                                    onClick = {
                                        showThreeDotMenu = false
                                        onDismiss()
                                        onNavigateQueue()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Add to playlist sheet ──────────────────────────────────────────────
    if (showAddToSheet && song != null) {
        AddToPlaylistSheet(
            song = song!!,
            vm = vm,
            isDarkMode = isDarkMode,
            onDismiss = { showAddToSheet = false },
            onCreateNew = { showCreatePlaylist = true }
        )
    }

    // ── Create playlist dialog ─────────────────────────────────────────────
    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            isDarkMode = isDarkMode,
            existingNames = customPlaylists.map { it.name },
            onDismiss = { showCreatePlaylist = false },
            onCreate = { name, emoji ->
                vm.createPlaylist(name, emoji)
                showCreatePlaylist = false
            }
        )
    }

    // ── Song info screen ───────────────────────────────────────────────────
    if (showSongInfo && song != null) {
        SongInfoScreen(
            song = song!!,
            isDarkMode = isDarkMode,
            context = context,
            savedAlbums = savedAlbums,
            resolvedAlbumCache = resolvedAlbumCache,
            onCacheResolvedAlbum = { vm.cacheResolvedAlbum(it) },
            onDismiss = { showSongInfo = false },
            onArtistClick = { artistName ->
                showSongInfo = false
                onDismiss()
                onNavigateArtist(artistName)
            },
            onAlbumClick = { albumTitle ->
                showSongInfo = false
                onDismiss()
                onNavigateAlbum(albumTitle)
            }
        )
    }
}

@Composable
fun DashedProgressBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    onDragging: (Float) -> Unit,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    isDarkMode: Boolean = true
) {
    val totalDashes = 30
    var barWidthPx by remember { mutableStateOf(0f) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }

    val displayProgress = dragProgress ?: progress
    val displayFilled = (displayProgress * totalDashes).toInt()
    var lastFilled by remember { mutableStateOf(displayFilled) }

    LaunchedEffect(displayFilled) {
        if (dragProgress != null && displayFilled != lastFilled && hapticsEnabled) {
            HapticUtils.performSubtleHaptic(context)
            lastFilled = displayFilled
        }
    }

    val unfilledColor = if (isDarkMode) Color(0xFF333333) else Color(0xFFBDBDBD)

    Row(
        modifier = modifier
            .height(24.dp)
            .onSizeChanged { barWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (barWidthPx > 0) {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            dragProgress = (offset.x / barWidthPx).coerceIn(0f, 1f)
                            lastFilled = (dragProgress!! * totalDashes).toInt()
                            onDragging(dragProgress!!)
                        }
                    },
                    onDragEnd = {
                        dragProgress?.let { onSeek(it) }
                        dragProgress = null
                    },
                    onDragCancel = { dragProgress = null },
                    onHorizontalDrag = { _, dragAmount ->
                        if (barWidthPx > 0) {
                            val current = dragProgress ?: progress
                            dragProgress = (current + dragAmount / barWidthPx).coerceIn(0f, 1f)
                            onDragging(dragProgress!!)
                        }
                    }
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(totalDashes) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index < displayFilled) Color(0xFFFF0000) else unfilledColor
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onSeek((index + 1).toFloat() / totalDashes.toFloat())
                    }
            )
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
