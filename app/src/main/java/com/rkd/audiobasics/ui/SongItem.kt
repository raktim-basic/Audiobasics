package com.rkd.audiobasics.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import coil.compose.AsyncImage
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

@Composable
fun SongItem(
    song: Song,
    isDarkMode: Boolean,
    isLiked: Boolean,
    isInQueue: Boolean = false,
    isPlaying: Boolean = false,
    showExplicit: Boolean = true,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onClick: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onLike: () -> Unit,
    onShare: () -> Unit,
    onRemoveFromQueue: (() -> Unit)? = null,
    onReorder: (() -> Unit)? = null,
    onRetryCache: (() -> Unit)? = null,
    onRemoveLike: (() -> Unit)? = null,
    onAddTo: (() -> Unit)? = null,     // new: opens Add to playlist sheet
    showMenu: Boolean = !song.isAlbum || isInQueue,
    isDragging: Boolean = false
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showBrokenHeartDialog by remember { mutableStateOf(false) }

    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val explicitBgColor = if (isDarkMode) Color(0xFF444444) else Color(0xFFDDDDDD)
    val explicitTextColor = if (isDarkMode) Color(0xFFCCCCCC) else Color(0xFF555555)

    val titleColor = if (isPlaying) Color.Red else textColor

    if (showBrokenHeartDialog) {
        BrokenHeartDialog(
            song = song,
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
            context = context,
            onDismiss = { showBrokenHeartDialog = false },
            onPlayOnline = {
                showBrokenHeartDialog = false
                onClick()
            },
            onRetryCache = {
                showBrokenHeartDialog = false
                onRetryCache?.invoke()
            },
            onRemoveLike = {
                showBrokenHeartDialog = false
                onRemoveLike?.invoke()
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.thumbnail,
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(3.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showExplicit && song.isExplicit) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(explicitBgColor)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "E",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = explicitTextColor
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = song.artist,
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = subTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 💔 cache-failed indicator (only when liked)
        if (isLiked && song.cacheFailed) {
            Text(
                text = "💔",
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        showBrokenHeartDialog = true
                    }
                    .padding(8.dp)
            )
        }

        if (showMenu) {
            Box {
                IconButton(onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    menuExpanded = true
                }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = subTextColor)
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (isInQueue) {
                        DropdownMenuItem(
                            text = { Text("Share", fontFamily = NothingFont) },
                            onClick = {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                menuExpanded = false
                                shareYouTube(context, song.id)
                            }
                        )
                        // Add to playlist or Like
                        if (onAddTo != null) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                text = { Text("Add to playlist...", fontFamily = NothingFont) },
                                onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    menuExpanded = false
                                    onAddTo()
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(if (isLiked) "Unlike" else "Like", fontFamily = NothingFont) },
                                leadingIcon = {
                                    Icon(
                                        if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = null,
                                        tint = if (isLiked) Color.Red else subTextColor
                                    )
                                },
                                onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    menuExpanded = false
                                    onLike()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (isDragging) "Cancel reorder" else "Reorder", fontFamily = NothingFont) },
                            onClick = {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                menuExpanded = false
                                onReorder?.invoke()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Remove from queue", fontFamily = NothingFont, color = Color.Red) },
                            onClick = {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                menuExpanded = false
                                onRemoveFromQueue?.invoke()
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Share", fontFamily = NothingFont) },
                            onClick = {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                menuExpanded = false
                                shareYouTube(context, song.id)
                            }
                        )
                        // Add to playlist or Like
                        if (onAddTo != null) {
                            DropdownMenuItem(
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                text = { Text("Add to playlist...", fontFamily = NothingFont) },
                                onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    menuExpanded = false
                                    onAddTo()
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(if (isLiked) "Unlike" else "Like", fontFamily = NothingFont) },
                                leadingIcon = {
                                    Icon(
                                        if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = null,
                                        tint = if (isLiked) Color.Red else subTextColor
                                    )
                                },
                                onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    menuExpanded = false
                                    onLike()
                                }
                            )
                        }
                        onPlayNext?.let {
                            DropdownMenuItem(
                                text = { Text("Play next", fontFamily = NothingFont) },
                                onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    menuExpanded = false
                                    it()
                                }
                            )
                        }
                        onAddToQueue?.let {
                            DropdownMenuItem(
                                text = { Text("Add to queue", fontFamily = NothingFont) },
                                onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    menuExpanded = false
                                    it()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun shareYouTube(context: android.content.Context, songId: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$songId")
    }
    context.startActivity(Intent.createChooser(i, "Share song"))
}

@Composable
fun BrokenHeartDialog(
    song: Song,
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onPlayOnline: () -> Unit,
    onRetryCache: () -> Unit,
    onRemoveLike: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black

    Dialog(onDismissRequest = {
        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
        onDismiss()
    }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(bgColor)
                .padding(20.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("💔", fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Cache failed",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = textColor
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = song.title,
                    fontFamily = NothingFont,
                    fontSize = 13.sp,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(16.dp))
                listOf(
                    Triple("Play online", Color.Red, onPlayOnline),
                    Triple("Retry cache", if (isDarkMode) Color(0xFF333333) else Color(0xFFE0E0E0), onRetryCache),
                    Triple("Remove from liked", if (isDarkMode) Color(0xFF333333) else Color(0xFFE0E0E0), onRemoveLike)
                ).forEach { (label, bg, action) ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(bg)
                            .clickable {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                action()
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            color = if (bg == Color.Red) Color.White else
                                if (label == "Remove from liked") Color.Red else textColor
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
