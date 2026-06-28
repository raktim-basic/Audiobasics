package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.data.db.PlaylistEntity
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

@Composable
fun CustomPlaylistScreen(
    vm: MusicViewModel,
    playlist: PlaylistEntity,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onAddTo: (Song) -> Unit
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()
    val currentSong by vm.currentSong.collectAsState()
    val playlistSongs by vm.openPlaylistSongs.collectAsState()

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    var removeConfirm by remember { mutableStateOf<Song?>(null) }

    LaunchedEffect(playlist.id) {
        vm.loadPlaylistSongs(playlist.id)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Header
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            Text(
                text = "${playlist.emoji} ${playlist.name}",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = textColor
            )
            Text(
                text = "${playlistSongs.size} songs",
                fontFamily = NothingFont,
                fontSize = 14.sp,
                color = subTextColor
            )
        }

        DashedDivider(modifier = Modifier.fillMaxWidth(), isDarkMode = isDarkMode)

        // Songs
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (playlistSongs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No songs yet. Tap + on any song to add.", fontFamily = NothingFont, color = Color.Gray, fontSize = 15.sp)
                    }
                }
            } else {
                itemsIndexed(playlistSongs) { _, entity ->
                    val song = Song(
                        id = entity.songId,
                        title = entity.title,
                        artist = entity.artist,
                        thumbnail = entity.thumbnail,
                        isExplicit = entity.isExplicit
                    )
                    SongItem(
                        song = song,
                        isDarkMode = isDarkMode,
                        isLiked = likedSongs.any { it.id == song.id },
                        isPlaying = currentSong?.id == song.id,
                        hapticsEnabled = hapticsEnabled,
                        context = context,
                        onClick = {
                            val queue = playlistSongs.map {
                                Song(id = it.songId, title = it.title, artist = it.artist, thumbnail = it.thumbnail)
                            }
                            vm.playWithQueue(song, queue)
                        },
                        onLike = { vm.toggleLike(song) },
                        onShare = {},
                        onAddToQueue = { vm.addToQueue(song) },
                        onPlayNext = { vm.playNext(song) },
                        onAddTo = { onAddTo(song) },
                        onRemoveLike = { removeConfirm = song }
                    )
                }
            }
        }

        // Bottom bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onBack()
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
            }
            Text(
                text = "🔍 (${playlist.name})",
                fontFamily = NothingFont,
                fontSize = 13.sp,
                color = subTextColor
            )
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                vm.shuffleCustomPlaylist(playlist.id)
            }) {
                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = textColor)
            }
        }
    }

    // Remove confirmation
    removeConfirm?.let { song ->
        AlertDialog(
            onDismissRequest = { removeConfirm = null },
            title = {
                Text("Are you sure?", fontFamily = NothingFont, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Remove \"${song.title}\" from \"${playlist.name}\"?",
                    fontFamily = NothingFont
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeSongFromCustomPlaylist(playlist.id, song)
                    removeConfirm = null
                }) {
                    Text("Yes", fontFamily = NothingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirm = null }) {
                    Text("No", fontFamily = NothingFont, fontSize = 18.sp)
                }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
        )
    }
}
