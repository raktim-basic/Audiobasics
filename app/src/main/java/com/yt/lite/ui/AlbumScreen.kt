package com.yt.lite.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yt.lite.data.Album
import com.yt.lite.data.Song
import com.yt.lite.ui.theme.NothingFont

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
    val isSaved = vm.isAlbumSaved(album.id)

    var albumSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
            listState.firstVisibleItemScrollOffset > 100
        }
    }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White

    // Load album songs
    LaunchedEffect(album.id) {
        isLoading = true
        try {
            val (_, songs) = com.yt.lite.api.Innertube.getAlbumSongs(album.id)
            albumSongs = songs
        } catch (e: Exception) {
            android.util.Log.e("AlbumScreen", "Failed to load songs: ${e.message}")
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Collapsing header
        AnimatedVisibility(
            visible = !isScrolled,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Album info
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = album.thumbnail,
                        contentDescription = null,
                        modifier = Modifier
                            .size(110.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = album.title,
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = textColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = album.artist,
                            fontFamily = NothingFont,
                            fontSize = 14.sp,
                            color = subTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = formatTime(album.duration),
                            fontFamily = NothingFont,
                            fontSize = 13.sp,
                            color = subTextColor
                        )
                    }
                }

                // Action row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Save + Share
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Save button
                        Row(
                            modifier = Modifier
                                .clickable {
                                    if (isSaved) vm.unsaveAlbum(album)
                                    else vm.saveAlbum(album)
                                }
                                .padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSaved) Icons.Default.Bookmark
                                else Icons.Default.BookmarkBorder,
                                contentDescription = "Save",
                                tint = textColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isSaved) "Saved" else "Save",
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = textColor
                            )
                        }

                        // Share button
                        Row(
                            modifier = Modifier
                                .clickable {
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
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Share",
                                tint = textColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Share",
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = textColor
                            )
                        }
                    }

                    // Play button
                    Box(
                        modifier = Modifier
                            .border(2.dp, textColor, RoundedCornerShape(6.dp))
                            .clickable {
                                if (albumSongs.isNotEmpty()) {
                                    vm.playWithQueue(albumSongs.first(), albumSongs)
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = textColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Play",
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = textColor
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                DashedDivider(modifier = Modifier.fillMaxWidth())
            }
        }

        // Search bar
        if (isSearching) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .border(2.dp, Color.Red, RoundedCornerShape(8.dp)),
                placeholder = {
                    Text(
                        "Search in album...",
                        fontFamily = NothingFont,
                        color = Color.Gray
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = NothingFont,
                    color = textColor
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
                keyboardActions = KeyboardActions(onSearch = { })
            )
        }

        // Song list
        if (isLoading) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.Red)
            }
        } else if (filteredSongs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isBlank()) "No songs found" else "No results",
                    fontFamily = NothingFont,
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState
            ) {
                items(filteredSongs) { song ->
                    val isLiked = likedSongs.any { it.id == song.id }
                    SongItem(
                        song = song,
                        isDarkMode = isDarkMode,
                        isLiked = isLiked,
                        isInQueue = false,
                        onClick = { vm.playWithQueue(song, albumSongs) },
                        onAddToQueue = { vm.addToQueue(song) },
                        onPlayNext = { vm.playNext(song) },
                        onLike = { vm.toggleLike(song) },
                        onShare = {}
                    )
                }
            }
        }

        // Bottom bar
        AlbumScreenBottomBar(
            isDarkMode = isDarkMode,
            isSearching = isSearching,
            onBack = onBack,
            onSearchToggle = {
                isSearching = !isSearching
                if (!isSearching) searchQuery = ""
            },
            onQueue = onNavigateQueue
        )
    }
}

@Composable
fun AlbumScreenBottomBar(
    isDarkMode: Boolean,
    isSearching: Boolean,
    onBack: () -> Unit,
    onSearchToggle: () -> Unit,
    onQueue: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)
    val iconColor = if (isDarkMode) Color.White else Color.Black

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(vertical = 12.dp, horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSearching) Color.Red else Color.Transparent)
                .clickable { onSearchToggle() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search album",
                    tint = if (isSearching) Color.White else iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "(ALBUM)",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (isSearching) Color.White else iconColor
                )
            }
        }

        IconButton(onClick = onQueue) {
            Icon(
                Icons.Default.QueueMusic,
                contentDescription = "Queue",
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
