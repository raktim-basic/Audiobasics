package com.rkd.audiobasics.ui

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

private fun formatAlbumDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(
    vm: MusicViewModel,
    album: Album,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onNavigateQueue: () -> Unit,
    onNavigateArtist: (String) -> Unit = {},
    onAddTo: (Song) -> Unit = {},
    onNavigateCacheSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()
    val currentSong by vm.currentSong.collectAsState()

    var enrichedAlbum by remember { mutableStateOf(album) }

    val isSaved by remember(vm.savedAlbums.collectAsState().value, enrichedAlbum) {
        derivedStateOf { vm.isAlbumSaved(album.id, enrichedAlbum.title) }
    }

    var albumSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var cacheRefreshTick by remember { mutableStateOf(0) }

    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearching) {
        if (isSearching) focusRequester.requestFocus()
    }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF888888)
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    val savedAlbumSongsMap by vm.savedAlbumSongs.collectAsState()
    val persistedForThisAlbum = savedAlbumSongsMap[album.id]

    LaunchedEffect(album.id, persistedForThisAlbum != null) {
        // If this album has a persisted offline tracklist, show it immediately —
        // this is what lets a saved album open fully offline.
        val persisted = savedAlbumSongsMap[album.id]
        if (persisted != null) {
            albumSongs = persisted.map { it.copy(albumTitle = album.title.ifBlank { it.albumTitle }) }
            isLoading = false
        }

        // Still attempt a live refresh (updates metadata / picks up tracklist changes)
        // unless we already have a persisted copy and there's no network — errors are
        // silently ignored in that case since we already have something to show.
        try {
            if (persisted == null) isLoading = true
            val (meta, songs) = com.rkd.audiobasics.api.Innertube.getAlbumSongs(
                browseId = album.id,
                fallbackArtist = album.artist,
                caller = "AlbumScreen"
            )
            // Innertube.getAlbumSongs fails silently (returns null/empty) rather than
            // throwing on network errors — don't let a failed live refresh clobber a
            // working persisted tracklist with an empty one.
            val resolvedTitle = (meta?.title?.ifBlank { null } ?: album.title)
            if (songs.isNotEmpty() || persisted == null) {
                // Stamp the album's own title directly onto each song — this is what Song
                // Info reads immediately, with no separate browse-id lookup needed.
                albumSongs = songs.map { it.copy(albumTitle = resolvedTitle) }
            } else if (persisted != null) {
                // Live fetch failed but we still have persisted songs shown — make sure
                // they at least carry the most accurate title we have.
                albumSongs = albumSongs.map { it.copy(albumTitle = resolvedTitle) }
            }
            if (meta != null) {
                enrichedAlbum = album.copy(
                    title = meta.title.ifBlank { album.title },
                    artist = meta.artist.ifBlank { album.artist },
                    thumbnail = meta.thumbnail.ifBlank { album.thumbnail },
                    year = meta.year.ifBlank { album.year }
                )
                // Share this resolved metadata app-wide so Song Info (and anywhere else
                // that looks up album titles by id) benefits immediately, not just this screen.
                if (meta.title.isNotBlank()) {
                    vm.cacheResolvedAlbum(enrichedAlbum)
                    // If this album was saved previously with incomplete metadata (e.g. a
                    // blank title from before this fix), refresh the saved copy too.
                    if (isSaved) vm.refreshSavedAlbumMetadata(enrichedAlbum)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AlbumScreen", "Failed to load: ${e.message}")
            // Network failed — fall back to whatever we have persisted, if anything.
            if (persisted != null) {
                albumSongs = persisted.map { it.copy(albumTitle = album.title.ifBlank { it.albumTitle }) }
            }
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

    val cacheProgress by vm.cacheProgress.collectAsState()
    val cachingSongIds by vm.cachingSongIds.collectAsState()
    LaunchedEffect(cacheProgress) {
        if (cacheProgress == null) cacheRefreshTick++
    }

    val uncachedInAlbumCount = remember(albumSongs, cacheRefreshTick, isSaved, cachingSongIds) {
        if (!isSaved) 0
        else albumSongs.count { !com.rkd.audiobasics.cache.CacheManager.isCached(context, it.id) }
    }
    val isAlbumCaching = remember(albumSongs, cachingSongIds) {
        albumSongs.any { it.id in cachingSongIds }
    }

    val totalRegularItems = filteredSongs.size + 1
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
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(24.dp))
                        AsyncImage(
                            model = enrichedAlbum.thumbnail.ifBlank { album.thumbnail },
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.height(12.dp))

                        // Clickable artists (red, underlined)
                        val displayedArtist = enrichedAlbum.artist.substringBeforeLast("•").trim()
                        val artistList = com.rkd.audiobasics.api.Innertube.splitArtistNames(displayedArtist)
                        com.rkd.audiobasics.ui.DebugLogCollector.add(
                            android.util.Log.ERROR, "ARTISTDEBUG",
                            "album.artist='${album.artist}' | enrichedAlbum.artist='${enrichedAlbum.artist}' | displayedArtist='$displayedArtist' | artistList=$artistList"
                        )

                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "(Album) ",
                                fontFamily = NothingFont,
                                fontSize = 14.sp,
                                color = subTextColor
                            )
                            artistList.forEachIndexed { i, artist ->
                                Text(
                                    text = buildAnnotatedString {
                                        withStyle(SpanStyle(
                                            color = Color.Red,
                                            textDecoration = TextDecoration.Underline
                                        )) { append(artist) }
                                    },
                                    fontFamily = NothingFont,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable { onNavigateArtist(artist) }
                                )
                                if (i < artistList.lastIndex) {
                                    Text(", ", fontFamily = NothingFont, fontSize = 14.sp, color = subTextColor)
                                }
                            }
                        }

                        if (albumSongs.isNotEmpty()) {
                            val totalDuration = albumSongs.sumOf { it.duration }
                            val durationText = formatAlbumDuration(totalDuration)
                            val yearStr = enrichedAlbum.year.ifBlank { "" }
                            val detailsText = buildString {
                                if (durationText.isNotBlank()) append("$durationText • ")
                                append("${albumSongs.size} tracks")
                                if (yearStr.isNotBlank()) append(" • $yearStr")
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = detailsText,
                                fontFamily = NothingFont,
                                fontSize = 13.sp,
                                color = subTextColor,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }

                stickyHeader {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor)
                    ) {
                        var titleOverflowed by remember { mutableStateOf(false) }
                        var titleExpanded by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .clickable(enabled = titleOverflowed || titleExpanded) {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    titleExpanded = !titleExpanded
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = enrichedAlbum.title.ifBlank { album.title },
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = textColor,
                                maxLines = if (titleExpanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { result ->
                                    if (!titleExpanded && result.hasVisualOverflow) titleOverflowed = true
                                },
                                modifier = Modifier.weight(1f)
                            )

                            if (!titleExpanded) {
                                Spacer(Modifier.width(8.dp))

                                // Share
                                IconButton(onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            album.youtubeUrl.ifBlank {
                                                "https://www.youtube.com/playlist?list=${album.id}"
                                            }
                                        )
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share album"))
                                }) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = textColor, modifier = Modifier.size(22.dp))
                                }

                            // Save/unsave
                            IconButton(onClick = {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                if (isSaved) vm.unsaveAlbum(enrichedAlbum) else vm.saveAlbum(enrichedAlbum)
                            }) {
                                Icon(
                                    imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = "Save",
                                    tint = textColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            // Play dropdown
                            var showPlayMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = {
                                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    showPlayMenu = true
                                }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = textColor, modifier = Modifier.size(26.dp))
                                }
                                DropdownMenu(
                                    expanded = showPlayMenu,
                                    onDismissRequest = { showPlayMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Play all", fontFamily = NothingFont) },
                                        onClick = {
                                            showPlayMenu = false
                                            if (albumSongs.isNotEmpty()) vm.playWithQueue(albumSongs.first(), albumSongs)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Shuffle", fontFamily = NothingFont) },
                                        onClick = {
                                            showPlayMenu = false
                                            if (albumSongs.isNotEmpty()) {
                                                val shuffled = albumSongs.shuffled()
                                                vm.playWithQueue(shuffled.first(), shuffled)
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Play next", fontFamily = NothingFont) },
                                        onClick = {
                                            showPlayMenu = false
                                            albumSongs.forEach { vm.playNext(it) }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add to queue", fontFamily = NothingFont) },
                                        onClick = {
                                            showPlayMenu = false
                                            albumSongs.forEach { vm.addToQueue(it) }
                                        }
                                    )
                                }
                            }
                            } // end if (!titleExpanded)
                        }

                        if (uncachedInAlbumCount > 0) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isAlbumCaching) "Downloading songs. "
                                           else "This album isn't available offline. ",
                                    fontFamily = NothingFont,
                                    fontSize = 13.sp,
                                    color = Color.Gray
                                )
                                Text(
                                    text = if (isAlbumCaching) "See progress" else "Download now?",
                                    fontFamily = NothingFont,
                                    fontSize = 13.sp,
                                    color = Color(0xFF1565C0),
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.clickable {
                                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                        onNavigateCacheSettings()
                                    }
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
                            modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (searchQuery.isBlank()) "No songs found" else "No results",
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
                        val thisSongCaching = song.id in cachingSongIds
                        val songIsCached = remember(song.id, cacheRefreshTick, isSaved, thisSongCaching) {
                            !isSaved || com.rkd.audiobasics.cache.CacheManager.isCached(context, song.id)
                        }
                        AlbumSongRow(
                            trackNumber = index + 1,
                            song = song,
                            isDarkMode = isDarkMode,
                            isLiked = isLiked,
                            isPlaying = isPlaying,
                            isCached = songIsCached,
                            hapticsEnabled = hapticsEnabled,
                            context = context,
                            onClick = { vm.playWithQueue(song, albumSongs) },
                            onAddToQueue = { vm.addToQueue(song) },
                            onPlayNext = { vm.playNext(song) },
                            onLike = { vm.toggleLike(song) },
                            onAddTo = { onAddTo(song) },
                            onRetryCache = {
                                vm.retryCacheStandalone(song)
                                cacheRefreshTick++
                            },
                            isCaching = thisSongCaching
                        )
                    }
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
                        Text("Search in album...", fontFamily = NothingFont, color = Color.Gray, fontSize = 14.sp)
                    },
                    textStyle = TextStyle(fontFamily = NothingFont, color = textColor, fontSize = 14.sp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Red,
                        unfocusedBorderColor = Color.Red,
                        focusedContainerColor = surfaceColor,
                        unfocusedContainerColor = surfaceColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    isSearching = false
                    searchQuery = ""
                    focusManager.clearFocus()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = textColor, modifier = Modifier.size(24.dp))
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
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor, modifier = Modifier.size(26.dp))
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
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = textColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("(ALBUM)", fontFamily = NothingFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = textColor)
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
}

