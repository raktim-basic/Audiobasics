package com.yt.lite.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.yt.lite.ui.theme.NothingFont
import com.yt.lite.utils.HapticUtils

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SavedAlbumsScreen(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onNavigateQueue: () -> Unit,
    onAlbumClick: (Album) -> Unit
) {
    val context = LocalContext.current
    val savedAlbums by vm.savedAlbums.collectAsState()
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    // Auto‑focus when entering search mode
    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    val filteredAlbums = remember(savedAlbums, searchQuery) {
        if (searchQuery.isBlank()) savedAlbums
        else savedAlbums.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    val totalRegularItems = filteredAlbums.size + 1
    val scrollProgress = remember(listState, totalRegularItems) {
        derivedStateOf {
            if (totalRegularItems <= 1) return@derivedStateOf 0f
            val layoutInfo = listState.layoutInfo
            val visibleRegularIndices = layoutInfo.visibleItemsInfo
                .map { it.index }
                .filter { it == 0 || (it >= 2 && it <= totalRegularItems) }
            val maxVisibleIndex = visibleRegularIndices.maxOrNull() ?: 0
            (maxVisibleIndex.toFloat() / totalRegularItems).coerceIn(0f, 1f)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(32.dp))
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = "Saved Albums",
                        modifier = Modifier.size(100.dp),
                        tint = textColor
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }

            stickyHeader {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Saved Albums (${savedAlbums.size})",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = textColor
                        )
                    }
                    DashedDivider(
                        modifier = Modifier.fillMaxWidth(),
                        isDarkMode = isDarkMode,
                        scrollProgress = scrollProgress.value
                    )
                }
            }

            if (filteredAlbums.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank())
                                "No saved albums yet\nSearch for albums to save"
                            else "No albums found",
                            fontFamily = NothingFont,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(filteredAlbums) { album ->
                    AlbumItem(
                        album = album,
                        isDarkMode = isDarkMode,
                        hapticsEnabled = hapticsEnabled,
                        context = context,
                        onClick = { onAlbumClick(album) }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFDDDDDD))
        )

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
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = {
                        Text(
                            "Search albums...",
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
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
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
                IconButton(onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    onBack()
                }) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = textColor,
                        modifier = Modifier.size(26.dp)
                    )
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
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "(ALBUMS)",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = textColor
                    )
                }
                IconButton(onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    onNavigateQueue()
                }) {
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
fun AlbumItem(
    album: Album,
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onClick: () -> Unit
) {
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onClick()
            }
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
