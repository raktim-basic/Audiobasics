package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rkd.audiobasics.api.Innertube
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private suspend fun fetchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
    if (query.isBlank()) return@withContext emptyList()
    try {
        val client = OkHttpClient()
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20260520.01.00")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("input", query)
        }
        val req = Request.Builder()
            .url(
                "https://music.youtube.com/youtubei/v1/music/get_search_suggestions" +
                "?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
            )
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0")
            .addHeader("Origin", "https://music.youtube.com")
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        val resp = client.newCall(req).execute()
        val text = resp.body?.string() ?: return@withContext emptyList()
        val json = JSONObject(text)
        val suggestions = mutableListOf<String>()
        val contents = json.optJSONArray("contents") ?: return@withContext emptyList()
        for (i in 0 until contents.length()) {
            val section = contents.optJSONObject(i)
                ?.optJSONObject("searchSuggestionsSectionRenderer")
                ?.optJSONArray("contents") ?: continue
            for (j in 0 until section.length()) {
                val runs = section.optJSONObject(j)
                    ?.optJSONObject("searchSuggestionRenderer")
                    ?.optJSONObject("suggestion")
                    ?.optJSONArray("runs") ?: continue
                val suggestion = buildString {
                    for (k in 0 until runs.length()) {
                        append(runs.optJSONObject(k)?.optString("text", "") ?: "")
                    }
                }
                if (suggestion.isNotBlank()) suggestions.add(suggestion)
            }
        }
        suggestions
    } catch (_: Exception) { emptyList() }
}

