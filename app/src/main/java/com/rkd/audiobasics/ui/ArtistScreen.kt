package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.data.ArtistPage
import com.rkd.audiobasics.api.Innertube
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils
import kotlinx.coroutines.launch

@Composable
fun ArtistScreen(
    vm: MusicViewModel,
    artistName: String,
    artistBrowseId: String = "",   // if coming from YTM browse link
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    onAddTo: (com.rkd.audiobasics.data.Song) -> Unit
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()
    val currentSong by vm.currentSong.collectAsState()
    val scope = rememberCoroutineScope()

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    var artistPage by remember { mutableStateOf<ArtistPage?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val tabs = listOf("Popular songs", "Albums", "Singles & EPs")

    LaunchedEffect(artistName, artistBrowseId) {
        isLoading = true
        hasError = false
        try {
            val page = if (artistBrowseId.isNotBlank()) {
                Innertube.getArtistPage(artistBrowseId)
            } else {
                Innertube.searchArtistByName(artistName)
            }
            artistPage = page
            hasError = page == null
        } catch (_: Exception) {
            hasError = true
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        // ── Artist image ────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            AsyncImage(
                model = artistPage?.artist?.thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // ── Artist name ─────────────────────────────────────────────────────
        Text(
            text = artistPage?.artist?.name ?: artistName,
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = textColor,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )

        // ── Tabs ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            tabs.forEachIndexed { i, label ->
                Text(
                    text = label,
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (selectedTab == i) Color.Red else subTextColor,
                    modifier = Modifier.clickable {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        selectedTab = i
                    }
                )
            }
        }

        DashedDivider(modifier = Modifier.fillMaxWidth(), isDarkMode = isDarkMode)

        // ── Content ─────────────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Red)
                }
                hasError || artistPage == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Artist not found", fontFamily = NothingFont, color = Color.Gray)
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        when (selectedTab) {
                            0 -> { // Popular songs
                                items(artistPage!!.popularSongs.take(10)) { song ->
                                    SongItem(
                                        song = song,
                                        isDarkMode = isDarkMode,
                                        isLiked = likedSongs.any { it.id == song.id },
                                        isPlaying = currentSong?.id == song.id,
                                        hapticsEnabled = hapticsEnabled,
                                        context = context,
                                        onClick = { vm.play(song) },
                                        onLike = { vm.toggleLike(song) },
                                        onShare = {},
                                        onAddToQueue = { vm.addToQueue(song) },
                                        onPlayNext = { vm.playNext(song) },
                                        onAddTo = { onAddTo(song) }
                                    )
                                }
                            }
                            1 -> { // Albums
                                items(artistPage!!.albums) { album ->
                                    AlbumRowItem(
                                        album = album,
                                        isDarkMode = isDarkMode,
                                        showYear = true,
                                        onClick = { onAlbumClick(album) }
                                    )
                                }
                            }
                            2 -> { // Singles & EPs
                                items(artistPage!!.singles) { single ->
                                    AlbumRowItem(
                                        album = single,
                                        isDarkMode = isDarkMode,
                                        showYear = true,
                                        onClick = { onAlbumClick(single) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Bottom bar ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onBack()
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "🔍 (Artist)",
                fontFamily = NothingFont,
                fontSize = 13.sp,
                color = subTextColor
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
fun AlbumRowItem(
    album: Album,
    isDarkMode: Boolean,
    showYear: Boolean = false,
    onClick: () -> Unit
) {
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = album.thumbnail,
            contentDescription = null,
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.title,
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showYear && album.youtubeUrl.isNotBlank()) {
                Text(
                    text = album.youtubeUrl, // youtubeUrl repurposed as year string for artist page
                    fontFamily = NothingFont,
                    fontSize = 14.sp,
                    color = subTextColor
                )
            } else if (!showYear) {
                Text(
                    text = album.artist,
                    fontFamily = NothingFont,
                    fontSize = 13.sp,
                    color = subTextColor
                )
            }
        }
    }
}
