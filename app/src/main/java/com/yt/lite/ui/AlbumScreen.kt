package com.yt.lite.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yt.lite.data.Album
import com.yt.lite.data.Song
import com.yt.lite.ui.theme.NothingFont

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(
    vm: MusicViewModel,
    album: Album,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onNavigateQueue: () -> Unit
) {
    val context = LocalContext.current
    val likedSongs by vm.likedSongs.collectAsState()
    val currentSong by vm.currentSong.collectAsState()
    val isSaved by remember(vm.savedAlbums.collectAsState().value) {
        derivedStateOf { vm.isAlbumSaved(album.id) }
    }

    var albumSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    LaunchedEffect(album.id) {
        isLoading = true
        try {
            val (_, songs) = com.yt.lite.api.Innertube.getAlbumSongs(
                browseId = album.id,
                fallbackArtist = album.artist
            )
            albumSongs = songs
        } catch (e: Exception) {
            android.util.Log.e("AlbumScreen", "Failed to load: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    val filteredSongs = remember(albumSongs, searchQuery) {
        if (searchQuery.isBlank()) albumSongs
        else albumSongs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                    it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    // Scroll progress: 0 at top, 1 when last song is visible
    val totalRegularItems = filteredSongs.size + 1 // art (index 0) + songs
val scrollProgress = remember(listState, totalRegularItems) {
    derivedStateOf {
        if (totalRegularItems <= 1) return@derivedStateOf 0f
        val layoutInfo = listState.layoutInfo
        // Only consider regular items (indices < totalRegularItems) — ignore sticky header
        val visibleRegularIndices = layoutInfo.visibleItemsInfo.map { it.index }.filter { it < totalRegularItems }
        val maxVisibleIndex = visibleRegularIndices.maxOrNull() ?: 0
        (maxVisibleIndex.toFloat() / (totalRegularItems - 1).toFloat()).coerceIn(0f, 1f)
    }
}

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        if (isLoading) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.Red)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState
            ) {
                // Art + artist name
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(24.dp))
                        AsyncImage(
                            model = album.thumbnail,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "(Album) ${album.artist}",
                            fontFamily = NothingFont,
                            fontSize = 14.sp,
                            color = subTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // Sticky header
                stickyHeader {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = album.title,
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = textColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))

                            IconButton(onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        album.youtubeUrl.ifBlank {
                                            "https://www.youtube.com/playlist?list=${album.id}"
                                        }
                                    )
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Share album")
                                )
                            }) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Share",
                                    tint = textColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            IconButton(onClick = {
                                if (isSaved) vm.unsaveAlbum(album)
                                else vm.saveAlbum(album)
                            }) {
                                Icon(
                                    imageVector = if (isSaved) Icons.Default.Bookmark
                                    else Icons.Default.BookmarkBorder,
                                    contentDescription = "Save",
                                    tint = textColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            IconButton(onClick = {
                                if (albumSongs.isNotEmpty())
                                    vm.playWithQueue(albumSongs.first(), albumSongs)
                            }) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = textColor,
                                    modifier = Modifier.size(26.dp)
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

                if (filteredSongs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (searchQuery.isBlank()) "No songs found"
                                else "No results",
                                fontFamily = NothingFont,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    itemsIndexed(filteredSongs) { index, song ->
                        val isLiked = likedSongs.any { it.id == song.id }
                        val isPlaying = currentSong?.id == song.id
                        AlbumSongRow(
                            trackNumber = index + 1,
                            song = song,
                            isDarkMode = isDarkMode,
                            isLiked = isLiked,
                            isPlaying = isPlaying,
                            onClick = { vm.playWithQueue(song, albumSongs) },
                            onAddToQueue = { vm.addToQueue(song) },
                            onPlayNext = { vm.playNext(song) },
                            onLike = { vm.toggleLike(song) }
                        )
                    }
                }
            }
        }

        // Divider above bottom bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFDDDDDD))
        )

        // Bottom bar
        if (isSearching) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barColor)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Search in album...",
                            fontFamily = NothingFont,
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    },
                    textStyle = TextStyle(
                        fontFamily = NothingFont,
                        color = textColor,
                        fontSize = 14.sp
                    ),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Red,
                        unfocusedBorderColor = Color.Red,
                        focusedContainerColor = surfaceColor,
                        unfocusedContainerColor = surfaceColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                    })
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = {
                    isSearching = false
                    searchQuery = ""
                    focusManager.clearFocus()
                }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = textColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barColor)
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = textColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .clickable { isSearching = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "(ALBUM)",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = textColor
                    )
                }
                IconButton(onClick = onNavigateQueue) {
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = "Queue",
                        tint = textColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumSongRow(
    trackNumber: Int,
    song: Song,
    isDarkMode: Boolean,
    isLiked: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onAddToQueue: () -> Unit,
    onPlayNext: () -> Unit,
    onLike: () -> Unit
) {
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val titleColor = if (isPlaying) Color.Red else textColor
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = trackNumber.toString(),
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = if (isPlaying) Color.Red else subTextColor,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.Center
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
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.artist,
                fontFamily = NothingFont,
                fontSize = 12.sp,
                color = subTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

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
                DropdownMenuItem(
                    text = { Text(if (isLiked) "Unlike" else "Like", fontFamily = NothingFont) },
                    onClick = { menuExpanded = false; onLike() }
                )
                DropdownMenuItem(
                    text = { Text("Play next", fontFamily = NothingFont) },
                    onClick = { menuExpanded = false; onPlayNext() }
                )
                DropdownMenuItem(
                    text = { Text("Add to queue", fontFamily = NothingFont) },
                    onClick = { menuExpanded = false; onAddToQueue() }
                )
            }
        }
    }
}
