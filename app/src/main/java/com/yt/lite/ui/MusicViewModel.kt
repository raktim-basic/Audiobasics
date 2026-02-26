package com.yt.lite.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.yt.lite.api.Innertube
import com.yt.lite.cache.CacheManager
import com.yt.lite.cache.CacheResult
import com.yt.lite.data.Album
import com.yt.lite.data.Song
import com.yt.lite.player.MusicService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@UnstableApi
class MusicViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ytlite", Context.MODE_PRIVATE)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null

    // Player state
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Queue
    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue

    // Search
    private val _searchResults = MutableStateFlow<List<Song>>(emptyList())
    val searchResults: StateFlow<List<Song>> = _searchResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    // Liked songs
    private val _likedSongs = MutableStateFlow<List<Song>>(loadLikedSongs())
    val likedSongs: StateFlow<List<Song>> = _likedSongs

    // Cache
    private val _cacheSize = MutableStateFlow("")
    val cacheSize: StateFlow<String> = _cacheSize

    private val _showStorageLow = MutableStateFlow(false)
    val showStorageLow: StateFlow<Boolean> = _showStorageLow

    // Bulk cache progress
    private val _cacheProgress = MutableStateFlow<Pair<Int, Int>?>(null) // done to total
    val cacheProgress: StateFlow<Pair<Int, Int>?> = _cacheProgress

    // Saved albums
    private val _savedAlbums = MutableStateFlow<List<Album>>(loadSavedAlbums())
    val savedAlbums: StateFlow<List<Album>> = _savedAlbums

    // Dark mode
    private val _isDarkMode = MutableStateFlow(prefs.getBoolean("dark_mode", false))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
            if (playing) startProgressTracking() else stopProgressTracking()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _isLoading.value = true
            val index = controller?.currentMediaItemIndex ?: return
            _currentSong.value = _queue.value.getOrNull(index) ?: mediaItem?.toSong()
            _currentPosition.value = 0L
            _duration.value = 0L
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    _isLoading.value = false
                    _duration.value = controller?.duration?.takeIf { it > 0 } ?: 0L
                }
                Player.STATE_ENDED -> {
                    _isPlaying.value = false
                    _isLoading.value = false
                    stopProgressTracking()
                }
                Player.STATE_BUFFERING -> {
                    _isLoading.value = true
                }
                else -> {}
            }
        }
    }

    init {
        val token = SessionToken(
            getApplication(),
            ComponentName(getApplication(), MusicService::class.java)
        )
        controllerFuture = MediaController.Builder(getApplication(), token).buildAsync()
        controllerFuture?.addListener(
            {
                try {
                    controller = controllerFuture?.get()
                    controller?.addListener(listener)
                    restoreStateFromController()
                } catch (e: Exception) {
                    Log.e("YTLite", "MediaController init failed", e)
                }
            },
            { it.run() }
        )
        refreshCacheSize()
    }

    private fun restoreStateFromController() {
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) return

        val restoredQueue = (0 until ctrl.mediaItemCount).mapNotNull { i ->
            ctrl.getMediaItemAt(i).toSong()
        }
        if (restoredQueue.isNotEmpty()) _queue.value = restoredQueue

        val currentIndex = ctrl.currentMediaItemIndex
        _currentSong.value = restoredQueue.getOrNull(currentIndex)
        _isPlaying.value = ctrl.isPlaying
        _duration.value = ctrl.duration.takeIf { it > 0 } ?: 0L
        _currentPosition.value = ctrl.currentPosition.coerceAtLeast(0L)
        if (ctrl.isPlaying) startProgressTracking()
    }

    private fun MediaItem.toSong(): Song? {
        val meta = mediaMetadata
        val id = mediaId.takeIf { it.isNotBlank() } ?: return null
        val title = meta.title?.toString() ?: return null
        val artist = meta.artist?.toString() ?: ""
        val thumbnail = meta.artworkUri?.toString() ?: ""
        return Song(id, title, artist, thumbnail)
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                controller?.let {
                    _currentPosition.value = it.currentPosition.coerceAtLeast(0L)
                    if (_duration.value <= 0L) {
                        _duration.value = it.duration.takeIf { d -> d > 0 } ?: 0L
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
    }

    fun seekTo(position: Long) {
        controller?.seekTo(position)
        _currentPosition.value = position
    }

    // Search
    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _isSearching.value = true
            try {
                _searchResults.value = Innertube.search(query)
            } catch (e: Exception) {
                Log.e("YTLite", "Search error: ${e.message}", e)
            } finally {
                _isSearching.value = false
            }
        }
    }

    // Play a single song from search — auto queues similar
    fun play(song: Song) {
        viewModelScope.launch {
            _currentSong.value = song
            _isLoading.value = true
            _error.value = null
            _queue.value = listOf(song)
            _currentPosition.value = 0L
            _duration.value = 0L

            try {
                val uri = resolveUri(song)
                val mediaItem = buildMediaItem(song, uri)
                controller?.run {
                    setMediaItem(mediaItem)
                    prepare()
                    play()
                } ?: Toast.makeText(getApplication(), "Player not ready", Toast.LENGTH_SHORT).show()

                // Fetch similar songs in background
                fetchAndQueueSimilar(song)

            } catch (e: Exception) {
                handlePlaybackError(e)
            }
        }
    }

    private suspend fun fetchAndQueueSimilar(song: Song) {
        try {
            val similar = Innertube.getRelatedSongs(getApplication(), song.id, 10)
            if (similar.isNotEmpty()) {
                val newQueue = listOf(song) + similar
                _queue.value = newQueue
                similar.forEach { s ->
                    val stubUri = "https://www.youtube.com/watch?v=${s.id}"
                    controller?.addMediaItem(buildMediaItem(s, stubUri))
                }
            }
        } catch (e: Exception) {
            Log.e("YTLite", "Failed to fetch similar: ${e.message}")
        }
    }

    // Play with a specific queue (liked, album, shuffle)
    fun playWithQueue(song: Song, queue: List<Song>) {
        viewModelScope.launch {
            _currentSong.value = song
            _isLoading.value = true
            _error.value = null
            _queue.value = queue
            _currentPosition.value = 0L
            _duration.value = 0L

            try {
                val songIndex = queue.indexOf(song).takeIf { it >= 0 } ?: 0
                val resolvedUri = resolveUri(song)

                val mediaItems = queue.mapIndexed { i, s ->
                    if (i == songIndex) {
                        buildMediaItem(s, resolvedUri)
                    } else {
                        val stubUri = if (CacheManager.isCached(getApplication(), s.id)) {
                            "file://${CacheManager.getCachedFilePath(getApplication(), s.id)}"
                        } else {
                            "https://www.youtube.com/watch?v=${s.id}"
                        }
                        buildMediaItem(s, stubUri)
                    }
                }

                controller?.run {
                    setMediaItems(mediaItems, songIndex, 0L)
                    prepare()
                    play()
                } ?: Toast.makeText(getApplication(), "Player not ready", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                handlePlaybackError(e)
            }
        }
    }

    // Play by YouTube URL — fetches real metadata
    fun playByUrl(url: String) {
        viewModelScope.launch {
            val videoId = extractVideoId(url)
            if (videoId == null) {
                Toast.makeText(getApplication(), "Invalid YouTube URL", Toast.LENGTH_SHORT).show()
                return@launch
            }

            _isLoading.value = true

            // Placeholder while loading
            val placeholder = Song(
                id = videoId,
                title = "Loading...",
                artist = "",
                thumbnail = "https://img.youtube.com/vi/$videoId/0.jpg"
            )
            _currentSong.value = placeholder
            _queue.value = listOf(placeholder)

            try {
                // Fetch real metadata and stream URL in parallel
                val metadata = Innertube.getVideoMetadata(videoId)
                val streamUrl = Innertube.getStreamUrl(getApplication(), videoId)

                if (streamUrl == null) {
                    Toast.makeText(getApplication(), "Could not load song", Toast.LENGTH_LONG).show()
                    _isLoading.value = false
                    return@launch
                }

                val song = metadata ?: placeholder.copy(title = "YouTube Song")
                _currentSong.value = song
                _queue.value = listOf(song)

                val mediaItem = buildMediaItem(song, streamUrl)
                controller?.run {
                    setMediaItem(mediaItem)
                    prepare()
                    play()
                }
            } catch (e: Exception) {
                handlePlaybackError(e)
            }
        }
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("v=([a-zA-Z0-9_-]{11})"),
            Regex("youtu\\.be/([a-zA-Z0-9_-]{11})"),
            Regex("embed/([a-zA-Z0-9_-]{11})")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    // Resolve URI — use cached file if available, else stub for service to resolve
    private fun resolveUri(song: Song): String {
        val cachedPath = CacheManager.getCachedFilePath(getApplication(), song.id)
        return if (cachedPath != null) {
            "file://$cachedPath"
        } else {
            "https://www.youtube.com/watch?v=${song.id}"
        }
    }

    private fun buildMediaItem(song: Song, uri: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(Uri.parse(song.thumbnail))
                    .build()
            )
            .build()
    }

    private fun handlePlaybackError(e: Exception) {
        val msg = e.message ?: "Unknown error"
        _error.value = msg
        _isLoading.value = false
        Log.e("YTLite", "Playback error: $msg", e)
        Toast.makeText(getApplication(), "Error: $msg", Toast.LENGTH_LONG).show()
    }

    // Queue management
    fun addToQueue(song: Song) {
        val newQueue = _queue.value.toMutableList()
        if (newQueue.none { it.id == song.id }) {
            newQueue.add(song)
            _queue.value = newQueue
            val uri = resolveUri(song)
            controller?.addMediaItem(buildMediaItem(song, uri))
            Toast.makeText(getApplication(), "Added to queue", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(getApplication(), "Already in queue", Toast.LENGTH_SHORT).show()
        }
    }

    fun playNext(song: Song) {
        val currentIndex = controller?.currentMediaItemIndex ?: 0
        val insertIndex = currentIndex + 1
        val newQueue = _queue.value.toMutableList()
        newQueue.removeAll { it.id == song.id }
        newQueue.add(insertIndex.coerceAtMost(newQueue.size), song)
        _queue.value = newQueue
        val uri = resolveUri(song)
        controller?.addMediaItem(insertIndex, buildMediaItem(song, uri))
        Toast.makeText(getApplication(), "Playing next", Toast.LENGTH_SHORT).show()
    }

    fun removeFromQueue(song: Song) {
        val index = _queue.value.indexOfFirst { it.id == song.id }
        if (index >= 0) {
            val newQueue = _queue.value.toMutableList()
            newQueue.removeAt(index)
            _queue.value = newQueue
            controller?.removeMediaItem(index)
        }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val newQueue = _queue.value.toMutableList()
        if (fromIndex < 0 || toIndex < 0 ||
            fromIndex >= newQueue.size || toIndex >= newQueue.size
        ) return
        val song = newQueue.removeAt(fromIndex)
        newQueue.add(toIndex, song)
        _queue.value = newQueue
        controller?.moveMediaItem(fromIndex, toIndex)
    }

    fun skipToNext() {
        _isLoading.value = true
        controller?.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        _isLoading.value = true
        controller?.seekToPreviousMediaItem()
    }

    fun togglePlayPause() {
        controller?.run { if (isPlaying) pause() else play() }
    }

    // Liked songs
    fun toggleLike(song: Song) {
        val isLiked = _likedSongs.value.any { it.id == song.id }
        if (isLiked) {
            unlike(song)
        } else {
            like(song)
        }
    }

    private fun like(song: Song) {
        val updatedSong = song.copy(isCached = false, cacheFailed = false)
        _likedSongs.value = listOf(updatedSong) + _likedSongs.value.filter { it.id != song.id }
        saveLikedSongs()
        viewModelScope.launch {
            cacheSongSilently(updatedSong)
        }
    }

    private fun unlike(song: Song) {
        _likedSongs.value = _likedSongs.value.filter { it.id != song.id }
        CacheManager.removeCachedSong(getApplication(), song.id)
        saveLikedSongs()
        refreshCacheSize()
    }

    private suspend fun cacheSongSilently(song: Song) {
        val result = CacheManager.cacheSong(getApplication(), song)
        when (result) {
            is CacheResult.Success -> {
                updateLikedSongCacheStatus(song.id, isCached = true, cacheFailed = false)
                refreshCacheSize()
                Log.d("YTLite", "Cached: ${song.title}")
            }
            is CacheResult.StorageLow -> {
                _showStorageLow.value = true
                updateLikedSongCacheStatus(song.id, isCached = false, cacheFailed = true)
            }
            is CacheResult.Failed -> {
                updateLikedSongCacheStatus(song.id, isCached = false, cacheFailed = true)
                Log.e("YTLite", "Cache failed for ${song.title}: ${result.reason}")
            }
        }
    }

    fun dismissStorageLow() {
        _showStorageLow.value = false
    }

    private fun updateLikedSongCacheStatus(id: String, isCached: Boolean, cacheFailed: Boolean) {
        _likedSongs.value = _likedSongs.value.map { song ->
            if (song.id == id) song.copy(isCached = isCached, cacheFailed = cacheFailed) else song
        }
        saveLikedSongs()
    }

    // Retry cache — only retries, does NOT unlike
    fun retryCache(song: Song) {
        viewModelScope.launch {
            updateLikedSongCacheStatus(song.id, isCached = false, cacheFailed = false)
            cacheSongSilently(song)
        }
    }

    // Cache all liked songs with progress
    fun cacheAllLiked() {
        val uncached = _likedSongs.value.filter { !it.isCached }
        if (uncached.isEmpty()) {
            Toast.makeText(getApplication(), "All songs already cached!", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            val total = uncached.size
            var done = 0
            _cacheProgress.value = Pair(0, total)

            createCacheNotificationChannel()

            uncached.forEach { song ->
                cacheSongSilently(song)
                done++
                _cacheProgress.value = Pair(done, total)
                updateCacheNotification(done, total)
            }

            _cacheProgress.value = null
            refreshCacheSize()
            cancelCacheNotification()
            Toast.makeText(
                getApplication(),
                "Caching complete!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun createCacheNotificationChannel() {
        val channel = NotificationChannel(
            "cache_channel",
            "Cache Progress",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun updateCacheNotification(done: Int, total: Int) {
        val percent = ((done.toFloat() / total.toFloat()) * 100).toInt()
        val manager = getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(getApplication(), "cache_channel")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Caching songs")
            .setContentText("$done / $total ($percent%)")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        manager.notify(1001, notification)
    }

    private fun cancelCacheNotification() {
        val manager = getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(1001)
    }

    fun shuffleLiked() {
        val shuffled = _likedSongs.value.shuffled()
        if (shuffled.isEmpty()) return
        playWithQueue(shuffled.first(), shuffled)
    }

    fun refreshCacheSize() {
        _cacheSize.value = CacheManager.getCacheSizeString(getApplication())
    }

    // Albums
    fun saveAlbum(album: Album) {
        if (_savedAlbums.value.none { it.id == album.id }) {
            _savedAlbums.value = listOf(album) + _savedAlbums.value
            saveSavedAlbums()
            Toast.makeText(getApplication(), "Album saved", Toast.LENGTH_SHORT).show()
        }
    }

    fun unsaveAlbum(album: Album) {
        _savedAlbums.value = _savedAlbums.value.filter { it.id != album.id }
        saveSavedAlbums()
        Toast.makeText(getApplication(), "Album removed", Toast.LENGTH_SHORT).show()
    }

    fun isAlbumSaved(albumId: String): Boolean {
        return _savedAlbums.value.any { it.id == albumId }
    }

    // Dark mode
    fun toggleDarkMode() {
        _isDarkMode.value = !_isDarkMode.value
        prefs.edit().putBoolean("dark_mode", _isDarkMode.value).apply()
    }

    // Persistence
    private fun saveLikedSongs() {
        val arr = JSONArray()
        _likedSongs.value.forEach { song ->
            val obj = JSONObject()
            obj.put("id", song.id)
            obj.put("title", song.title)
            obj.put("artist", song.artist)
            obj.put("thumbnail", song.thumbnail)
            obj.put("isCached", song.isCached)
            obj.put("cacheFailed", song.cacheFailed)
            arr.put(obj)
        }
        prefs.edit().putString("liked_songs", arr.toString()).apply()
    }

    private fun loadLikedSongs(): List<Song> {
        return try {
            val json = prefs.getString("liked_songs", "[]") ?: "[]"
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val isCached = CacheManager.isCached(
                    getApplication(), id
                )
                Song(
                    id = id,
                    title = obj.getString("title"),
                    artist = obj.getString("artist"),
                    thumbnail = obj.getString("thumbnail"),
                    isCached = isCached,
                    cacheFailed = if (isCached) false else obj.optBoolean("cacheFailed", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveSavedAlbums() {
        val arr = JSONArray()
        _savedAlbums.value.forEach { album ->
            val obj = JSONObject()
            obj.put("id", album.id)
            obj.put("title", album.title)
            obj.put("artist", album.artist)
            obj.put("thumbnail", album.thumbnail)
            obj.put("duration", album.duration)
            obj.put("songCount", album.songCount)
            obj.put("youtubeUrl", album.youtubeUrl)
            arr.put(obj)
        }
        prefs.edit().putString("saved_albums", arr.toString()).apply()
    }

    private fun loadSavedAlbums(): List<Album> {
        return try {
            val json = prefs.getString("saved_albums", "[]") ?: "[]"
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Album(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    artist = obj.getString("artist"),
                    thumbnail = obj.getString("thumbnail"),
                    duration = obj.optLong("duration", 0L),
                    songCount = obj.optInt("songCount", 0),
                    youtubeUrl = obj.optString("youtubeUrl", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Import / Export
    fun exportData(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val root = JSONObject()
                val songsArr = JSONArray()
                _likedSongs.value.forEach { song ->
                    val obj = JSONObject()
                    obj.put("id", song.id)
                    obj.put("title", song.title)
                    obj.put("artist", song.artist)
                    obj.put("thumbnail", song.thumbnail)
                    songsArr.put(obj)
                }
                val albumsArr = JSONArray()
                _savedAlbums.value.forEach { album ->
                    val obj = JSONObject()
                    obj.put("id", album.id)
                    obj.put("title", album.title)
                    obj.put("artist", album.artist)
                    obj.put("thumbnail", album.thumbnail)
                    obj.put("duration", album.duration)
                    obj.put("songCount", album.songCount)
                    obj.put("youtubeUrl", album.youtubeUrl)
                    albumsArr.put(obj)
                }
                root.put("liked_songs", songsArr)
                root.put("saved_albums", albumsArr)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(root.toString(2).toByteArray())
                }
                Toast.makeText(context, "Exported successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun importData(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: return@launch
                val root = JSONObject(text)

                val songsArr = root.getJSONArray("liked_songs")
                val songs = (0 until songsArr.length()).map { i ->
                    val obj = songsArr.getJSONObject(i)
                    Song(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        artist = obj.getString("artist"),
                        thumbnail = obj.getString("thumbnail"),
                        isCached = false,
                        cacheFailed = true
                    )
                }

                val albumsArr = root.getJSONArray("saved_albums")
                val albums = (0 until albumsArr.length()).map { i ->
                    val obj = albumsArr.getJSONObject(i)
                    Album(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        artist = obj.getString("artist"),
                        thumbnail = obj.getString("thumbnail"),
                        duration = obj.optLong("duration", 0L),
                        songCount = obj.optInt("songCount", 0),
                        youtubeUrl = obj.optString("youtubeUrl", "")
                    )
                }

                _likedSongs.value = songs
                _savedAlbums.value = albums
                saveLikedSongs()
                saveSavedAlbums()

                Toast.makeText(
                    context,
                    "Imported ${songs.size} songs and ${albums.size} albums",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Import failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCleared() {
        stopProgressTracking()
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
