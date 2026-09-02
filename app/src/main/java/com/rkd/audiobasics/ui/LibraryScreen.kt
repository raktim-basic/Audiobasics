package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.data.db.PlaylistEntity
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private fun <T> MutableList<T>.move(fromIndex: Int, toIndex: Int) {
    add(toIndex, removeAt(fromIndex))
}

@Composable
fun LibraryScreen(
    vm: MusicViewModel,
    onBack: () -> Unit,
    onNavigateLiked: () -> Unit,
    onNavigateAlbums: () -> Unit,
    onNavigatePlaylist: (PlaylistEntity) -> Unit,
    onNavigateQueue: () -> Unit = {}
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
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White

    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(isSearching) {
        if (isSearching) focusRequester.requestFocus()
    }

    // Filter: custom playlists by name; fixed rows always shown unless filtered out
    val filteredCustom = remember(customPlaylists, searchQuery) {
        if (searchQuery.isBlank()) customPlaylists
        else customPlaylists.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }
    val showLiked = searchQuery.isBlank() || "liked songs".contains(searchQuery, ignoreCase = true)
    val showAlbums = searchQuery.isBlank() || "saved albums".contains(searchQuery, ignoreCase = true)

    // Reordering only makes sense against the full, unfiltered list — while searching, the
    // custom-playlist rows use filteredCustom read-only below instead.
    val reorderEnabled = searchQuery.isBlank()

    var draggingIndex by remember { mutableStateOf<Int?>(null) }

    // Local, live-editable copy of the custom playlists, mirroring QueueScreen's pattern:
    // dragging an armed row reorders this list instantly, and it's committed to the
    // ViewModel/DB only once the finger lifts.
    val livePlaylists = remember { mutableStateListOf<PlaylistEntity>().apply { addAll(customPlaylists) } }
    LaunchedEffect(customPlaylists) {
        livePlaylists.clear()
        livePlaylists.addAll(customPlaylists)
    }

    val listState = rememberLazyListState()
    val totalItems = remember(showLiked, showAlbums, filteredCustom) {
        (if (showLiked) 1 else 0) + (if (showAlbums) 1 else 0) + filteredCustom.size
    }
    val scrollProgress = remember(listState) {
        derivedStateOf {
            if (totalItems <= 1) return@derivedStateOf 0f
            val max = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            (max.toFloat() / totalItems).coerceIn(0f, 1f)
        }
    }

    // Position of the first custom-playlist row within the LazyColumn: 1 sticky header +
    // Liked Songs + Saved Albums (both always shown when reorder is enabled, since it's only
    // enabled while unfiltered). Playlists can never be dragged above this boundary.
    val playlistOffset = 1 + (if (showLiked) 1 else 0) + (if (showAlbums) 1 else 0)

    var pendingReorder by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val fromLocal = from.index - playlistOffset
        val toLocal = (to.index - playlistOffset).coerceIn(0, livePlaylists.size - 1)
        if (fromLocal < 0 || fromLocal >= livePlaylists.size) return@rememberReorderableLazyListState
        val current = pendingReorder
        pendingReorder = if (current == null) fromLocal to toLocal else current.first to toLocal
        livePlaylists.move(fromLocal, toLocal)
        draggingIndex = toLocal
    }

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            pendingReorder?.let { (from, to) ->
                if (from != to) vm.reorderPlaylists(from, to)
            }
            pendingReorder = null
            draggingIndex = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(bgColor)
    ) {
        // ── List ───────────────────────────────────────────────────────────
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), state = listState) {
            stickyHeader {
                Column(modifier = Modifier.fillMaxWidth().background(bgColor)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Your Library",
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                color = textColor
                            )
                            if (draggingIndex != null) {
                                Text(
                                    text = "Drag and drop",
                                    fontFamily = NothingFont,
                                    fontSize = 14.sp,
                                    color = Color.Gray
                                )
                            } else if (cacheSize.isNotBlank()) {
                                Text(
                                    text = cacheSize,
                                    fontFamily = NothingFont,
                                    fontSize = 14.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                        // + button in header
                        IconButton(onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            showCreateDialog = true
                        }) {
                            Text(
                                text = "+",
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp,
                                color = textColor
                            )
                        }
                    }

                    DashedDivider(
                        modifier = Modifier.fillMaxWidth(),
                        isDarkMode = isDarkMode,
                        scrollProgress = scrollProgress.value
                    )
                }
            }

            if (showLiked) {
                item {
                    LibraryRow(
                        emoji = null,
                        icon = {
                            Icon(Icons.Default.Favorite, contentDescription = null,
                                tint = Color.Red, modifier = Modifier.size(40.dp))
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
            }

            if (showAlbums) {
                item {
                    LibraryRow(
                        emoji = null,
                        icon = {
                            Icon(Icons.Default.Bookmark, contentDescription = null,
                                tint = textColor, modifier = Modifier.size(40.dp))
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
            }

            val displayedPlaylists = if (reorderEnabled) livePlaylists else filteredCustom
            itemsIndexed(
                items = displayedPlaylists,
                key = { _, playlist -> playlist.id }
            ) { index, playlist ->
                val armed = reorderEnabled && draggingIndex == index

                if (reorderEnabled) {
                    ReorderableItem(
                        state = reorderableState,
                        key = playlist.id
                    ) { isActivelyDragging ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .graphicsLayer {
                                    val s = if (isActivelyDragging) 1.02f else 1f
                                    scaleX = s
                                    scaleY = s
                                    alpha = if (isActivelyDragging) 0.9f else 1f
                                }
                                .then(
                                    if (armed) {
                                        Modifier.longPressDraggableHandle(
                                            onDragStarted = {
                                                HapticUtils.performStrongHaptic(context)
                                            }
                                        )
                                    } else Modifier
                                )
                                .background(
                                    when {
                                        isActivelyDragging -> if (isDarkMode)
                                            Color.White.copy(alpha = 0.1f)
                                        else Color.Black.copy(alpha = 0.06f)
                                        armed -> if (isDarkMode)
                                            Color.White.copy(alpha = 0.06f)
                                        else Color.Black.copy(alpha = 0.05f)
                                        else -> Color.Transparent
                                    }
                                )
                        ) {
                            LibraryRow(
                                emoji = playlist.emoji,
                                icon = null,
                                label = playlist.name,
                                isDarkMode = isDarkMode,
                                showMenu = true,
                                onClick = {
                                    // While any playlist is armed for reorder, taps must not
                                    // navigate — a press-and-release-without-moving can
                                    // otherwise race the drag gesture and fire as a normal click.
                                    if (draggingIndex == null) {
                                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                        onNavigatePlaylist(playlist)
                                    }
                                },
                                onRename = { renameTarget = playlist },
                                onDelete = { deleteTarget = playlist },
                                isDragging = armed,
                                onReorder = {
                                    draggingIndex = if (armed) null else index
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                }
                            )
                        }
                    }
                } else {
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
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp)
            .background(if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFDDDDDD)))

        // ── Bottom bar ─────────────────────────────────────────────────────
        if (isSearching) {
            Row(
                modifier = Modifier.fillMaxWidth().background(barColor)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = {
                        Text("Search library...", fontFamily = NothingFont,
                            color = Color.Gray, fontSize = 14.sp)
                    },
                    textStyle = TextStyle(fontFamily = NothingFont, color = textColor, fontSize = 14.sp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Red,
                        unfocusedBorderColor = Color.Red,
                        focusedContainerColor = surfaceColor,
                        unfocusedContainerColor = surfaceColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    isSearching = false
                    searchQuery = ""
                    focusManager.clearFocus()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel",
                        tint = textColor, modifier = Modifier.size(24.dp))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().background(barColor)
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    onBack()
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                        tint = textColor, modifier = Modifier.size(26.dp))
                }
                Row(
                    modifier = Modifier
                        .clickable {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            isSearching = true
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Search",
                        tint = textColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("(Library)", fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, color = textColor)
                }
                IconButton(onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    onNavigateQueue()
                }) {
                    Icon(Icons.Default.QueueMusic, contentDescription = "Queue",
                        tint = textColor, modifier = Modifier.size(26.dp))
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(isDarkMode = isDarkMode,
            existingNames = customPlaylists.map { it.name },
            onDismiss = { showCreateDialog = false },
            onCreate = { name, emoji -> vm.createPlaylist(name, emoji); showCreateDialog = false })
    }
    renameTarget?.let { target ->
        CreatePlaylistDialog(isDarkMode = isDarkMode, initialName = target.name,
            initialEmoji = target.emoji, title = "Rename playlist", confirmLabel = "Save",
            existingNames = customPlaylists.filter { it.id != target.id }.map { it.name },
            onDismiss = { renameTarget = null },
            onCreate = { name, emoji -> vm.renamePlaylist(target.id, name, emoji); renameTarget = null })
    }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${target.name}\"?", fontFamily = NothingFont) },
            text = { Text("Are you sure you want to remove the songs in the playlist?", fontFamily = NothingFont) },
            confirmButton = {
                TextButton(onClick = { vm.deletePlaylist(target.id); deleteTarget = null }) {
                    Text("Delete", color = Color.Red, fontFamily = NothingFont)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", fontFamily = NothingFont)
                }
            })
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
    onDelete: (() -> Unit)? = null,
    isDragging: Boolean = false,
    onReorder: (() -> Unit)? = null
) {
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isDragging) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SwapVert,
                    contentDescription = "Reordering",
                    tint = textColor,
                    modifier = Modifier.size(30.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)),
                contentAlignment = Alignment.Center
            ) {
                if (emoji != null) Text(text = emoji, fontSize = 28.sp)
                else icon?.invoke()
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(text = label, fontFamily = NothingFont, fontWeight = FontWeight.Bold,
            fontSize = 16.sp, color = textColor, modifier = Modifier.weight(1f))
        if (showMenu) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = subTextColor)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Rename", fontFamily = NothingFont) },
                        onClick = { menuExpanded = false; onRename?.invoke() })
                    if (onReorder != null) {
                        DropdownMenuItem(
                            text = { Text(if (isDragging) "Cancel reorder" else "Reorder", fontFamily = NothingFont) },
                            onClick = { menuExpanded = false; onReorder.invoke() })
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = Color.Red, fontFamily = NothingFont) },
                        onClick = { menuExpanded = false; onDelete?.invoke() })
                }
            }
        }
    }
}