@Composable
fun AlbumSongRow(
    trackNumber: Int,
    song: Song,
    isDarkMode: Boolean,
    isLiked: Boolean,
    isPlaying: Boolean,
    isCached: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onClick: () -> Unit,
    onAddToQueue: () -> Unit,
    onPlayNext: () -> Unit,
    onLike: () -> Unit,
    onAddTo: (() -> Unit)? = null,
    onRetryCache: (() -> Unit)? = null,
    isCaching: Boolean = false
) {
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val titleColor = if (isPlaying) Color.Red else textColor
    var menuExpanded by remember { mutableStateOf(false) }
    var showBrokenHeartDialog by remember { mutableStateOf(false) }

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

        if (!isCached) {
            Text(
                text = if (isCaching) "❤️‍🩹" else "💔",
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        if (!isCaching) showBrokenHeartDialog = true
                    }
                    .padding(8.dp)
            )
        }

        Box {
            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                menuExpanded = true
            }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = subTextColor)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                if (onAddTo != null) {
                    DropdownMenuItem(
                        text = { Text("Add to playlist...", fontFamily = NothingFont) },
                        onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            menuExpanded = false
                            onAddTo()
                        }
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(if (isLiked) "Unlike" else "Like", fontFamily = NothingFont) },
                        onClick = {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            menuExpanded = false
                            onLike()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Play next", fontFamily = NothingFont) },
                    onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        menuExpanded = false
                        onPlayNext()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Add to queue", fontFamily = NothingFont) },
                    onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        menuExpanded = false
                        onAddToQueue()
                    }
                )
            }
        }
    }

    if (showBrokenHeartDialog) {
        BrokenHeartDialog(
            song = song,
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
            context = context,
            onDismiss = { showBrokenHeartDialog = false },
            onPlayOnline = {
                showBrokenHeartDialog = false
                onClick()
            },
            onRetryCache = {
                showBrokenHeartDialog = false
                onRetryCache?.invoke()
            }
        )
    }
}
