package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.api.Innertube
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

@Composable
fun SearchAlbumsScreen(
    vm: MusicViewModel,
    query: String,
    // Non-blank only when this search came from tapping an album in Song Info. When both are
    // set and the first result confidently matches them (same title, at least one shared
    // artist), we skip the results list and open it directly via onAutoMatchedAlbum instead.
    expectedTitle: String = "",
    expectedArtist: String = "",
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    onAutoMatchedAlbum: (Album) -> Unit = {},
    onNavigateQueue: () -> Unit
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(query, expectedTitle, expectedArtist) {
        isLoading = true
        try {
            val results = Innertube.searchAlbums(query)
            albums = results
            val first = results.firstOrNull()
            if (first != null && isConfidentAlbumMatch(first, expectedTitle, expectedArtist)) {
                onAutoMatchedAlbum(first)
            }
        } catch (_: Exception) {
            albums = emptyList()
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // Header
        Text(
            text = "Albums",
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = textColor,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 0.dp),
            color = subTextColor.copy(alpha = 0.3f),
            thickness = 1.dp
        )

        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Red)
                }
                albums.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No albums found", fontFamily = NothingFont, color = Color.Gray)
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(albums) { album ->
                            AlbumRowItem(
                                album = album,
                                isDarkMode = isDarkMode,
                                showYear = false,
                                onClick = { onAlbumClick(album) }
                            )
                        }
                    }
                }
            }
        }

        // Bottom bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onBack()
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
            }
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onNavigateQueue()
            }) {
                Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = textColor, modifier = Modifier.size(26.dp))
            }
        }
    }
}

// Title must match exactly (trimmed, case-insensitive); artist only needs one shared name,
// since the song's own artist list may be a subset of the album's full artist credits (or
// vice versa) even for the correct album.
private fun isConfidentAlbumMatch(album: Album, expectedTitle: String, expectedArtist: String): Boolean {
    if (expectedTitle.isBlank() || expectedArtist.isBlank()) return false
    if (!album.title.trim().equals(expectedTitle.trim(), ignoreCase = true)) return false
    val candidates = album.artistNames.ifEmpty { Innertube.splitArtistNames(album.artist) }
    return candidates.any { it.trim().equals(expectedArtist.trim(), ignoreCase = true) }
}
