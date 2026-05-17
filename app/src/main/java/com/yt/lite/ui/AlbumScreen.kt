package com.yt.lite.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yt.lite.api.Innertube
import com.yt.lite.data.Album
import com.yt.lite.data.Song
import com.yt.lite.ui.theme.NothingFont
import com.yt.lite.utils.HapticUtils
import kotlinx.coroutines.launch

private fun formatAlbumDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    val h = m / 60
    return if (h > 0) {
        "%d hr %d min".format(h, m % 60)
    } else {
        "%d min %02d sec".format(m, s)
    }
}

@Composable
fun AlbumScreen(
    vm: MusicViewModel,
    album: Album,
    isDarkMode: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()
    val savedAlbums by vm.savedAlbums.collectAsState()
    val currentSong by vm.currentSong.collectAsState()

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var releaseYear by remember { mutableStateOf("") }
    var totalDuration by remember { mutableStateOf(0L) }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    val isSaved = savedAlbums.any { it.id == album.id }

    LaunchedEffect(album.id) {
        isLoading = true
        try {
            val fetchedSongs = Innertube.getAlbumSongs(album.id).second
            songs = fetchedSongs
            releaseYear = album.artist.substringAfterLast("•").trim().takeIf { it.matches(Regex("\\d{4}")) } ?: "2024"
            totalDuration = album.duration
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to load album", Toast.LENGTH_SHORT).show()
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .padding(vertical = 8.dp, horizontal = 8.dp),
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
        }

        if (isLoading) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.Red)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AsyncImage(
                            model = album.thumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = album.title,
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            color = textColor,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = album.artist.substringBeforeLast("•").trim(),
                            fontFamily = NothingFont,
                            fontSize = 16.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                        
                        val durationText = formatAlbumDuration(totalDuration)
                        val detailsText = buildString {
                            if (releaseYear.isNotBlank()) append(releaseYear)
                            if (releaseYear.isNotBlank() && durationText.isNotBlank()) append(" • ")
                            if (durationText.isNotBlank()) append(durationText)
                        }
                        
                        if (detailsText.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = detailsText,
                                fontFamily = NothingFont,
                                fontSize = 14.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(32.dp))
                                    .background(Color.Red)
                                    .clickable {
                                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                        if (songs.isNotEmpty()) {
                                            vm.playWithQueue(songs.first(), songs)
                                        }
                                    }
                                    .padding(horizontal = 32.dp, vertical = 12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color.White
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Play",
                                        fontFamily = NothingFont,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            IconButton(onClick = {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                if (isSaved) vm.unsaveAlbum(album) else vm.saveAlbum(album)
                            }) {
                                Icon(
                                    imageVector = if (isSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Save Album",
                                    tint = if (isSaved) Color.Red else textColor,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }

                items(songs) { song ->
                    val isLiked = likedSongs.any { it.id == song.id }
                    val isPlayingSong = currentSong?.id == song.id
                    SongItem(
                        song = song,
                        isDarkMode = isDarkMode,
                        isLiked = isLiked,
                        isInQueue = false,
                        isPlaying = isPlayingSong,
                        showMenu = true,
                        hapticsEnabled = hapticsEnabled,
                        context = context,
                        onClick = { vm.playWithQueue(song, songs) },
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
}
