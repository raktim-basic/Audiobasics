package com.yt.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yt.lite.data.Album
import com.yt.lite.ui.theme.NothingFont

@Composable
fun SearchScreen(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onNavigateQueue: () -> Unit,
    onAlbumClick: (Album) -> Unit
) {
    val results by vm.searchResults.collectAsState()
    val isSearching by vm.isSearching.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()

    var query by remember { mutableStateOf("") }
    var showLinkDialog by remember { mutableStateOf(false) }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White

    if (showLinkDialog) {
        PlayByLinkDialog(
            isDarkMode = isDarkMode,
            onDismiss = { showLinkDialog = false },
            onPlay = { url ->
                vm.playByUrl(url)
                showLinkDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // Search box
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, Color.Red, RoundedCornerShape(8.dp)),
                placeholder = {
                    Text(
                        "Search songs...",
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
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) })
            )

            Spacer(Modifier.height(8.dp))

            // YouTube link option
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            fontFamily = NothingFont,
                            color = textColor,
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic
                        )
                    ) { append("Cannot find the song?  ") }
                    withStyle(
                        SpanStyle(
                            fontFamily = NothingFont,
                            color = Color(0xFF1565C0),
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                            textDecoration = TextDecoration.Underline
                        )
                    ) { append("Try with YouTube link here.") }
                },
                modifier = Modifier.clickable { showLinkDialog = true }
            )

            Spacer(Modifier.height(8.dp))

            if (isSearching) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.Red)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(results) { song ->
                        val isLiked = likedSongs.any { it.id == song.id }
                        SongItem(
                            song = song,
                            isDarkMode = isDarkMode,
                            isLiked = isLiked,
                            isInQueue = false,
                            showMenu = !song.isAlbum,
                            onClick = {
                                if (song.isAlbum) {
                                    onAlbumClick(
                                        Album(
                                            id = song.id,
                                            title = song.title,
                                            artist = song.artist
                                                .removePrefix("(Album) "),
                                            thumbnail = song.thumbnail
                                        )
                                    )
                                } else {
                                    vm.play(song)
                                }
                            },
                            onAddToQueue = { vm.addToQueue(song) },
                            onPlayNext = { vm.playNext(song) },
                            onLike = { vm.toggleLike(song) },
                            onShare = {},
                            onRetryCache = { vm.retryCache(song) },
                            onRemoveLike = { vm.toggleLike(song) }
                        )
                    }
                }
            }
        }

        SearchBottomBar(
            isDarkMode = isDarkMode,
            onBack = onBack,
            onQueue = onNavigateQueue
        )
    }
}

@Composable
fun SearchBottomBar(
    isDarkMode: Boolean,
    onBack: () -> Unit,
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

@Composable
fun PlayByLinkDialog(
    isDarkMode: Boolean,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit
) {
    var link by remember { mutableStateOf("") }
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
                Text(
                    text = "Play with YouTube Link",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = textColor
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Paste YouTube link...",
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
                        unfocusedBorderColor = Color.Gray
                    )
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", fontFamily = NothingFont, color = Color.Gray)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Red)
                            .clickable { if (link.isNotBlank()) onPlay(link) }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "Play",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
