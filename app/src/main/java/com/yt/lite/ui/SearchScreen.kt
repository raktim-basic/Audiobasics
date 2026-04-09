package com.yt.lite.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yt.lite.data.Album
import com.yt.lite.ui.theme.NothingFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
                    put("clientVersion", "1.20240101.01.00")
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
            .addHeader("Origin", "https://music.youtube")
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
    onAlbumClick: (Album) -> Unit
) {
    val results by vm.searchResults.collectAsState()
    val isSearching by vm.isSearching.collectAsState()
    val likedSongs by vm.likedSongs.collectAsState()
    val currentSong by vm.currentSong.collectAsState()
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val haptic = LocalHapticFeedback.current

    var query by remember { mutableStateOf("") }
    var showLinkDialog by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    val bgColor = if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val barColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

    // Auto-focus the search field when screen appears
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(Unit) {
        query = ""
        suggestions = emptyList()
        showSuggestions = false
        vm.clearSearch()
    }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            suggestions = emptyList()
            showSuggestions = false
            return@LaunchedEffect
        }
        delay(300)
        val fetched = fetchSuggestions(query)
        suggestions = fetched
        showSuggestions = fetched.isNotEmpty() && results.isEmpty()
    }

    if (showLinkDialog) {
        PlayByLinkDialog(
            isDarkMode = isDarkMode,
            hapticsEnabled = hapticsEnabled,
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
                isSearching -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
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
                                        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
                                        // Only fill the query, do not search
                                        query = suggestion
                                        suggestions = emptyList()
                                        showSuggestions = false
                                        focusManager.clearFocus()
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
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true
                    ) {
                        items(results) { song ->
                            val isLiked = likedSongs.any { it.id == song.id }
                            val isPlaying = currentSong?.id == song.id
                            SongItem(
                                song = song,
                                isDarkMode = isDarkMode,
                                isLiked = isLiked,
                                isInQueue = false,
                                isPlaying = isPlaying,
                                showMenu = !song.isAlbum,
                                hapticsEnabled = hapticsEnabled,
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
        }

        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(
                    fontFamily = NothingFont,
                    color = textColor,
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic
                )) { append("Can't find the song?  ") }
                withStyle(SpanStyle(
                    fontFamily = NothingFont,
                    color = Color(0xFF1565C0),
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    textDecoration = TextDecoration.Underline
                )) { append("Try with YouTube link here.") }
            },
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .clickable {
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
                    showLinkDialog = true
                }
                .padding(horizontal = 20.dp, vertical = 8.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFDDDDDD)
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
                onBack()
            }) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = textColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (it.isNotBlank()) showSuggestions = true
                },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = {
                    Text(
                        "Search...",
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
                    if (query.isNotBlank()) {
                        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
                        showSuggestions = false
                        vm.search(query)
                        focusManager.clearFocus()
                    }
                })
            )

            IconButton(onClick = {
                if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
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

@Composable
fun PlayByLinkDialog(
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var link by remember { mutableStateOf("") }
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black

    Dialog(onDismissRequest = {
        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
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
                        Text(
                            "Paste YouTube link...",
                            fontFamily = NothingFont,
                            color = Color.Gray
                        )
                    },
                    textStyle = TextStyle(
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
                    TextButton(onClick = {
                        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
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
                                if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.KeyTap)
                                if (link.isNotBlank()) onPlay(link)
                            }
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
