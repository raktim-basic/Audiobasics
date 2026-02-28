package com.yt.lite.ui

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yt.lite.ui.theme.NothingFont

@Composable
fun LikedScreen(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onNavigateQueue: () -> Unit
) {
    val likedSongs by vm.likedSongs.collectAsState()
    val cacheSize by vm.cacheSize.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    val isHeaderCollapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White

    val filteredSongs = remember(likedSongs, searchQuery) {
        if (searchQuery.isBlank()) likedSongs
        else likedSongs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        if (isHeaderCollapsed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Liked Songs (${likedSongs.size})",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = textColor
                )
            }
            DashedDivider(modifier = Modifier.fillMaxWidth(), isDarkMode = isDarkMode)
        }

        if (isSearching) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .border(2.dp, Color.Red, RoundedCornerShape(8.dp)),
                placeholder = {
                    Text("Search liked songs...", fontFamily = NothingFont, color = Color.Gray)
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

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(90.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Liked Songs (${likedSongs.size})",
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = textColor
                            )
                            if (cacheSize.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = cacheSize,
                                    fontFamily = NothingFont,
                                    fontSize = 14.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Box(
                            modifier = Modifier
                                .border(2.dp, textColor, RoundedCornerShape(6.dp))
                                .clickable { vm.shuffleLiked() }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = textColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Shuffle",
                                    fontFamily = NothingFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = textColor
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    DashedDivider(
                        modifier = Modifier.fillMaxWidth(),
                        isDarkMode = isDarkMode
                    )
                }
            }

            items(filteredSongs) { song ->
                SongItem(
                    song = song,
                    isDarkMode = isDarkMode,
                    isLiked = true,
                    isInQueue = false,
                    showExplicit = false,
                    onClick = { vm.playWithQueue(song, filteredSongs) },
                    onAddToQueue = { vm.addToQueue(song) },
                    onPlayNext = { vm.playNext(song) },
                    onLike = { vm.toggleLike(song) },
                    onShare = {},
                    onRetryCache = { vm.retryCache(song) },
                    onRemoveLike = { vm.toggleLike(song) }
                )
            }
        }

        LikedBottomBar(
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
fun LikedBottomBar(
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
                    contentDescription = "Search liked",
                    tint = if (isSearching) Color.White else iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "(LIKED)",
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