@Composable
fun SearchScreen(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    onBack: () -> Unit,
    onNavigateQueue: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (com.rkd.audiobasics.data.Artist) -> Unit = {},
    onAddTo: (Song) -> Unit = {}
) {
    val context = LocalContext.current
    val results by vm.searchResults.collectAsState()
    val isSearching by vm.isSearching.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()
    val currentSong by vm.currentSong.collectAsState()
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()

    val query by vm.searchQuery.collectAsState()
    var showLinkDialog by rememberSaveable { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSuggestions by rememberSaveable { mutableStateOf(false) }

    // The filter tabs (Songs/Albums/Artists) switch which results the same box below shows;
    // "Links" isn't a result type, it's shorthand for opening the same play-by-link dialog
    // the old "Try with YouTube link" text used to.
    var selectedFilter by rememberSaveable { mutableStateOf(SearchResultFilter.SONGS) }
    // The query that's actually been searched (i.e. submitted), separate from what's still
    // being typed — Albums/Artists fetch off this, the same way Songs already does via
    // vm.search(). Also doubles as which query each cache below (if any) was fetched for.
    var committedQuery by rememberSaveable { mutableStateOf("") }
    var albumResults by remember { mutableStateOf<List<Album>>(emptyList()) }
    var artistResults by remember { mutableStateOf<List<com.rkd.audiobasics.data.Artist>>(emptyList()) }
    var isLoadingAlbums by remember { mutableStateOf(false) }
    var isLoadingArtists by remember { mutableStateOf(false) }
    var albumsFetchedFor by remember { mutableStateOf<String?>(null) }
    var artistsFetchedFor by remember { mutableStateOf<String?>(null) }

    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var suggestionJob by remember { mutableStateOf<Job?>(null) }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    // Runs a fresh search for the query as-typed: songs go through the ViewModel as before;
    // albums/artists are only actually fetched once their tab gets selected (see the
    // LaunchedEffect below) so switching tabs back and forth doesn't refetch needlessly.
    fun submitSearch(text: String) {
        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
        suggestionJob?.cancel()
        suggestions = emptyList()
        showSuggestions = false
        committedQuery = text
        vm.search(text)
        focusManager.clearFocus()
    }

    LaunchedEffect(committedQuery, selectedFilter) {
        if (committedQuery.isBlank()) return@LaunchedEffect
        when (selectedFilter) {
            SearchResultFilter.ALBUMS -> {
                if (albumsFetchedFor != committedQuery) {
                    isLoadingAlbums = true
                    albumResults = try {
                        Innertube.searchAlbums(committedQuery)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    isLoadingAlbums = false
                    albumsFetchedFor = committedQuery
                }
            }
            SearchResultFilter.ARTISTS -> {
                if (artistsFetchedFor != committedQuery) {
                    isLoadingArtists = true
                    artistResults = try {
                        Innertube.searchArtists(committedQuery)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    isLoadingArtists = false
                    artistsFetchedFor = committedQuery
                }
            }
            SearchResultFilter.SONGS -> Unit
        }
    }

    LaunchedEffect(Unit) {
        if (results.isEmpty()) focusRequester.requestFocus()
    }

    LaunchedEffect(query, results) {
        suggestionJob?.cancel()
        if (query.isBlank()) {
            suggestions = emptyList()
            showSuggestions = false
            return@LaunchedEffect
        }
        if (results.isNotEmpty()) {
            suggestions = emptyList()
            showSuggestions = false
            return@LaunchedEffect
        }
        suggestionJob = scope.launch {
            delay(300)
            if (query.isNotBlank() && results.isEmpty()) {
                val fetched = fetchSuggestions(query)
                if (query.isNotBlank() && results.isEmpty()) {
                    suggestions = fetched
                    showSuggestions = fetched.isNotEmpty()
                }
            }
        }
    }

    if (showLinkDialog) {
        PlayByLinkDialog(
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
            context = context,
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
        Box(modifier = Modifier.weight(1f)) {
            when {
                isSearching && selectedFilter == SearchResultFilter.SONGS -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.Red)
                    }
                }
                showSuggestions && suggestions.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true,
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(suggestions) { suggestion ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                        suggestionJob?.cancel()
                                        vm.setSearchQuery(suggestion)
                                        suggestions = emptyList()
                                        showSuggestions = false
                                        submitSearch(suggestion)
                                    }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🔍", fontSize = 14.sp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = suggestion,
                                    fontFamily = NothingFont,
                                    fontSize = 14.sp,
                                    color = textColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                selectedFilter == SearchResultFilter.ALBUMS -> {
                    when {
                        committedQuery.isBlank() -> Unit // nothing searched yet — same blank initial state as Songs
                        isLoadingAlbums -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.Red)
                        }
                        albumResults.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No albums found", fontFamily = NothingFont, color = Color.Gray)
                        }
                        else -> {
                            LazyColumn(modifier = Modifier.fillMaxSize(), reverseLayout = true) {
                                items(albumResults) { album ->
                                    AlbumRowItem(
                                        album = album,
                                        isDarkMode = isDarkMode,
                                        onClick = {
                                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                            onAlbumClick(album)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                selectedFilter == SearchResultFilter.ARTISTS -> {
                    when {
                        committedQuery.isBlank() -> Unit // nothing searched yet — same blank initial state as Songs
                        isLoadingArtists -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.Red)
                        }
                        artistResults.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No artists found", fontFamily = NothingFont, color = Color.Gray)
                        }
                        else -> {
                            LazyColumn(modifier = Modifier.fillMaxSize(), reverseLayout = true) {
                                items(artistResults) { artist ->
                                    ArtistRowItem(
                                        artist = artist,
                                        isDarkMode = isDarkMode,
                                        onClick = {
                                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                            onArtistClick(artist)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true
                    ) {
                        itemsIndexed(results, key = { _, song -> song.id }) { index, song ->
                            val isLiked = likedSongs.any { it.id == song.id }
                            val isPlaying = currentSong?.id == song.id

                            // Fade-in-from-bottom stagger: with reverseLayout = true, index 0
                            // is the item rendered closest to the search bar at the bottom of
                            // the screen, so it gets the shortest delay and animates in first,
                            // with each item above it following ~24ms later — communicating
                            // that results originate from the search bar without any text.
                            // Keyed on song.id (via `remember`) so this only plays once per
                            // item's first appearance, not on every recomposition/scroll.
                            val alpha = remember(song.id) { Animatable(0f) }
                            val offsetY = remember(song.id) { Animatable(12f) }
                            LaunchedEffect(song.id) {
                                delay((index * 28L).coerceAtMost(600L))
                                launch { alpha.animateTo(1f, tween(durationMillis = 220)) }
                                launch { offsetY.animateTo(0f, tween(durationMillis = 220)) }
                            }

                            Box(
                                modifier = Modifier.graphicsLayer {
                                    this.alpha = alpha.value
                                    translationY = offsetY.value
                                }
                            ) {
                                SongItem(
                                    song = song,
                                    isDarkMode = isDarkMode,
                                    isLiked = isLiked,
                                    isInQueue = false,
                                    isPlaying = isPlaying,
                                    showMenu = true,
                                    hapticsEnabled = hapticsEnabled,
                                    context = context,
                                    onClick = { vm.play(song) },
                                    onAddToQueue = { vm.addToQueue(song) },
                                    onPlayNext = { vm.playNext(song) },
                                    onLike = { vm.toggleLike(song) },
                                    onShare = {},
                                    onRetryCache = { vm.retryCache(song) },
                                    onAddTo = { onAddTo(song) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Filter tabs — replaces the old "View Albums" / "View Artists" cards. Songs/Albums/
        // Artists switch which results the box above shows (fetching that type's results the
        // first time its tab is selected for the current query, see the LaunchedEffect above);
        // Links isn't a result type, tapping it just opens the play-by-link dialog directly.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Text(
                text = "Links",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = textColor,
                modifier = Modifier.clickable {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    showLinkDialog = true
                }
            )
            listOf(
                SearchResultFilter.SONGS to "Songs",
                SearchResultFilter.ALBUMS to "Albums",
                SearchResultFilter.ARTISTS to "Artists"
            ).forEach { (filter, label) ->
                Text(
                    text = label,
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = if (selectedFilter == filter) Color.Red else textColor,
                    modifier = Modifier.clickable {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        selectedFilter = filter
                        // Selecting a filter with a query already typed (but not yet
                        // submitted, e.g. suggestions still showing) submits it — matches
                        // hitting the keyboard's search action, just triggered from a tab tap.
                        if (query.isNotBlank() && committedQuery != query) {
                            submitSearch(query)
                        }
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFDDDDDD))
        )

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
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = textColor, modifier = Modifier.size(26.dp))
            }

            OutlinedTextField(
                value = query,
                onValueChange = {
                    vm.setSearchQuery(it)
                    if (it.isNotBlank()) {
                        if (results.isNotEmpty()) vm.clearSearchResultsOnly()
                        showSuggestions = true
                    } else {
                        showSuggestions = false
                    }
                },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = {
                    Text("Search...", fontFamily = NothingFont, color = Color.Gray, fontSize = 14.sp)
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
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.isNotBlank()) submitSearch(query)
                })
            )

            IconButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onNavigateQueue()
            }) {
                Icon(Icons.Default.QueueMusic, contentDescription = "Queue", tint = textColor, modifier = Modifier.size(26.dp))
            }
        }
    }
}

private enum class SearchResultFilter { SONGS, ALBUMS, ARTISTS }

@Composable
fun PlayByLinkDialog(
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit
) {
    var link by remember { mutableStateOf("") }
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black

    Dialog(onDismissRequest = {
        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
        onDismiss()
    }) {
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
                        Text("Paste YouTube link...", fontFamily = NothingFont, color = Color.Gray)
                    },
                    textStyle = TextStyle(fontFamily = NothingFont, color = textColor),
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
                    TextButton(onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onDismiss()
                    }) {
                        Text("Cancel", fontFamily = NothingFont, color = Color.Gray)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Red)
                            .clickable {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                if (link.isNotBlank()) onPlay(link)
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text("Play", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}
