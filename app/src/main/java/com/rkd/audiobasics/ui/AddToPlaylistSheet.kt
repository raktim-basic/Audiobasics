package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.data.db.PlaylistEntity
import com.rkd.audiobasics.ui.theme.NothingFont
import kotlinx.coroutines.launch

@Composable
fun AddToPlaylistSheet(
    song: Song,
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit
) {
    val likedSongs by vm.likedSongs.collectAsState()
    val customPlaylists by vm.customPlaylists.collectAsState()
    val scope = rememberCoroutineScope()

    // Track which playlists already contain the song
    val containsMap = remember { mutableStateMapOf<String, Boolean>() }

    // Remove confirmation state
    var removeConfirm by remember { mutableStateOf<Pair<String, String>?>(null) } // (playlistId, playlistName)

    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)

    // Load initial state
    LaunchedEffect(song.id) {
        containsMap[MusicViewModel.LIKED_PLAYLIST_ID] = likedSongs.any { it.id == song.id }
        customPlaylists.forEach { pl ->
            containsMap[pl.id] = vm.isSongInPlaylistAsync(song.id, pl.id)
        }
    }

    // Update liked state reactively
    LaunchedEffect(likedSongs) {
        containsMap[MusicViewModel.LIKED_PLAYLIST_ID] = likedSongs.any { it.id == song.id }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(bgColor)
        ) {
            // Title
            Text(
                text = "Add to...",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = textColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            )

            HorizontalDivider(color = subColor.copy(alpha = 0.2f))

            // Playlist list
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                // Liked songs row
                item {
                    val isIn = containsMap[MusicViewModel.LIKED_PLAYLIST_ID] == true
                    PlaylistPickerRow(
                        emoji = null,
                        emojiIcon = "❤️",
                        label = "Liked songs",
                        isSelected = isIn,
                        isDarkMode = isDarkMode,
                        onClick = {
                            if (isIn) {
                                removeConfirm = Pair(MusicViewModel.LIKED_PLAYLIST_ID, "Liked songs")
                            } else {
                                vm.toggleLike(song)
                                containsMap[MusicViewModel.LIKED_PLAYLIST_ID] = true
                            }
                        }
                    )
                }

                // Custom playlists
                items(customPlaylists) { pl ->
                    val isIn = containsMap[pl.id] == true
                    PlaylistPickerRow(
                        emoji = pl.emoji,
                        emojiIcon = null,
                        label = pl.name,
                        isSelected = isIn,
                        isDarkMode = isDarkMode,
                        onClick = {
                            if (isIn) {
                                removeConfirm = Pair(pl.id, pl.name)
                            } else {
                                scope.launch {
                                    val added = vm.toggleSongInPlaylist(song, pl.id)
                                    containsMap[pl.id] = added
                                }
                            }
                        }
                    )
                }
            }

            HorizontalDivider(color = subColor.copy(alpha = 0.2f))

            // Bottom row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onDismiss(); onCreateNew() }) {
                    Text(
                        "Create a new playlist",
                        fontFamily = NothingFont,
                        color = textColor,
                        fontSize = 14.sp
                    )
                }
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Done", fontFamily = NothingFont, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Remove confirmation dialog
    removeConfirm?.let { (playlistId, playlistName) ->
        AlertDialog(
            onDismissRequest = { removeConfirm = null },
            title = {
                Text(
                    "Are you sure?",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Remove \"${song.title}\" from \"$playlistName\"?",
                    fontFamily = NothingFont
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (playlistId == MusicViewModel.LIKED_PLAYLIST_ID) {
                        vm.toggleLike(song)
                        containsMap[MusicViewModel.LIKED_PLAYLIST_ID] = false
                    } else {
                        scope.launch {
                            vm.toggleSongInPlaylist(song, playlistId)
                            containsMap[playlistId] = false
                        }
                    }
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
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun PlaylistPickerRow(
    emoji: String?,
    emojiIcon: String?,
    label: String,
    isSelected: Boolean,
    isDarkMode: Boolean,
    onClick: () -> Unit
) {
    val textColor = if (isSelected) Color.Red else (if (isDarkMode) Color.White else Color.Black)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emoji ?: emojiIcon ?: "🎵",
                fontSize = 32.sp
            )
        }

        Spacer(Modifier.width(16.dp))

        Text(
            text = label,
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = textColor
        )
    }
}
