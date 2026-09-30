package com.rkd.audiobasics.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.rkd.audiobasics.api.Innertube
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistScreen(
    vm: MusicViewModel,
    artistName: String,
    artistBrowseId: String = "",
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    onAddTo: (Song) -> Unit,
    onNavigateQueue: () -> Unit = {}
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()
    val currentSong by vm.currentSong.collectAsState()

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White

    // Scoped to this artist's own NavEntry (see ArtistViewModel.kt) — survives pushing an
    // album/EP on top and coming back, so the fetched page and selected tab don't reset.
    val artistVm: ArtistViewModel = viewModel()

    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showShareChoice by remember { mutableStateOf(false) }

    val displayName = artistVm.artistPage?.artist?.name?.takeIf { it.isNotBlank() } ?: artistName
    val shareArtistId = artistVm.artistPage?.artist?.id?.takeIf { it.isNotBlank() } ?: artistBrowseId

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(isSearching) { if (isSearching) focusRequester.requestFocus() }

    LaunchedEffect(artistName, artistBrowseId) {
        artistVm.loadIfNeeded(artistName, artistBrowseId)
    }

    // Reset search when tab changes
    LaunchedEffect(artistVm.selectedTab) {
        searchQuery = ""
        isSearching = false
    }

    val tabs = listOf("Popular songs", "Albums", "Singles & EPs")

    // Filtered content per tab
    val filteredSongs = remember(artistVm.artistPage, searchQuery, artistVm.selectedTab) {
        if (artistVm.selectedTab != 0) return@remember emptyList()
        val all = artistVm.artistPage?.popularSongs ?: emptyList()
        if (searchQuery.isBlank()) all
        else all.filter { it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true) }
    }
    val filteredAlbums = remember(artistVm.artistPage, searchQuery, artistVm.selectedTab) {
        if (artistVm.selectedTab != 1) return@remember emptyList()
        val all = artistVm.artistPage?.albums ?: emptyList()
        if (searchQuery.isBlank()) all
        else all.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }
    val filteredSingles = remember(artistVm.artistPage, searchQuery, artistVm.selectedTab) {
        if (artistVm.selectedTab != 2) return@remember emptyList()
        val all = artistVm.artistPage?.singles ?: emptyList()
        if (searchQuery.isBlank()) all
        else all.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    val scrollProgress = remember(listState) {
        derivedStateOf {
            val total = when (artistVm.selectedTab) {
                0 -> filteredSongs.size + 2
                1 -> filteredAlbums.size + 2
                else -> filteredSingles.size + 2
            }.coerceAtLeast(2)
            val max = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            (max.toFloat() / total).coerceIn(0f, 1f)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {

        if (artistVm.isLoading) {
            // Loading: just a centered spinner — no skeleton hero/controls/tabs
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Red)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), state = listState) {

                // ── Hero image ─────────────────────────────────────────────────
                item {
                    val clear = artistVm.clearView
                    val dim by animateFloatAsState(if (clear) 0f else 0.45f, tween(300), label = "heroDim")
                    val nameAlpha by animateFloatAsState(if (clear) 0f else 1f, tween(300), label = "heroName")

                    Box(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                        AsyncImage(
                            model = artistVm.artistPage?.artist?.thumbnail,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        // Dimming scrim — fades out entirely in clear view
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
                        Text(
                            text = displayName,
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 24.dp)
                                .graphicsLayer { alpha = nameAlpha }
                        )
                    }

                    // Same fixed height in both states so the tabs below never jump on toggle
                    Crossfade(targetState = clear, animationSpec = tween(250), label = "heroControls") { isClear ->
                        if (isClear) {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(72.dp).padding(start = 20.dp, end = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = displayName,
                                    fontFamily = NothingFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp,
                                    color = textColor,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    artistVm.clearView = false
                                }) {
                                    ClearViewIcon(outward = false, color = Color.Red)
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    artistVm.clearView = true
                                }) {
                                    ClearViewIcon(outward = true, color = Color.Red)
                                }
                                val wiki = artistVm.wikiUrl
                                if (wiki != null) {
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "Wiki",
                                        fontFamily = NothingFont,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = Color(0xFF4A90E2),
                                        textDecoration = TextDecoration.Underline,
                                        modifier = Modifier
                                            .clickable {
                                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                                try {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(wiki)))
                                                } catch (_: Exception) {
                                                    Toast.makeText(context, "Couldn't open Wikipedia", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 12.dp)
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    when {
                                        shareArtistId.isBlank() ->
                                            Toast.makeText(context, "Artist isn't loaded yet", Toast.LENGTH_SHORT).show()
                                        com.rkd.audiobasics.utils.AudiobasicsLinks.isYoutubeShareOptionEnabled(context) ->
                                            showShareChoice = true
                                        else -> com.rkd.audiobasics.utils.AudiobasicsLinks.shareText(
                                            context,
                                            com.rkd.audiobasics.utils.AudiobasicsLinks.artistLink(shareArtistId, displayName),
                                            "Share artist"
                                        )
                                    }
                                }) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = textColor,
                                        modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }

                // ── Tabs (sticky) ──────────────────────────────────────────────
                stickyHeader {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { }
                            .background(bgColor)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            tabs.forEachIndexed { i, label ->
                                Text(
                                    text = label,
                                    fontFamily = NothingFont,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (artistVm.selectedTab == i) Color.Red else subTextColor,
                                    modifier = Modifier
                                        .clickable {
                                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                            artistVm.selectedTab = i
                                        }
                                        .padding(vertical = 8.dp)
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

                // ── Error / Empty ─────────────────────────────────────────────
                if (artistVm.hasError || artistVm.artistPage == null) {
                    item {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text("Artist not found", fontFamily = NothingFont, color = Color.Gray)
                        }
                    }
                } else {
                    // ── Tab content ────────────────────────────────────────────
                    when (artistVm.selectedTab) {
                        0 -> {
                            if (filteredSongs.isEmpty()) {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                        Text(if (searchQuery.isBlank()) "No songs found" else "No results",
                                            fontFamily = NothingFont, color = Color.Gray)
                                    }
                                }
                            } else {
                                items(filteredSongs) { song ->
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
                        }
                        1 -> {
                            if (filteredAlbums.isEmpty()) {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                        Text(if (searchQuery.isBlank()) "No albums found" else "No results",
                                            fontFamily = NothingFont, color = Color.Gray)
                                    }
                                }
                            } else {
                                items(filteredAlbums) { album ->
                                    AlbumRowItem(album = album, isDarkMode = isDarkMode, showYear = true,
                                        onClick = { onAlbumClick(album) })
                                }
                            }
                        }
                        2 -> {
                            if (filteredSingles.isEmpty()) {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                        Text(if (searchQuery.isBlank()) "No singles/EPs found" else "No results",
                                            fontFamily = NothingFont, color = Color.Gray)
                                    }
                                }
                            } else {
                                items(filteredSingles) { single ->
                                    AlbumRowItem(album = single, isDarkMode = isDarkMode, showYear = true,
                                        onClick = { onAlbumClick(single) })
                                }
                            }
                        }
                    }
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
                        val hint = when (artistVm.selectedTab) {
                            0 -> "Search songs..."
                            1 -> "Search albums..."
                            else -> "Search singles & EPs..."
                        }
                        Text(hint, fontFamily = NothingFont, color = Color.Gray, fontSize = 14.sp)
                    },
                    textStyle = TextStyle(fontFamily = NothingFont, color = textColor, fontSize = 14.sp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Red, unfocusedBorderColor = Color.Red,
                        focusedContainerColor = surfaceColor, unfocusedContainerColor = surfaceColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    isSearching = false; searchQuery = ""; focusManager.clearFocus()
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
                    Text("(Artist)", fontFamily = NothingFont,
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

    if (showShareChoice) {
        ShareChoiceDialog(
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
            context = context,
            onAudiobasicsLink = {
                com.rkd.audiobasics.utils.AudiobasicsLinks.shareText(
                    context,
                    com.rkd.audiobasics.utils.AudiobasicsLinks.artistLink(shareArtistId, displayName),
                    "Share artist"
                )
            },
            onYoutubeLink = {
                com.rkd.audiobasics.utils.AudiobasicsLinks.shareText(
                    context, "https://music.youtube.com/channel/$shareArtistId", "Share artist"
                )
            },
            onDismiss = { showShareChoice = false }
        )
    }
}

/** The "clear view" toggle glyph: two horizontal arrows pointing outward (enter clear view)
 *  or inward (exit it). */
@Composable
private fun ClearViewIcon(outward: Boolean, color: Color) {
    Canvas(Modifier.size(width = 28.dp, height = 16.dp)) {
        val w = size.width
        val cy = size.height / 2f
        val stroke = 2.5.dp.toPx()
        val head = 5.dp.toPx()
        val mid = w / 2f
        val gap = 2.dp.toPx()
        if (outward) {
            drawArrow(mid - gap, 0f, cy, color, stroke, head)
            drawArrow(mid + gap, w, cy, color, stroke, head)
        } else {
            drawArrow(0f, mid - gap, cy, color, stroke, head)
            drawArrow(w, mid + gap, cy, color, stroke, head)
        }
    }
}

private fun DrawScope.drawArrow(fromX: Float, toX: Float, cy: Float, color: Color, stroke: Float, head: Float) {
    drawLine(color, Offset(fromX, cy), Offset(toX, cy), strokeWidth = stroke, cap = StrokeCap.Round)
    val back = if (toX > fromX) -head else head
    drawLine(color, Offset(toX, cy), Offset(toX + back, cy - head), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, Offset(toX, cy), Offset(toX + back, cy + head), strokeWidth = stroke, cap = StrokeCap.Round)
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = album.thumbnail,
            contentDescription = null,
            modifier = Modifier.size(60.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = album.title, fontFamily = NothingFont, fontWeight = FontWeight.Bold,
                fontSize = 15.sp, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                showYear && album.year.isNotBlank() -> album.year
                showYear && album.youtubeUrl.matches(Regex("\\d{4}")) -> album.youtubeUrl
                else -> album.artist
            }
            if (sub.isNotBlank()) {
                Text(text = sub, fontFamily = NothingFont, fontSize = 13.sp,
                    color = subTextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
