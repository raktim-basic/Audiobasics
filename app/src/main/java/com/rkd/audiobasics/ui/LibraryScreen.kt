package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.data.db.PlaylistEntity
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

@Composable
fun LibraryScreen(
    vm: MusicViewModel,
    onBack: () -> Unit,
    onNavigateLiked: () -> Unit,
    onNavigateAlbums: () -> Unit,
    onNavigatePlaylist: (PlaylistEntity) -> Unit
) {
    val context = LocalContext.current
    val isDarkMode by vm.isDarkMode.collectAsState()
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()
    val savedAlbums by vm.savedAlbums.collectAsState()
    val customPlaylists by vm.customPlaylists.collectAsState()
    val cacheSize by vm.cacheSize.collectAsState()

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // ── Header ─────────────────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
            val headerLabel = buildString {
                append("Your Library")
                if (cacheSize.isNotBlank()) append(" ($cacheSize)")
            }
            Text(
                text = headerLabel,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = textColor
            )
        }

        DashedDivider(modifier = Modifier.fillMaxWidth(), isDarkMode = isDarkMode)

        // ── List ───────────────────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Fixed: Liked Songs
            item {
                LibraryRow(
                    emoji = null,
                    icon = {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(40.dp)
                        )
                    },
                    label = "Liked songs (${likedSongs.size})",
                    isDarkMode = isDarkMode,
                    showMenu = false,
                    onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onNavigateLiked()
                    }
                )
            }

            // Fixed: Saved Albums
            item {
                LibraryRow(
                    emoji = null,
                    icon = {
                        Icon(
                            Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(40.dp)
                        )
                    },
                    label = "Saved albums (${savedAlbums.size})",
                    isDarkMode = isDarkMode,
                    showMenu = false,
                    onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onNavigateAlbums()
                    }
                )
            }

            // Custom playlists
            items(customPlaylists) { playlist ->
                LibraryRow(
                    emoji = playlist.emoji,
                    icon = null,
                    label = playlist.name,
                    isDarkMode = isDarkMode,
                    showMenu = true,
                    onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onNavigatePlaylist(playlist)
                    },
                    onRename = { renameTarget = playlist },
                    onDelete = { deleteTarget = playlist }
                )
            }
        }

        // ── Bottom bar ─────────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth().background(barColor)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
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
                    text = "🔍 (Library)",
                    fontFamily = NothingFont,
                    fontSize = 13.sp,
                    color = subTextColor
                )
                IconButton(onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    showCreateDialog = true
                }) {
                    Text(
                        text = "+",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = textColor
                    )
                }
            }
        }
    }

    // ── Create playlist dialog ─────────────────────────────────────────────
    if (showCreateDialog) {
        CreatePlaylistDialog(
            isDarkMode = isDarkMode,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, emoji ->
                vm.createPlaylist(name, emoji)
                showCreateDialog = false
            }
        )
    }

    // ── Rename dialog ──────────────────────────────────────────────────────
    renameTarget?.let { target ->
        CreatePlaylistDialog(
            isDarkMode = isDarkMode,
            initialName = target.name,
            initialEmoji = target.emoji,
            title = "Rename playlist",
            confirmLabel = "Save",
            onDismiss = { renameTarget = null },
            onCreate = { name, emoji ->
                vm.renamePlaylist(target.id, name, emoji)
                renameTarget = null
            }
        )
    }

    // ── Delete confirmation ────────────────────────────────────────────────
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${target.name}\"?", fontFamily = NothingFont) },
            text = { Text("This will remove the playlist and uncache any songs not saved elsewhere.", fontFamily = NothingFont) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePlaylist(target.id)
                    deleteTarget = null
                }) {
                    Text("Delete", color = Color.Red, fontFamily = NothingFont)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", fontFamily = NothingFont)
                }
            }
        )
    }
}

@Composable
private fun LibraryRow(
    emoji: String?,
    icon: (@Composable () -> Unit)?,
    label: String,
    isDarkMode: Boolean,
    showMenu: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Emoji or icon box
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)),
            contentAlignment = Alignment.Center
        ) {
            if (emoji != null) {
                Text(text = emoji, fontSize = 28.sp)
            } else {
                icon?.invoke()
            }
        }

        Spacer(Modifier.width(16.dp))

        Text(
            text = label,
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = textColor,
            modifier = Modifier.weight(1f)
        )

        if (showMenu) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = subTextColor)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename", fontFamily = NothingFont) },
                        onClick = { menuExpanded = false; onRename?.invoke() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = Color.Red, fontFamily = NothingFont) },
                        onClick = { menuExpanded = false; onDelete?.invoke() }
                    )
                }
            }
        }
    }
}
