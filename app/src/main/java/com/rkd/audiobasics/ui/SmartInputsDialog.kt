package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

@Composable
fun SmartInputsDialog(
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    loading: Boolean,
    linkSong: Song?,
    matches: List<Song>,
    vm: MusicViewModel,
    onDismiss: () -> Unit,
    onPlayLinkSong: () -> Unit,
    onAddToSheet: (Song) -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)

    Dialog(
        onDismissRequest = {
            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(bgColor, RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            if (loading || linkSong == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.Red)
                }
                return@Box
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "From the link",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = subTextColor,
                        modifier = Modifier.weight(1f)
                    )

                    var linkMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            linkMenuExpanded = true
                        }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = textColor)
                        }
                        DropdownMenu(
                            expanded = linkMenuExpanded,
                            onDismissRequest = { linkMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Add to queue", fontFamily = NothingFont) },
                                onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    linkMenuExpanded = false
                                    vm.addToQueue(linkSong)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Play next", fontFamily = NothingFont) },
                                onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    linkMenuExpanded = false
                                    vm.playNext(linkSong)
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = linkSong.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = linkSong.title,
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = textColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (linkSong.artist.isNotBlank()) {
                            Text(
                                text = linkSong.artist,
                                fontSize = 14.sp,
                                color = subTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .clickable {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                onAddToSheet(linkSong)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add to playlist", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = textColor)
                    }

                    Box(
                        modifier = Modifier
                            .background(Color.Red, RoundedCornerShape(10.dp))
                            .clickable {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                onPlayLinkSong()
                            }
                            .padding(horizontal = 28.dp, vertical = 12.dp)
                    ) {
                        Text("Play", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                if (matches.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    StaticDashedDivider(modifier = Modifier.fillMaxWidth(), isDarkMode = isDarkMode)
                    Spacer(Modifier.height(4.dp))

                    Column {
                        matches.forEach { match ->
                            SmartInputMatchRow(
                                song = match,
                                isDarkMode = isDarkMode,
                                hapticsEnabled = hapticsEnabled,
                                context = context,
                                onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    vm.play(match)
                                    onDismiss()
                                },
                                onAddToPlaylist = { onAddToSheet(match) },
                                onPlayNext = { vm.playNext(match) },
                                onAddToQueue = { vm.addToQueue(match) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartInputMatchRow(
    song: Song,
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit
) {
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                fontSize = 13.sp,
                color = subTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box {
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                menuExpanded = true
            }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = textColor)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add to playlist", fontFamily = NothingFont) },
                    onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        menuExpanded = false
                        onAddToPlaylist()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Play next", fontFamily = NothingFont) },
                    onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        menuExpanded = false
                        onPlayNext()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Add to queue", fontFamily = NothingFont) },
                    onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        menuExpanded = false
                        onAddToQueue()
                    }
                )
            }
        }
    }
}
