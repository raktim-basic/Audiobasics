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
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.data.db.PlaylistEntity
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils
import kotlinx.coroutines.launch

/**
 * Tapping a row only SELECTS it. Nothing is added or removed until Done, which applies every
 * difference in one go and shows a spinner on the button until the last one is finished.
 * The dialog can't be dismissed by tapping outside it, and not at all while it is saving.
 */
@Composable
fun AddToPlaylistSheet(
    song: Song,
    vm: MusicViewModel,
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit
) {
    val likedSongs by vm.likedSongs.collectAsState()
    val customPlaylists by vm.customPlaylists.collectAsState()
    // Changes currently waiting on the account; their rows show a spinner too.
    val cloudBusy by vm.cloudBusy.collectAsState()
    val scope = rememberCoroutineScope()

    // What is true right now: which lists already contain the song.
    val containsMap = remember { mutableStateMapOf<String, Boolean>() }
    // What the user has ticked. Differences from containsMap are applied by Done.
    val selection = remember { mutableStateMapOf<String, Boolean>() }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)

    // Load initial state
    LaunchedEffect(song.id) {
        containsMap[MusicViewModel.LIKED_PLAYLIST_ID] = likedSongs.any { it.id == song.id }
        customPlaylists.forEach { pl ->
            containsMap[pl.id] = vm.isSongInPlaylistAsync(song.id, pl.id)
        }
        selection.clear()
        selection.putAll(containsMap)
        loaded = true
    }

    // Keep "what is true now" current (a like can land while the sheet is open)
    LaunchedEffect(likedSongs) {
        containsMap[MusicViewModel.LIKED_PLAYLIST_ID] = likedSongs.any { it.id == song.id }
    }

    MorphPopup(
        // Back can close it when idle, never while changes are being made.
        onDismissRequest = { if (!saving) onDismiss() },
        placement = MorphPlacement.Center,
        containerColor = bgColor,
        dismissOnOutsideTap = false
    ) { close ->
        Column(modifier = Modifier.fillMaxWidth()) {
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
                    PlaylistPickerRow(
                        emoji = null,
                        emojiIcon = "❤️",
                        label = "Liked songs",
                        isSelected = selection[MusicViewModel.LIKED_PLAYLIST_ID] == true,
                        isDarkMode = isDarkMode,
                        busy = "song:${song.id}" in cloudBusy,
                        onClick = {
                            if (loaded && !saving) {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                selection[MusicViewModel.LIKED_PLAYLIST_ID] =
                                    selection[MusicViewModel.LIKED_PLAYLIST_ID] != true
                            }
                        }
                    )
                }

                // Custom playlists
                items(customPlaylists) { pl ->
                    PlaylistPickerRow(
                        emoji = pl.emoji,
                        emojiIcon = null,
                        label = pl.name,
                        isSelected = selection[pl.id] == true,
                        isDarkMode = isDarkMode,
                        busy = "pl:${pl.id}:${song.id}" in cloudBusy,
                        onClick = {
                            if (loaded && !saving) {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                selection[pl.id] = selection[pl.id] != true
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
                TextButton(
                    enabled = !saving,
                    onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onCreateNew()
                    }
                ) {
                    Text(
                        "+ New playlist",
                        fontFamily = NothingFont,
                        color = textColor,
                        fontSize = 14.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            close()
                        }
                    ) {
                        Text("Cancel", fontFamily = NothingFont, color = textColor, fontSize = 14.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        enabled = loaded && !saving,
                        onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            // Everything whose ticked state differs from what is true now.
                            val wanted = selection.toMap()
                            val changes = wanted.filter { (id, want) -> (containsMap[id] == true) != want }.keys.toList()
                            if (changes.isEmpty()) {
                                close()
                            } else {
                                scope.launch {
                                    saving = true
                                    for (id in changes) {
                                        // Returns what is true afterwards, so a refused change
                                        // puts the row back to how it really is.
                                        val now = vm.toggleSongInPlaylist(song, id)
                                        containsMap[id] = now
                                        selection[id] = now
                                    }
                                    saving = false
                                    if (changes.all { containsMap[it] == wanted[it] }) close()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red,
                            disabledContainerColor = Color.Red.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text("Done", fontFamily = NothingFont, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistPickerRow(
    emoji: String?,
    emojiIcon: String?,
    label: String,
    isSelected: Boolean,
    isDarkMode: Boolean,
    busy: Boolean = false,
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
            color = textColor,
            modifier = Modifier.weight(1f)
        )

        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = textColor
            )
        }
    }
}
