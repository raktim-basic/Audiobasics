package com.yt.lite.ui

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
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yt.lite.data.Album
import com.yt.lite.ui.theme.NothingFont

@Composable
fun SavedAlbumsScreen(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onNavigateQueue: () -> Unit,
    onAlbumClick: (Album) -> Unit
) {
    val savedAlbums by vm.savedAlbums.collectAsState()

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
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White

    val filteredAlbums = remember(savedAlbums, searchQuery) {
        if (searchQuery.isBlank()) savedAlbums
        else savedAlbums.filter {
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(90.dp)
                    )

                    Spacer(Modifier.width(16.dp))

                    Text(
                        text = "Saved Albums (${savedAlbums.size})",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = textColor
                    )
                }

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
                        "Search albums...",
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

        if (filteredAlbums.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isBlank()) "No saved albums yet\nSearch for albums to save"
                    else "No albums found",
                    fontFamily = NothingFont,
                    color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState
            ) {
                items(filteredAlbums) { album ->
                    AlbumItem(
                        album = album,
                        isDarkMode = isDarkMode,
                        onClick = { onAlbumClick(album) }
                    )
                }
            }
        }

        // Bottom bar
        AlbumsBottomBar(
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
fun AlbumItem(
    album: Album,
    isDarkMode: Boolean,
    onClick: () -> Unit
) {
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = album.thumbnail,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "(Album) ${album.artist}",
                fontFamily = NothingFont,
                fontSize = 12.sp,
                color = subTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (album.songCount > 0) {
                Text(
                    text = "${album.songCount} songs · ${formatTime(album.duration)}",
                    fontFamily = NothingFont,
                    fontSize = 11.sp,
                    color = Color.Gray,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun AlbumsBottomBar(
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
                    contentDescription = "Search albums",
                    tint = if (isSearching) Color.White else iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "(ALBUMS)",
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
