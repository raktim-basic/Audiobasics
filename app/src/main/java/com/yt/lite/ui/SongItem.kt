package com.yt.lite.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.yt.lite.data.Song
import com.yt.lite.ui.theme.NothingFont

@Composable
fun SongItem(
    song: Song,
    isDarkMode: Boolean,
    isLiked: Boolean,
    isInQueue: Boolean = false,
    onClick: () -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onLike: () -> Unit,
    onShare: () -> Unit,
    onRemoveFromQueue: (() -> Unit)? = null,
    onReorder: (() -> Unit)? = null,
    onRetryCache: (() -> Unit)? = null,
    onRemoveLike: (() -> Unit)? = null,
    showMenu: Boolean = !song.isAlbum || isInQueue
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var showBrokenHeartDialog by remember { mutableStateOf(false) }

    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White

    if (showBrokenHeartDialog) {
        BrokenHeartDialog(
            song = song,
            isDarkMode = isDarkMode,
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
            .clickable { onClick() }
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
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Artist line — show (e) tag before artist if explicit
            Text(
                text = buildAnnotatedString {
                    if (song.isExplicit) {
                        withStyle(
                            SpanStyle(
                                color = Color(0xFFAAAAAA),
                                fontFamily = NothingFont,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                background = if (isDarkMode)
                                    Color(0xFF333333) else Color(0xFFE0E0E0)
                            )
                        ) {
                            append(" e ")
                        }
                        withStyle(SpanStyle(color = subTextColor)) {
                            append("  ")
                        }
                    }
                    withStyle(
                        SpanStyle(
                            color = subTextColor,
                            fontFamily = NothingFont,
                            fontSize = 12.sp
                        )
                    ) {
                        append(song.artist)
                    }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Broken heart — only when liked and cache failed
        if (isLiked && song.cacheFailed) {
            IconButton(onClick = { showBrokenHeartDialog = true }) {
                Icon(
                    Icons.Default.HeartBroken,
                    contentDescription = "Cache failed",
                    tint = Color.Red,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 3 dots menu
        if (showMenu) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = subTextColor
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (isInQueue) {
                        DropdownMenuItem(
                            text = { Text("Share", fontFamily = NothingFont) },
                            onClick = {
                                menuExpanded = false
                                val i = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "https://www.youtube.com/watch?v=${song.id}"
                                    )
                                }
                                context.startActivity(Intent.createChooser(i, "Share song"))
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isLiked) "Unlike" else "Like",
                                    fontFamily = NothingFont
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (isLiked) Icons.Default.Favorite
                                    else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (isLiked) Color.Red else subTextColor
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onLike()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Reorder", fontFamily = NothingFont) },
                            onClick = {
                                menuExpanded = false
                                onReorder?.invoke()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Remove from queue",
                                    fontFamily = NothingFont,
                                    color = Color.Red
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onRemoveFromQueue?.invoke()
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Share", fontFamily = NothingFont) },
                            onClick = {
                                menuExpanded = false
                                val i = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "https://www.youtube.com/watch?v=${song.id}"
                                    )
                                }
                                context.startActivity(Intent.createChooser(i, "Share song"))
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isLiked) "Unlike" else "Like",
                                    fontFamily = NothingFont
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (isLiked) Icons.Default.Favorite
                                    else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (isLiked) Color.Red else subTextColor
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onLike()
                            }
                        )
                        onPlayNext?.let {
                            DropdownMenuItem(
                                text = { Text("Play next", fontFamily = NothingFont) },
                                onClick = {
                                    menuExpanded = false
                                    it()
                                }
                            )
                        }
                        onAddToQueue?.let {
                            DropdownMenuItem(
                                text = { Text("Add to queue", fontFamily = NothingFont) },
                                onClick = {
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

@Composable
fun BrokenHeartDialog(
    song: Song,
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onPlayOnline: () -> Unit,
    onRetryCache: () -> Unit,
    onRemoveLike: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(bgColor)
                .padding(20.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.HeartBroken,
                        contentDescription = null,
                        tint = Color.Red,
                        modifier = Modifier.size(24.dp)
                    )
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

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Red)
                        .clickable { onPlayOnline() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Play online",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isDarkMode) Color(0xFF333333) else Color(0xFFE0E0E0)
                        )
                        .clickable { onRetryCache() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Retry cache",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isDarkMode) Color(0xFF333333) else Color(0xFFE0E0E0)
                        )
                        .clickable { onRemoveLike() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Remove from liked",
                        fontFamily = NothingFont,
                        color = Color.Red
                    )
                }
            }
        }
    }
}
