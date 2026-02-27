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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

@UnstableApi
class MusicViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ytlite", Context.MODE_PRIVATE)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var syncJob: Job? = null
    private var cacheAllJob: Job? = null

    // Semaphore: max 3 parallel downloads
    private val cacheSemaphore = Semaphore(3)

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

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue

    private val _searchResults = MutableStateFlow<List<Song>>(emptyList())
    val searchResults: StateFlow<List<Song>> = _searchResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _likedSongs = MutableStateFlow<List<Song>>(loadLikedSongs())
    val likedSongs: StateFlow<List<Song>> = _likedSongs

    private val _cacheSize = MutableStateFlow("")
    val cacheSize: StateFlow<String> = _cacheSize

    private val _showStorageLow = MutableStateFlow(false)
    val showStorageLow: StateFlow<Boolean> = _showStorageLow

    private val _cacheProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val cacheProgress: StateFlow<Pair<Int, Int>?> = _cacheProgress

    private val _savedAlbums = MutableStateFlow<List<Album>>(loadSavedAlbums())
    val savedAlbums: StateFlow<List<Album>> = _savedAlbums

    private val _isDarkMode = MutableStateFlow(prefs.getBoolean("dark_mode", false))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
            if (playing) {
                _isLoading.value = false
                startProgressTracking()
            } else {
                stopProgressTracking()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _isLoading.value = true
            _currentPosition.value = 0L
            _duration.value = 0L
            val mediaId = mediaItem?.mediaId
            val songFromQueue = if (mediaId != null)
                _queue.value.firstOrNull { it.id == mediaId }
            else null
            _currentSong.value = songFromQueue ?: mediaItem?.toSong()
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    _isLoading.value = false
                    _duration.value = controller?.duration?.takeIf { it > 0 } ?: 0L
                    _currentPosition.value =
                        controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                }
                Player.STATE_ENDED -> {
                    _isPlaying.value = false
                    _isLoading.value = false
                    stopProgressTracking()
                }
                Player.STATE_BUFFERING -> _isLoading.value = true
                else -> {}
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            syncStateFromController()
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
                    startPeriodicSync()
                } catch (e: Exception) {
                    Log.e("YTLite", "MediaController init failed", e)
                }
            },
            { it.run() }
        )
        refreshCacheSize()
    }

    fun syncState() {
        syncStateFromController()
    }

    private fun syncStateFromController() {
        val ctrl = controller ?: return
        val mediaId = ctrl.currentMediaItem?.mediaId
        if (mediaId != null) {
            val songFromQueue = _queue.value.firstOrNull { it.id == mediaId }
            if (songFromQueue != null && _currentSong.value?.id != mediaId) {
                _currentSong.value = songFromQueue
            } else if (songFromQueue == null) {
                val song = ctrl.currentMediaItem?.toSong()
                if (song != null && _currentSong.value?.id != song.id) {
                    _currentSong.value = song
                }
            }
        }
        val playing = ctrl.isPlaying
        val buffering = ctrl.playbackState == Player.STATE_BUFFERING
        if (_isPlaying.value != playing) _isPlaying.value = playing
        if (_isLoading.value != buffering) _isLoading.value = buffering
        val dur = ctrl.duration.takeIf { it > 0 } ?: 0L
        if (_duration.value != dur) _duration.value = dur
        _currentPosition.value = ctrl.currentPosition.coerceAtLeast(0L)
        if (playing) startProgressTracking() else stopProgressTracking()
    }

    private fun restoreStateFromController() {
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) return
        val restoredQueue = (0 until ctrl.mediaItemCount).mapNotNull { i ->
            ctrl.getMediaItemAt(i).toSong()
        }
        if (restoredQueue.isNotEmpty()) _queue.value = restoredQueue
        syncStateFromController()
    }

    private fun startPeriodicSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                syncStateFromController()
            }
        }
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
        if (progressJob?.isActive == true) return
        progressJob = viewModelScope.launch {
            while (true) {
                controller?.let { ctrl ->
                    if (ctrl.isPlaying) {
                        _currentPosition.value = ctrl.currentPosition.coerceAtLeast(0L)
                        val dur = ctrl.duration.takeIf { it > 0 } ?: 0L
                        if (_duration.value != dur) _duration.value = dur
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    fun seekTo(position: Long) {
        controller?.seekTo(position)
        _currentPosition.value = position
    }

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

    fun play(song: Song) {
        viewModelScope.launch {
            _currentSong.value = song
            _isLoading.value = true
            _error.value = null
            _queue.value = listOf(song)
            _currentPosition.value = 0L
            _duration.value = 0L
            try {
                controller?.run {
                    setMediaItem(buildMediaItem(song, resolveUri(song)))
                    prepare()
                    play()
                } ?: Toast.makeText(
                    getApplication(), "Player not ready", Toast.LENGTH_SHORT
                ).show()
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
                    controller?.addMediaItem(buildMediaItem(s, resolveUri(s)))
                }
            }
        } catch (e: Exception) {
            Log.e("YTLite", "Failed to fetch similar: ${e.message}")
        }
    }

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
                val mediaItems = queue.map { s -> buildMediaItem(s, resolveUri(s)) }
                controller?.run {
                    setMediaItems(mediaItems, songIndex, 0L)
                    prepare()
                    play()
                } ?: Toast.makeText(
                    getApplication(), "Player not ready", Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                handlePlaybackError(e)
            }
        }
    }

    fun playByUrl(url: String) {
        viewModelScope.launch {
            val videoId = extractVideoId(url)
            if (videoId == null) {
                Toast.makeText(getApplication(), "Invalid YouTube URL", Toast.LENGTH_SHORT).show()
                return@launch
            }
            _isLoading.value = true
            val placeholder = Song(
                id = videoId,
                title = "Loading...",
                artist = "",
                thumbnail = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
            )
            _currentSong.value = placeholder
            _queue.value = listOf(placeholder)
            try {
                val metadata = Innertube.getVideoMetadata(videoId)
                val streamUrl = Innertube.getStreamUrl(getApplication(), videoId)
                if (streamUrl == null) {
                    Toast.makeText(
                        getApplication(), "Could not load song", Toast.LENGTH_LONG
                    ).show()
                    _isLoading.value = false
                    return@launch
                }
                val song = metadata ?: placeholder.copy(title = "YouTube Song")
                _currentSong.value = song
                _queue.value = listOf(song)
                controller?.run {
                    setMediaItem(buildMediaItem(song, streamUrl))
                    prepare()
                    play()
                }
            } catch (e: Exception) {
                handlePlaybackError(e)
            }
        }
    }

    private fun extractVideoId(url: String): String? {
        listOf(
            Regex("v=([a-zA-Z0-9_-]{11})"),
            Regex("youtu\\.be/([a-zA-Z0-9_-]{11})"),
            Regex("embed/([a-zA-Z0-9_-]{11})")
        ).forEach { it.find(url)?.let { m -> return m.groupValues[1] } }
        return null
    }

    private fun resolveUri(song: Song): String {
        val cached = CacheManager.getCachedFilePath(getApplication(), song.id)
        return if (cached != null) "file://$cached"
        else "https://www.youtube.com/watch?v=${song.id}"
    }

    private fun resolveArtworkUri(song: Song): Uri {
        val cached = CacheManager.getCachedThumbPath(getApplication(), song.id)
        return if (cached != null) Uri.parse("file://$cached")
        else Uri.parse(song.thumbnail)
    }

    private fun buildMediaItem(song: Song, uri: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(resolveArtworkUri(song))
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

    fun addToQueue(song: Song) {
        val newQueue = _queue.value.toMutableList()
        if (newQueue.none { it.id == song.id }) {
            newQueue.add(song)
            _queue.value = newQueue
            controller?.addMediaItem(buildMediaItem(song, resolveUri(song)))
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
        controller?.addMediaItem(insertIndex, buildMediaItem(song, resolveUri(song)))
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
            fromIndex >= newQueue.size || toIndex >= newQueue.size) return
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

    // Like — immediately kicks off aggressive cache
    fun toggleLike(song: Song) {
        if (_likedSongs.value.any { it.id == song.id }) unlike(song) else like(song)
    }

    private fun like(song: Song) {
        val updatedSong = song.copy(isCached = false, cacheFailed = false)
        _likedSongs.value = listOf(updatedSong) +
                _likedSongs.value.filter { it.id != song.id }
        saveLikedSongs()
        // Immediately start caching — no delay
        viewModelScope.launch {
            cacheSemaphore.withPermit {
                cacheSongSilently(updatedSong)
            }
        }
    }

    private fun unlike(song: Song) {
        _likedSongs.value = _likedSongs.value.filter { it.id != song.id }
        CacheManager.removeCachedSong(getApplication(), song.id)
        saveLikedSongs()
        refreshCacheSize()
    }

    // Returns true on success
    private suspend fun cacheSongSilently(song: Song): Boolean {
        return try {
            when (val result = CacheManager.cacheSong(getApplication(), song)) {
                is CacheResult.Success -> {
                    updateLikedSongCacheStatus(song.id, isCached = true, cacheFailed = false)
                    refreshCacheSize()
                    true
                }
                is CacheResult.StorageLow -> {
                    _showStorageLow.value = true
                    updateLikedSongCacheStatus(song.id, isCached = false, cacheFailed = true)
                    false
                }
                is CacheResult.Failed -> {
                    updateLikedSongCacheStatus(song.id, isCached = false, cacheFailed = true)
                    Log.e("YTLite", "Cache failed ${song.title}: ${result.reason}")
                    false
                }
            }
        } catch (e: Exception) {
            updateLikedSongCacheStatus(song.id, isCached = false, cacheFailed = true)
            Log.e("YTLite", "Cache exception ${song.title}: ${e.message}")
            false
        }
    }

    fun dismissStorageLow() { _showStorageLow.value = false }

    private fun updateLikedSongCacheStatus(
        id: String, isCached: Boolean, cacheFailed: Boolean
    ) {
        _likedSongs.value = _likedSongs.value.map { s ->
            if (s.id == id) s.copy(isCached = isCached, cacheFailed = cacheFailed) else s
        }
        saveLikedSongs()
    }

    // Retry — wipe partial, clear state, re-cache immediately
    fun retryCache(song: Song) {
        updateLikedSongCacheStatus(song.id, isCached = false, cacheFailed = false)
        CacheManager.removeCachedSong(getApplication(), song.id)
        viewModelScope.launch {
            cacheSemaphore.withPermit {
                val success = cacheSongSilently(song)
                if (!success) {
                    updateLikedSongCacheStatus(song.id, isCached = false, cacheFailed = true)
                }
            }
        }
    }

    // Cache all — 3 parallel downloads with live progress
    fun cacheAllLiked() {
        if (_cacheProgress.value != null) {
            Toast.makeText(getApplication(), "Already caching...", Toast.LENGTH_SHORT).show()
            return
        }

        val uncached = _likedSongs.value.filter { !it.isCached }
        if (uncached.isEmpty()) {
            Toast.makeText(getApplication(), "All songs cached!", Toast.LENGTH_SHORT).show()
            return
        }

        cacheAllJob = viewModelScope.launch {
            createCacheNotificationChannel()
            val total = uncached.size
            var done = 0
            _cacheProgress.value = Pair(0, total)
            updateCacheNotification(0, total)

            try {
                coroutineScope {
                    uncached.map { song ->
                        async {
                            // Clear any previous failed state
                            updateLikedSongCacheStatus(
                                song.id, isCached = false, cacheFailed = false
                            )
                            cacheSemaphore.withPermit {
                                cacheSongSilently(song)
                            }
                            // Update progress atomically
                            synchronized(this@MusicViewModel) { done++ }
                            _cacheProgress.value = Pair(done, total)
                            updateCacheNotification(done, total)
                        }
                    }.forEach { it.await() }
                }
            } finally {
                _cacheProgress.value = null
                refreshCacheSize()
                cancelCacheNotification()
                Toast.makeText(
                    getApplication(), "Caching complete!", Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun createCacheNotificationChannel() {
        val channel = NotificationChannel(
            "cache_channel", "Cache Progress", NotificationManager.IMPORTANCE_LOW
        )
        (getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun updateCacheNotification(done: Int, total: Int) {
        val percent = if (total > 0) ((done.toFloat() / total) * 100).toInt() else 0
        val mgr = getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(getApplication(), "cache_channel")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Caching songs")
            .setContentText("$done / $total ($percent%)")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        mgr.notify(1001, n)
    }

    private fun cancelCacheNotification() {
        (getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(1001)
    }

    fun shuffleLiked() {
        val shuffled = _likedSongs.value.shuffled()
        if (shuffled.isEmpty()) return
        playWithQueue(shuffled.first(), shuffled)
    }

    fun refreshCacheSize() {
        _cacheSize.value = CacheManager.getCacheSizeString(getApplication())
    }

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

    fun isAlbumSaved(albumId: String) = _savedAlbums.value.any { it.id == albumId }

    fun toggleDarkMode() {
        _isDarkMode.value = !_isDarkMode.value
        prefs.edit().putBoolean("dark_mode", _isDarkMode.value).apply()
    }

    private fun saveLikedSongs() {
        val arr = JSONArray()
        _likedSongs.value.forEach { song ->
            arr.put(JSONObject().apply {
                put("id", song.id)
                put("title", song.title)
                put("artist", song.artist)
                put("thumbnail", song.thumbnail)
                put("isCached", song.isCached)
                put("cacheFailed", song.cacheFailed)
                put("isExplicit", song.isExplicit)
            })
        }
        prefs.edit().putString("liked_songs", arr.toString()).apply()
    }

    private fun loadLikedSongs(): List<Song> {
        return try {
            val arr = JSONArray(prefs.getString("liked_songs", "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val isCached = CacheManager.isCached(getApplication(), id)
                Song(
                    id = id,
                    title = obj.getString("title"),
                    artist = obj.getString("artist"),
                    thumbnail = obj.getString("thumbnail"),
                    isCached = isCached,
                    cacheFailed = if (isCached) false
                    else obj.optBoolean("cacheFailed", false),
                    isExplicit = obj.optBoolean("isExplicit", false)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveSavedAlbums() {
        val arr = JSONArray()
        _savedAlbums.value.forEach { album ->
            arr.put(JSONObject().apply {
                put("id", album.id)
                put("title", album.title)
                put("artist", album.artist)
                put("thumbnail", album.thumbnail)
                put("duration", album.duration)
                put("songCount", album.songCount)
                put("youtubeUrl", album.youtubeUrl)
            })
        }
        prefs.edit().putString("saved_albums", arr.toString()).apply()
    }

    private fun loadSavedAlbums(): List<Album> {
        return try {
            val arr = JSONArray(prefs.getString("saved_albums", "[]") ?: "[]")
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
        } catch (_: Exception) { emptyList() }
    }

    fun exportData(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val root = JSONObject()
                val songsArr = JSONArray()
                _likedSongs.value.forEach { song ->
                    songsArr.put(JSONObject().apply {
                        put("id", song.id)
                        put("title", song.title)
                        put("artist", song.artist)
                        put("thumbnail", song.thumbnail)
                    })
                }
                val albumsArr = JSONArray()
                _savedAlbums.value.forEach { album ->
                    albumsArr.put(JSONObject().apply {
                        put("id", album.id)
                        put("title", album.title)
                        put("artist", album.artist)
                        put("thumbnail", album.thumbnail)
                        put("duration", album.duration)
                        put("songCount", album.songCount)
                        put("youtubeUrl", album.youtubeUrl)
                    })
                }
                root.put("liked_songs", songsArr)
                root.put("saved_albums", albumsArr)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(root.toString(2).toByteArray())
                }
                Toast.makeText(context, "Exported!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context, "Export failed: ${e.message}", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun importData(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val text = context.contentResolver
                    .openInputStream(uri)?.bufferedReader()?.readText() ?: return@launch
                val root = JSONObject(text)
                val songs = (0 until root.getJSONArray("liked_songs").length()).map { i ->
                    val obj = root.getJSONArray("liked_songs").getJSONObject(i)
                    Song(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        artist = obj.getString("artist"),
                        thumbnail = obj.getString("thumbnail"),
                        isCached = false,
                        cacheFailed = true
                    )
                }
                val albums = (0 until root.getJSONArray("saved_albums").length()).map { i ->
                    val obj = root.getJSONArray("saved_albums").getJSONObject(i)
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
                    "Imported ${songs.size} songs, ${albums.size} albums",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context, "Import failed: ${e.message}", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCleared() {
        stopProgressTracking()
        syncJob?.cancel()
        cacheAllJob?.cancel()
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
