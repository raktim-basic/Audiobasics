package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import coil.compose.AsyncImage
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

/**
 * Shown after a YouTube link is shared into Audiobasics. Drawn as a morph popup (like every
 * other popup), with morph menus on the 3-dot buttons. The popup's content is created once,
 * when it opens, but this dialog opens while the link is still loading — so the values below
 * are read through State, and the card fills in by itself once the song and matches arrive.
 */
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
    val loadingNow by rememberUpdatedState(loading)
    val linkSongNow by rememberUpdatedState(linkSong)
    val matchesNow by rememberUpdatedState(matches)
    val onPlayLinkSongNow by rememberUpdatedState(onPlayLinkSong)
    val onAddToSheetNow by rememberUpdatedState(onAddToSheet)

    MorphPopup(
        onDismissRequest = {
            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
            onDismiss()
        },
        placement = MorphPlacement.Center,
        containerColor = bgColor
    ) { close ->
        SmartInputsContent(
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
            context = context,
            loading = loadingNow,
            linkSong = linkSongNow,
            matches = matchesNow,
            vm = vm,
            onDismiss = close,
            onPlayLinkSong = { onPlayLinkSongNow() },
            onAddToSheet = { onAddToSheetNow(it) }
        )
    }
}

@Composable
private fun SmartInputsContent(
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
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)

    Box(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
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

                    val overlay = LocalMorphOverlay.current
                    val linkMenuAnchor = rememberMorphAnchor()
                    IconButton(
                        onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            overlay.show(
                                anchor = linkMenuAnchor.bounds(),
                                placement = MorphPlacement.Anchored
                            ) { close ->
                                MorphMenuColumn {
                                    MorphMenuItem(
                                        text = "Add to queue",
                                        onClick = {
                                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                            close()
                                            vm.addToQueue(linkSong)
                                        }
                                    )
                                    MorphMenuItem(
                                        text = "Play next",
                                        onClick = {
                                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                            close()
                                            vm.playNext(linkSong)
                                        }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.morphAnchor(linkMenuAnchor)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = textColor)
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
    val overlay = LocalMorphOverlay.current
    val menuAnchor = rememberMorphAnchor()

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

        IconButton(
            onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                overlay.show(
                    anchor = menuAnchor.bounds(),
                    placement = MorphPlacement.Anchored
                ) { close ->
                    MorphMenuColumn {
                        MorphMenuItem(
                            text = "Add to playlist",
                            onClick = {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                close()
                                onAddToPlaylist()
                            }
                        )
                        MorphMenuItem(
                            text = "Play next",
                            onClick = {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                close()
                                onPlayNext()
                            }
                        )
                        MorphMenuItem(
                            text = "Add to queue",
                            onClick = {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                close()
                                onAddToQueue()
                            }
                        )
                    }
                }
            },
            modifier = Modifier.morphAnchor(menuAnchor)
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = textColor)
        }
    }
}
