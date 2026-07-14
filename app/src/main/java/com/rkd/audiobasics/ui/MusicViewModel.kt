package com.rkd.audiobasics.ui

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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.rkd.audiobasics.api.Innertube
import com.rkd.audiobasics.cache.CacheManager
import com.rkd.audiobasics.lyrics.LyricsCache
import com.rkd.audiobasics.lyrics.LyricsRepository
import com.rkd.audiobasics.cache.CacheResult
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.data.db.AppDatabase
import com.rkd.audiobasics.data.db.PlaylistEntity
import com.rkd.audiobasics.data.db.PlaylistSongEntity
import com.rkd.audiobasics.player.MusicService
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import timber.log.Timber
import org.json.JSONObject
import java.util.UUID

@UnstableApi
class MusicViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ytlite", Context.MODE_PRIVATE)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null
    private var syncJob: Job? = null
    private var cacheAllJob: Job? = null

    private val cacheSemaphore = Semaphore(3)
    private val _cachingSongIds = MutableStateFlow<Set<String>>(emptySet())
    /** Song IDs currently being actively downloaded, across every download source (like, retry, download-all, etc). */
    val cachingSongIds: StateFlow<Set<String>> = _cachingSongIds

    // ── Room DB ───────────────────────────────────────────────────────────────
    private val db = AppDatabase.getInstance(app)
    private val playlistDao = db.playlistDao()

    // ── Player state ──────────────────────────────────────────────────────────
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

    private val _repeatMode = MutableStateFlow(prefs.getInt("repeat_mode", 0))
    val repeatMode: StateFlow<Int> = _repeatMode

    // ── Sleep timer ──────────────────────────────────────────────────────────
    // SLEEP_TIMER_OFF: no timer running.
    // SLEEP_TIMER_CUSTOM: counting down to a fixed wall-clock deadline (sleepTimerEndsAt).
    // SLEEP_TIMER_END_OF_SONG: pauses when the current song finishes; tracked by watching
    // currentPosition vs duration rather than a deadline, so scrubbing doesn't desync it.
    private val _sleepTimerMode = MutableStateFlow(SLEEP_TIMER_OFF)
    val sleepTimerMode: StateFlow<Int> = _sleepTimerMode

    private val _sleepTimerRemaining = MutableStateFlow(0L)
    val sleepTimerRemaining: StateFlow<Long> = _sleepTimerRemaining

    private var sleepTimerEndsAt: Long? = null
    private var sleepTimerPausedRemaining: Long? = null
    private var sleepTimerJob: Job? = null
    private var sleepTimerSongWatcherJob: Job? = null

    // ── Search ────────────────────────────────────────────────────────────────
    private val _searchResults = MutableStateFlow<List<Song>>(emptyList())
    val searchResults: StateFlow<List<Song>> = _searchResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // ── Liked songs (SharedPrefs) ─────────────────────────────────────────────
    private val _likedSongs = MutableStateFlow<List<Song>>(loadLikedSongs())
    val likedSongs: StateFlow<List<Song>> = _likedSongs

    // ── Custom playlists (Room) ───────────────────────────────────────────────
    val customPlaylists: StateFlow<List<PlaylistEntity>> = playlistDao
        .observePlaylists()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // songs loaded for a specific custom playlist (set when user opens it)
    private val _openPlaylistSongs = MutableStateFlow<List<PlaylistSongEntity>>(emptyList())
    val openPlaylistSongs: StateFlow<List<PlaylistSongEntity>> = _openPlaylistSongs

    private val _openPlaylistId = MutableStateFlow<String?>(null)
    val openPlaylistId: StateFlow<String?> = _openPlaylistId

    // ── Cache / Storage ───────────────────────────────────────────────────────
    private val _cacheSize = MutableStateFlow("")
    val cacheSize: StateFlow<String> = _cacheSize

    private val _showStorageLow = MutableStateFlow(false)
    val showStorageLow: StateFlow<Boolean> = _showStorageLow

    private val _cacheProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val cacheProgress: StateFlow<Pair<Int, Int>?> = _cacheProgress

    // ── Saved albums ──────────────────────────────────────────────────────────
    private val _savedAlbums = MutableStateFlow<List<Album>>(loadSavedAlbums())
    val savedAlbums: StateFlow<List<Album>> = _savedAlbums

    // Persisted tracklists for saved albums (songId list + metadata per album), so a saved
    // album can be opened and browsed fully offline without needing a live Innertube call.
    // Keyed by albumId.
    private val _savedAlbumSongs = MutableStateFlow<Map<String, List<Song>>>(loadSavedAlbumSongs())
    val savedAlbumSongs: StateFlow<Map<String, List<Song>>> = _savedAlbumSongs

    // Persistent (SharedPrefs-backed) cache of album metadata resolved via a live lookup —
    // populated automatically as songs are played or cached for offline use, so Song Info
    // (and offline playback) can show the album without a network call. Keyed by albumId.
    private val _resolvedAlbumCache = MutableStateFlow<Map<String, Album>>(loadResolvedAlbumCache())
    val resolvedAlbumCache: StateFlow<Map<String, Album>> = _resolvedAlbumCache

    fun cacheResolvedAlbum(album: Album) {
        if (album.id.isBlank() || album.title.isBlank()) return
        if (_resolvedAlbumCache.value[album.id]?.title == album.title) return // no-op, avoid redundant writes
        _resolvedAlbumCache.value = _resolvedAlbumCache.value + (album.id to album)
        saveResolvedAlbumCache()
    }

    // Resolves an album's metadata in the background if not already known, and persists it.
    // Safe to call repeatedly — no-ops instantly if already resolved or already in flight.
    private val resolvingAlbumIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private fun resolveAlbumInBackground(albumId: String, fallbackArtist: String = "") {
        if (albumId.isBlank()) return
        if (_resolvedAlbumCache.value.containsKey(albumId)) return
        if (_savedAlbums.value.any { it.id == albumId }) return
        if (!resolvingAlbumIds.add(albumId)) return // already resolving
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (meta, _) = Innertube.getAlbumSongs(albumId, fallbackArtist, caller = "autoResolve")
                if (meta != null && meta.title.isNotBlank()) {
                    cacheResolvedAlbum(meta.copy(id = albumId))
                }
            } catch (e: Exception) {
                Log.e("YTLite", "Background album resolve failed for $albumId: ${e.message}")
            } finally {
                resolvingAlbumIds.remove(albumId)
            }
        }
    }

    fun normalizeAlbumTitle(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]"), "")

    // ── Settings ──────────────────────────────────────────────────────────────
    // Theme: stored preference is one of THEME_SYSTEM / THEME_LIGHT / THEME_DARK.
    // isDarkMode stays the resolved boolean every screen already reads — screens never
    // need to know about "system" mode, they just get the effective light/dark value.
    private fun systemIsDarkMode(): Boolean {
        val uiMode = getApplication<Application>().resources.configuration.uiMode
        return (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private val _themeMode = MutableStateFlow(prefs.getString("theme_mode", THEME_SYSTEM) ?: THEME_SYSTEM)
    val themeMode: StateFlow<String> = _themeMode

    private val _isDarkMode = MutableStateFlow(resolveDarkMode(_themeMode.value))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode

    private fun resolveDarkMode(mode: String): Boolean = when (mode) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> systemIsDarkMode() // THEME_SYSTEM or any unrecognized value
    }

    fun setThemeMode(mode: String) {
        if (mode != THEME_SYSTEM && mode != THEME_LIGHT && mode != THEME_DARK) return
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode).apply()
        _isDarkMode.value = resolveDarkMode(mode)
    }

    // Only relevant while themeMode == THEME_SYSTEM: re-resolves isDarkMode whenever the
    // system's night-mode setting changes (e.g. scheduled dark mode, quick-settings toggle,
    // battery saver auto dark mode) without requiring the app/ViewModel to restart.
    private val systemThemeCallback = object : android.content.ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            if (_themeMode.value == THEME_SYSTEM) {
                val nowDark = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                if (_isDarkMode.value != nowDark) {
                    _isDarkMode.value = nowDark
                }
            }
        }
        override fun onLowMemory() {}
        override fun onTrimMemory(level: Int) {}
    }

    init {
        getApplication<Application>().registerComponentCallbacks(systemThemeCallback)
    }

    private val _hapticsEnabled = MutableStateFlow(prefs.getBoolean("haptics_enabled", true))
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled

    private val _logsEnabled = MutableStateFlow(prefs.getBoolean("logs_enabled", false))
    val logsEnabled: StateFlow<Boolean> = _logsEnabled

    private val _navigateToUpdater = MutableStateFlow(false)
    val navigateToUpdater: StateFlow<Boolean> = _navigateToUpdater

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable

    // ── "Add to..." sheet ─────────────────────────────────────────────────────
    private val _addToSheetSong = MutableStateFlow<Song?>(null)
    val addToSheetSong: StateFlow<Song?> = _addToSheetSong

    private var fallbackRetryCount = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Player listener
    // ─────────────────────────────────────────────────────────────────────────

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
            fallbackRetryCount = 0
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
                    fallbackRetryCount = 0
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

        override fun onPlayerError(error: PlaybackException) {
            Timber.e("Player error ❌ code=${error.errorCode} | ${error.message} | cause=${error.cause?.message} | rootCause=${error.cause?.cause?.message}")

            if (fallbackRetryCount < 1) {
                fallbackRetryCount++
                Timber.w("Fallback retry attempt #$fallbackRetryCount")
                _isLoading.value = true

                val currentSong = _currentSong.value
                val ctrl = controller
                if (currentSong != null && ctrl != null) {
                    val currentIndex = ctrl.currentMediaItemIndex
                    val currentPos = ctrl.currentPosition.coerceAtLeast(0L)
                    val fallbackUri = resolveUri(currentSong, forceFallback = true)
                    val newMediaItem = buildMediaItem(currentSong, fallbackUri)
                    ctrl.replaceMediaItem(currentIndex, newMediaItem)
                    ctrl.seekTo(currentIndex, currentPos)
                    ctrl.prepare()
                    ctrl.play()
                }
            } else {
                Timber.e("Fallback exhausted — giving up ❌")
                _isLoading.value = false
                _isPlaying.value = false
                _error.value = "Stream failed. Please try another song."
                Toast.makeText(getApplication(), "Stream unavailable right now", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val mapped = when (repeatMode) {
                Player.REPEAT_MODE_ALL -> 1
                Player.REPEAT_MODE_ONE -> 2
                else -> 0
            }
            if (_repeatMode.value != mapped) {
                _repeatMode.value = mapped
                prefs.edit().putInt("repeat_mode", mapped).apply()
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
        checkForUpdate()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Player controls
    // ─────────────────────────────────────────────────────────────────────────

    fun syncState() { syncStateFromController() }

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
        val mappedRepeat = when (ctrl.repeatMode) {
            Player.REPEAT_MODE_ALL -> 1
            Player.REPEAT_MODE_ONE -> 2
            else -> 0
        }
        if (_repeatMode.value != mappedRepeat) {
            _repeatMode.value = mappedRepeat
            prefs.edit().putInt("repeat_mode", mappedRepeat).apply()
        }
        if (playing) startProgressTracking() else stopProgressTracking()
    }

    private fun restoreStateFromController() {
        val ctrl = controller ?: return
        val savedRepeat = prefs.getInt("repeat_mode", 0)
        ctrl.repeatMode = when (savedRepeat) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        _repeatMode.value = savedRepeat
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

    fun clearSearch() {
        _searchResults.value = emptyList()
        _isSearching.value = false
        _searchQuery.value = ""
    }

    fun clearSearchResultsOnly() {
        _searchResults.value = emptyList()
        _isSearching.value = false
    }

    fun play(song: Song) {
        viewModelScope.launch {
            fallbackRetryCount = 0
            _currentSong.value = song
            _isLoading.value = true
            _error.value = null
            _queue.value = listOf(song)
            _currentPosition.value = 0L
            _duration.value = 0L
            if (song.albumId.isNotBlank()) resolveAlbumInBackground(song.albumId, song.artist)
            try {
                controller?.run {
                    setMediaItem(buildMediaItem(song, resolveUri(song)))
                    prepare()
                    play()
                } ?: Toast.makeText(getApplication(), "Player not ready", Toast.LENGTH_SHORT).show()
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
                val ctrl = controller ?: return
                similar.forEach { s -> ctrl.addMediaItem(buildMediaItem(s, resolveUri(s))) }
                Log.d("YTLite", "Queued ${similar.size} related songs")
            }
        } catch (e: Exception) {
            Log.e("YTLite", "Failed to fetch similar: ${e.message}")
        }
    }

    fun playWithQueue(song: Song, queue: List<Song>) {
        viewModelScope.launch {
            fallbackRetryCount = 0
            _currentSong.value = song
            _isLoading.value = true
            _error.value = null
            _queue.value = queue
            _currentPosition.value = 0L
            _duration.value = 0L
            if (song.albumId.isNotBlank()) resolveAlbumInBackground(song.albumId, song.artist)
            try {
                val songIndex = queue.indexOf(song).takeIf { it >= 0 } ?: 0
                val mediaItems = queue.map { s -> buildMediaItem(s, resolveUri(s)) }
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

    fun playByUrl(url: String) {
        viewModelScope.launch {
            fallbackRetryCount = 0
            val videoId = extractVideoId(url)
            if (videoId == null) {
                Toast.makeText(getApplication(), "Invalid YouTube URL", Toast.LENGTH_SHORT).show()
                return@launch
            }
            _isLoading.value = true
            val placeholder = Song(
                id = videoId, title = "Loading...", artist = "",
                // hqdefault always exists; maxresdefault 404s for many videos, which was
                // causing cover art to intermittently stay blank on Play with Link.
                thumbnail = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
            )
            _currentSong.value = placeholder
            _queue.value = listOf(placeholder)
            try {
                val metadata = Innertube.getVideoMetadata(videoId)
                val streamUrl = Innertube.getStreamUrl(getApplication(), videoId)?.first
                if (streamUrl == null) {
                    Toast.makeText(getApplication(), "Could not load song", Toast.LENGTH_LONG).show()
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

    private fun resolveUri(song: Song, forceFallback: Boolean = false): String {
        val cached = CacheManager.getCachedFilePath(getApplication(), song.id)
        return if (cached != null) "file://$cached"
        else "https://www.youtube.com/watch?v=${song.id}${if (forceFallback) "&fallback=true" else ""}"
    }

    private fun resolveArtworkUri(song: Song): Uri {
        val cached = CacheManager.getCachedThumbPath(getApplication(), song.id)
        return if (cached != null) Uri.parse("file://$cached") else Uri.parse(song.thumbnail)
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

    fun skipToNext() { _isLoading.value = true; controller?.seekToNextMediaItem() }
    fun skipToPrevious() { _isLoading.value = true; controller?.seekToPreviousMediaItem() }
    fun togglePlayPause() { controller?.run { if (isPlaying) pause() else play() } }

    fun toggleRepeatMode() {
        val next = (_repeatMode.value + 1) % 3
        _repeatMode.value = next
        prefs.edit().putInt("repeat_mode", next).apply()
        controller?.repeatMode = when (next) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sleep timer
    // ─────────────────────────────────────────────────────────────────────────

    fun startCustomSleepTimer(minutes: Long) {
        cancelSleepTimer()
        _sleepTimerMode.value = SLEEP_TIMER_CUSTOM
        sleepTimerEndsAt = System.currentTimeMillis() + minutes * 60_000L
        runCustomSleepTimerLoop()
    }

    fun startEndOfSongSleepTimer() {
        cancelSleepTimer()
        _sleepTimerMode.value = SLEEP_TIMER_END_OF_SONG
        _sleepTimerRemaining.value = (_duration.value - _currentPosition.value).coerceAtLeast(0L)
        runEndOfSongSleepTimerWatcher()
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerSongWatcherJob?.cancel()
        sleepTimerSongWatcherJob = null
        sleepTimerEndsAt = null
        sleepTimerPausedRemaining = null
        _sleepTimerMode.value = SLEEP_TIMER_OFF
        _sleepTimerRemaining.value = 0L
    }

    private fun runCustomSleepTimerLoop() {
        sleepTimerJob?.cancel()
        sleepTimerJob = viewModelScope.launch {
            while (true) {
                val endAt = sleepTimerEndsAt
                if (endAt == null || _sleepTimerMode.value != SLEEP_TIMER_CUSTOM) return@launch

                if (!_isPlaying.value) {
                    // Paused: freeze the remaining time instead of letting it drain.
                    if (sleepTimerPausedRemaining == null) {
                        sleepTimerPausedRemaining = (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
                    }
                    delay(300)
                    continue
                }
                val paused = sleepTimerPausedRemaining
                if (paused != null) {
                    sleepTimerEndsAt = System.currentTimeMillis() + paused
                    sleepTimerPausedRemaining = null
                    continue
                }

                val remaining = endAt - System.currentTimeMillis()
                if (remaining <= 0) {
                    _sleepTimerRemaining.value = 0L
                    if (_isPlaying.value) togglePlayPause()
                    cancelSleepTimer()
                    return@launch
                }
                _sleepTimerRemaining.value = remaining
                delay(1000)
            }
        }
    }

    private fun runEndOfSongSleepTimerWatcher() {
        sleepTimerSongWatcherJob?.cancel()
        val watchedSongId = _currentSong.value?.id
        sleepTimerSongWatcherJob = viewModelScope.launch {
            while (true) {
                if (_sleepTimerMode.value != SLEEP_TIMER_END_OF_SONG) return@launch

                // If the track changes (skip, autoplay-next, etc.) the "end of song" the user
                // meant has already passed — cancel rather than carry the timer into the next song.
                if (_currentSong.value?.id != watchedSongId) {
                    cancelSleepTimer()
                    return@launch
                }

                if (!_isPlaying.value) {
                    delay(300)
                    continue
                }

                val dur = _duration.value
                if (dur <= 0L) {
                    delay(300)
                    continue
                }

                val remaining = dur - _currentPosition.value
                _sleepTimerRemaining.value = remaining.coerceAtLeast(0L)

                if (remaining <= 300L) { // small threshold: position updates aren't frame-exact
                    if (_isPlaying.value) togglePlayPause()
                    cancelSleepTimer()
                    return@launch
                }
                delay(500)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Liked Songs (SharedPrefs — unchanged from v2.3.x)
    // ─────────────────────────────────────────────────────────────────────────

    fun isLiked(songId: String) = _likedSongs.value.any { it.id == songId }

    fun toggleLike(song: Song) {
        if (isLiked(song.id)) unlike(song) else like(song)
    }

    private fun like(song: Song) {
        val updatedSong = song.copy(isCached = false, cacheFailed = false)
        _likedSongs.value = listOf(updatedSong) + _likedSongs.value.filter { it.id != song.id }
        saveLikedSongs()
        viewModelScope.launch {
            cacheSemaphore.withPermit { cacheSongSilently(updatedSong) }
        }
    }

    private fun unlike(song: Song) {
        _likedSongs.value = _likedSongs.value.filter { it.id != song.id }
        saveLikedSongs()
        // Only remove cache if not in any custom playlist
        viewModelScope.launch(Dispatchers.IO) {
            val inCustomPlaylist = playlistDao.countPlaylistsContainingSong(song.id) > 0
            if (!inCustomPlaylist) {
                CacheManager.removeCachedSong(getApplication(), song.id)
            }
            refreshCacheSize()
        }
    }

    fun retryCache(song: Song) {
        updateLikedSongCacheStatus(song.id, isCached = false, cacheFailed = false)
        CacheManager.removeCachedSong(getApplication(), song.id)
        viewModelScope.launch {
            cacheSemaphore.withPermit {
                val success = cacheSongSilently(song)
                if (!success) updateLikedSongCacheStatus(song.id, isCached = false, cacheFailed = true)
            }
        }
    }

    /** Retry downloading a single song not tracked as liked or in a playlist (e.g. from an album view). */
    fun retryCacheStandalone(song: Song) {
        CacheManager.removeCachedSong(getApplication(), song.id)
        viewModelScope.launch {
            cacheSemaphore.withPermit { cacheSongSilently(song) }
            if (isLiked(song.id)) {
                updateLikedSongCacheStatus(song.id, isCached = CacheManager.isCached(getApplication(), song.id), cacheFailed = false)
            }
            refreshCacheSize()
        }
    }

    fun cacheAllLiked() {
        if (_cacheProgress.value != null) {
            Toast.makeText(getApplication(), "Already downloading...", Toast.LENGTH_SHORT).show()
            return
        }
        val uncached = _likedSongs.value.filter { !it.isCached }
        if (uncached.isEmpty()) {
            Toast.makeText(getApplication(), "All songs downloaded!", Toast.LENGTH_SHORT).show()
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
                            updateLikedSongCacheStatus(song.id, isCached = false, cacheFailed = false)
                            cacheSemaphore.withPermit { cacheSongSilently(song) }
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
                Toast.makeText(getApplication(), "Download complete!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Download every song in the whole library: liked songs, every custom playlist, and every saved album, deduplicated. */
    fun cacheAllLibrary() {
        if (_cacheProgress.value != null) {
            Toast.makeText(getApplication(), "Already downloading...", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val likedIds = _likedSongs.value.map { it.id }.toSet()
            val playlistSongs = playlistDao.getAllPlaylistSongs().map { it.toSong() }

            val combined = LinkedHashMap<String, Song>()
            _likedSongs.value.forEach { combined[it.id] = it }
            playlistSongs.forEach { if (it.id !in combined) combined[it.id] = it }

            // One-time cleanup for libraries imported from an older app version: those
            // exports predate the multi-artist comma-splitting and album-title fixes, so
            // their metadata may be stale or wrong. Saved-album songs already come from a
            // fresh Innertube.getAlbumSongs() call below and don't need this — only liked
            // songs and custom-playlist songs, which may carry whatever was in the import.
            if (prefs.getBoolean("needs_metadata_refresh", false)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Cleaning up your library's metadata…", Toast.LENGTH_SHORT).show()
                }

                val refreshed = coroutineScope {
                    combined.values.map { song ->
                        async { song.id to Innertube.refreshSongMetadata(song) }
                    }.map { it.await() }
                }.toMap()

                _likedSongs.value = _likedSongs.value.map { refreshed[it.id] ?: it }
                saveLikedSongs()

                val allPlaylists = playlistDao.getPlaylists()
                allPlaylists.forEach { playlist ->
                    val existing = playlistDao.getPlaylistSongs(playlist.id)
                    existing.forEach { entity ->
                        val fresh = refreshed[entity.songId] ?: return@forEach
                        playlistDao.insertSong(
                            entity.copy(
                                title = fresh.title,
                                artist = fresh.artist,
                                thumbnail = fresh.thumbnail,
                                isExplicit = fresh.isExplicit,
                                albumId = fresh.albumId
                            )
                        )
                    }
                }
                if (_openPlaylistId.value != null) {
                    _openPlaylistSongs.value = playlistDao.getPlaylistSongs(_openPlaylistId.value!!)
                }

                // Rebuild `combined` with the refreshed metadata so the download pass below
                // caches songs under their corrected names/thumbnails/album links.
                refreshed.forEach { (id, song) -> combined[id] = song }

                prefs.edit().putBoolean("needs_metadata_refresh", false).apply()
            }

            // Use persisted tracklists for saved albums where available; only hit the
            // network for albums we don't have a persisted tracklist for yet.
            val albumSongLists = coroutineScope {
                _savedAlbums.value.map { album ->
                    async {
                        _savedAlbumSongs.value[album.id] ?: try {
                            val songs = Innertube.getAlbumSongs(album.id, album.artist, caller = "cacheAllLibrary").second
                            _savedAlbumSongs.value = _savedAlbumSongs.value + (album.id to songs)
                            songs
                        } catch (e: Exception) {
                            Log.e("YTLite", "cacheAllLibrary: failed to load ${album.title}: ${e.message}")
                            emptyList()
                        }
                    }
                }.map { it.await() }
            }
            saveSavedAlbumSongs()
            albumSongLists.forEach { songs ->
                songs.forEach { if (it.id !in combined) combined[it.id] = it }
            }

            val uncached = combined.values.filter {
                !CacheManager.isCached(getApplication(), it.id)
            }
            if (uncached.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "All songs downloaded!", Toast.LENGTH_SHORT).show()
                }
                return@launch
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
                                if (song.id in likedIds) {
                                    updateLikedSongCacheStatus(song.id, isCached = false, cacheFailed = false)
                                }
                                cacheSemaphore.withPermit { cacheSongSilently(song) }
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
                    if (_openPlaylistId.value != null) {
                        _openPlaylistSongs.value = playlistDao.getPlaylistSongs(_openPlaylistId.value!!)
                    }
                    Toast.makeText(getApplication(), "Download complete!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun shuffleLiked() {
        val shuffled = _likedSongs.value.shuffled()
        if (shuffled.isEmpty()) return
        playWithQueue(shuffled.first(), shuffled)
    }

    private fun updateLikedSongCacheStatus(id: String, isCached: Boolean, cacheFailed: Boolean) {
        _likedSongs.value = _likedSongs.value.map { s ->
            if (s.id == id) s.copy(isCached = isCached, cacheFailed = cacheFailed) else s
        }
        saveLikedSongs()
    }

    private suspend fun cacheSongSilently(song: Song): Boolean {
        // Resolve this song's album metadata in the background if not already known,
        // so it's ready (and persisted) by the time the song is played offline or
        // Song Info is opened — no need to wait for the user to open Song Info first.
        if (song.albumId.isNotBlank()) {
            resolveAlbumInBackground(song.albumId, song.artist)
        }
        _cachingSongIds.value = _cachingSongIds.value + song.id
        try {
            return try {
                when (val result = CacheManager.cacheSong(getApplication(), song)) {
                    is CacheResult.Success -> {
                        updateLikedSongCacheStatus(song.id, isCached = true, cacheFailed = false)
                        refreshCacheSize()
                        // Cache lyrics in background
                        viewModelScope.launch(Dispatchers.IO) { cacheLyricsForSong(song) }
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
        } finally {
            _cachingSongIds.value = _cachingSongIds.value - song.id
        }
    }

    fun dismissStorageLow() { _showStorageLow.value = false }

    // ─────────────────────────────────────────────────────────────────────────
    // Custom Playlists (Room)
    // ─────────────────────────────────────────────────────────────────────────

    fun createPlaylist(name: String, emoji: String) {
        val trimmed = name.trim()
        val isDuplicate = customPlaylists.value.any { it.name.equals(trimmed, ignoreCase = true) }
        if (isDuplicate) return
        viewModelScope.launch(Dispatchers.IO) {
            val entity = PlaylistEntity(
                id = UUID.randomUUID().toString(),
                name = trimmed,
                emoji = emoji
            )
            playlistDao.insertPlaylist(entity)
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Get songs before deletion to check cache cleanup
            val songs = playlistDao.getPlaylistSongs(playlistId)
            playlistDao.clearPlaylist(playlistId)
            playlistDao.deletePlaylist(playlistId)
            // For each song, remove cache only if it's not liked and not in other playlists
            songs.forEach { entity ->
                val liked = isLiked(entity.songId)
                val inOther = playlistDao.countPlaylistsContainingSong(entity.songId) > 0
                if (!liked && !inOther) {
                    CacheManager.removeCachedSong(getApplication(), entity.songId)
                }
            }
            refreshCacheSize()
        }
    }

    fun renamePlaylist(playlistId: String, name: String, emoji: String) {
        val trimmed = name.trim()
        val isDuplicate = customPlaylists.value.any {
            it.id != playlistId && it.name.equals(trimmed, ignoreCase = true)
        }
        if (isDuplicate) return
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.renamePlaylist(playlistId, trimmed, emoji)
        }
    }

    fun loadPlaylistSongs(playlistId: String) {
        _openPlaylistId.value = playlistId
        viewModelScope.launch(Dispatchers.IO) {
            _openPlaylistSongs.value = playlistDao.getPlaylistSongs(playlistId)
        }
    }

    fun isSongInPlaylist(songId: String, playlistId: String): Boolean {
        // Optimistic check from loaded songs if this is the open playlist
        if (_openPlaylistId.value == playlistId) {
            return _openPlaylistSongs.value.any { it.songId == songId }
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // "Add to..." bottom sheet
    // ─────────────────────────────────────────────────────────────────────────

    fun openAddToSheet(song: Song) { _addToSheetSong.value = song }
    fun closeAddToSheet() { _addToSheetSong.value = null }

    /**
     * Toggle song in a playlist.
     * If playlistId == LIKED_PLAYLIST_ID → toggleLike
     * Otherwise → custom playlist in Room
     * Returns true if added, false if removed.
     */
    suspend fun toggleSongInPlaylist(song: Song, playlistId: String): Boolean {
        return if (playlistId == LIKED_PLAYLIST_ID) {
            val wasLiked = isLiked(song.id)
            toggleLike(song)
            !wasLiked
        } else {
            withContext(Dispatchers.IO) {
                val already = playlistDao.isSongInPlaylist(playlistId, song.id)
                val added: Boolean
                if (already) {
                    playlistDao.removeSong(playlistId, song.id)
                    // Remove cache if not liked and not in other playlists
                    val liked = isLiked(song.id)
                    val inOther = playlistDao.countPlaylistsContainingSong(song.id) > 0
                    if (!liked && !inOther) {
                        CacheManager.removeCachedSong(getApplication(), song.id)
                        refreshCacheSize()
                    }
                    added = false
                } else {
                    playlistDao.insertSong(
                        PlaylistSongEntity(
                            playlistId = playlistId,
                            songId = song.id,
                            title = song.title,
                            artist = song.artist,
                            thumbnail = song.thumbnail,
                            isExplicit = song.isExplicit,
                            albumId = song.albumId
                        )
                    )
                    // Trigger cache for this song
                    cacheSemaphore.withPermit { cacheSongSilently(song) }
                    added = true
                }
                // Refresh open playlist if it's this one
                if (_openPlaylistId.value == playlistId) {
                    _openPlaylistSongs.value = playlistDao.getPlaylistSongs(playlistId)
                }
                added
            }
        }
    }

    suspend fun isSongInPlaylistAsync(songId: String, playlistId: String): Boolean {
        if (playlistId == LIKED_PLAYLIST_ID) return isLiked(songId)
        return playlistDao.isSongInPlaylist(playlistId, songId)
    }

    fun removeSongFromCustomPlaylist(playlistId: String, song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.removeSong(playlistId, song.id)
            val liked = isLiked(song.id)
            val inOther = playlistDao.countPlaylistsContainingSong(song.id) > 0
            if (!liked && !inOther) {
                CacheManager.removeCachedSong(getApplication(), song.id)
                refreshCacheSize()
            }
            if (_openPlaylistId.value == playlistId) {
                _openPlaylistSongs.value = playlistDao.getPlaylistSongs(playlistId)
            }
        }
    }

    fun playCustomPlaylist(playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val songs = playlistDao.getPlaylistSongs(playlistId).map { it.toSong() }
            if (songs.isEmpty()) return@launch
            viewModelScope.launch(Dispatchers.Main) {
                playWithQueue(songs.first(), songs)
            }
        }
    }

    fun shuffleCustomPlaylist(playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val songs = playlistDao.getPlaylistSongs(playlistId).map { it.toSong() }.shuffled()
            if (songs.isEmpty()) return@launch
            viewModelScope.launch(Dispatchers.Main) {
                playWithQueue(songs.first(), songs)
            }
        }
    }

    private fun PlaylistSongEntity.toSong() = Song(
        id = songId, title = title, artist = artist,
        thumbnail = thumbnail, isExplicit = isExplicit, albumId = albumId,
        isCached = CacheManager.isCached(getApplication(), songId)
    )

    /** Retry downloading a single song that belongs to a custom playlist (not liked). */
    fun retryCacheInPlaylist(song: Song) {
        viewModelScope.launch {
            CacheManager.removeCachedSong(getApplication(), song.id)
            cacheSemaphore.withPermit { cacheSongSilently(song) }
            // Force a refresh of the open playlist's song list so isCached is re-derived
            _openPlaylistId.value?.let { pid ->
                withContext(Dispatchers.IO) {
                    _openPlaylistSongs.value = playlistDao.getPlaylistSongs(pid)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Saved Albums
    // ─────────────────────────────────────────────────────────────────────────

    fun saveAlbum(album: Album) {
        if (!isAlbumSaved(album.id, album.title)) {
            _savedAlbums.value = listOf(album) + _savedAlbums.value
            saveSavedAlbums()
            Toast.makeText(getApplication(), "Album saved", Toast.LENGTH_SHORT).show()
            // Cache all songs of this album, and persist the tracklist itself so the
            // album can be browsed fully offline afterward.
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val (_, songs) = Innertube.getAlbumSongs(album.id, album.artist, caller = "saveAlbum")
                    _savedAlbumSongs.value = _savedAlbumSongs.value + (album.id to songs)
                    saveSavedAlbumSongs()
                    songs.forEach { song ->
                        cacheSemaphore.withPermit { cacheSongSilently(song) }
                    }
                    refreshCacheSize()
                } catch (e: Exception) {
                    Log.e("YTLite", "Failed to cache album songs: ${e.message}")
                }
            }
        }
    }

    /**
     * Repairs a saved album's stored metadata (title/artist/thumbnail/year) in place, keeping
     * the same id. Used when a live lookup reveals better data than what was originally saved
     * (e.g. an album saved before a metadata-resolution bug fix, or saved with a placeholder
     * title from an incomplete nav source).
     */
    fun refreshSavedAlbumMetadata(resolved: Album) {
        val existing = _savedAlbums.value.firstOrNull { it.id == resolved.id } ?: return
        if (existing.title == resolved.title &&
            existing.artist == resolved.artist &&
            existing.thumbnail == resolved.thumbnail &&
            existing.year == resolved.year
        ) return // already up to date, avoid redundant writes
        _savedAlbums.value = _savedAlbums.value.map {
            if (it.id == resolved.id) resolved.copy(id = it.id) else it
        }
        saveSavedAlbums()
    }

    fun unsaveAlbum(album: Album) {
        // Remove by id OR by matching title, so unsaving works even if this screen was
        // opened via a different browse id than the one the album was originally saved under.
        val toRemove = _savedAlbums.value.filter {
            it.id == album.id || (album.title.isNotBlank() && normalizeAlbumTitle(it.title) == normalizeAlbumTitle(album.title))
        }
        if (toRemove.isEmpty()) return
        _savedAlbums.value = _savedAlbums.value.filterNot { it in toRemove }
        saveSavedAlbums()
        Toast.makeText(getApplication(), "Album removed", Toast.LENGTH_SHORT).show()
        // Remove cached songs that aren't liked or in custom playlists, using the persisted
        // tracklist (falls back to a live lookup only if we never had one cached).
        viewModelScope.launch(Dispatchers.IO) {
            try {
                toRemove.forEach { removedAlbum ->
                    val songs = _savedAlbumSongs.value[removedAlbum.id]
                        ?: try {
                            Innertube.getAlbumSongs(removedAlbum.id, removedAlbum.artist, caller = "unsaveAlbum").second
                        } catch (e: Exception) {
                            Log.e("YTLite", "unsaveAlbum: live lookup failed: ${e.message}")
                            emptyList()
                        }
                    songs.forEach { song ->
                        val liked = isLiked(song.id)
                        val inPlaylist = playlistDao.countPlaylistsContainingSong(song.id) > 0
                        if (!liked && !inPlaylist) {
                            CacheManager.removeCachedSong(getApplication(), song.id)
                        }
                    }
                    _savedAlbumSongs.value = _savedAlbumSongs.value - removedAlbum.id
                }
                saveSavedAlbumSongs()
                refreshCacheSize()
            } catch (e: Exception) {
                Log.e("YTLite", "Failed to clean album cache: ${e.message}")
            }
        }
    }

    fun isAlbumSaved(albumId: String, albumTitle: String = ""): Boolean {
        if (_savedAlbums.value.any { it.id == albumId }) return true
        if (albumTitle.isBlank()) return false
        return _savedAlbums.value.any { normalizeAlbumTitle(it.title) == normalizeAlbumTitle(albumTitle) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cache helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun refreshCacheSize() {
        _cacheSize.value = CacheManager.getCacheSizeString(getApplication())
    }

    private fun createCacheNotificationChannel() {
        val channel = NotificationChannel(
            "cache_channel", "Download Progress", NotificationManager.IMPORTANCE_LOW
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
            .setContentTitle("Downloading songs")
            .setContentText("$done / $total ($percent%)")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        mgr.notify(1001, n)
    }

    private fun cancelCacheNotification() {
        (getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(1001)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Settings
    // ─────────────────────────────────────────────────────────────────────────


    fun toggleHaptics() {
        _hapticsEnabled.value = !_hapticsEnabled.value
        prefs.edit().putBoolean("haptics_enabled", _hapticsEnabled.value).apply()
    }

    fun toggleLogs() {
        _logsEnabled.value = !_logsEnabled.value
        prefs.edit().putBoolean("logs_enabled", _logsEnabled.value).apply()
    }

    fun triggerUpdater() { _navigateToUpdater.value = true }
    fun onUpdaterNavigated() { _navigateToUpdater.value = false }

    fun checkForUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            val latest = fetchLatestAppVersion()
            _updateAvailable.value = latest != null && latest != APP_CURRENT_VERSION
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence helpers
    // ─────────────────────────────────────────────────────────────────────────

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
                put("albumId", song.albumId)
                put("albumTitle", song.albumTitle)
                put("year", song.year)
                put("artistNames", JSONArray(song.artistNames))
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
                val artistNamesArr = obj.optJSONArray("artistNames")
                val artistNames = if (artistNamesArr != null) {
                    (0 until artistNamesArr.length()).map { artistNamesArr.optString(it, "") }.filter { it.isNotBlank() }
                } else emptyList()
                Song(
                    id = id, title = obj.getString("title"),
                    artist = obj.getString("artist"), thumbnail = obj.getString("thumbnail"),
                    isCached = isCached,
                    cacheFailed = if (isCached) false else obj.optBoolean("cacheFailed", false),
                    isExplicit = obj.optBoolean("isExplicit", false),
                    albumId = obj.optString("albumId", ""),
                    albumTitle = obj.optString("albumTitle", ""),
                    year = obj.optString("year", ""),
                    artistNames = artistNames
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveResolvedAlbumCache() {
        val arr = JSONArray()
        _resolvedAlbumCache.value.values.forEach { album ->
            arr.put(JSONObject().apply {
                put("id", album.id)
                put("title", album.title)
                put("artist", album.artist)
                put("thumbnail", album.thumbnail)
                put("year", album.year)
            })
        }
        prefs.edit().putString("resolved_album_cache", arr.toString()).apply()
    }

    private fun loadResolvedAlbumCache(): Map<String, Album> {
        return try {
            val arr = JSONArray(prefs.getString("resolved_album_cache", "[]") ?: "[]")
            (0 until arr.length()).associate { i ->
                val obj = arr.getJSONObject(i)
                val album = Album(
                    id = obj.getString("id"), title = obj.getString("title"),
                    artist = obj.optString("artist", ""), thumbnail = obj.optString("thumbnail", ""),
                    year = obj.optString("year", "")
                )
                album.id to album
            }
        } catch (_: Exception) { emptyMap() }
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
                put("year", album.year)
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
                    id = obj.getString("id"), title = obj.getString("title"),
                    artist = obj.getString("artist"), thumbnail = obj.getString("thumbnail"),
                    duration = obj.optLong("duration", 0L),
                    songCount = obj.optInt("songCount", 0),
                    youtubeUrl = obj.optString("youtubeUrl", ""),
                    year = obj.optString("year", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveSavedAlbumSongs() {
        val root = JSONObject()
        _savedAlbumSongs.value.forEach { (albumId, songs) ->
            val arr = JSONArray()
            songs.forEach { song ->
                arr.put(JSONObject().apply {
                    put("id", song.id)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("thumbnail", song.thumbnail)
                    put("duration", song.duration)
                    put("albumId", song.albumId)
                    put("albumTitle", song.albumTitle)
                    put("isExplicit", song.isExplicit)
                    put("year", song.year)
                    put("artistNames", JSONArray(song.artistNames))
                })
            }
            root.put(albumId, arr)
        }
        prefs.edit().putString("saved_album_songs", root.toString()).apply()
    }

    private fun loadSavedAlbumSongs(): Map<String, List<Song>> {
        return try {
            val root = JSONObject(prefs.getString("saved_album_songs", "{}") ?: "{}")
            val map = mutableMapOf<String, List<Song>>()
            root.keys().forEach { albumId ->
                val arr = root.getJSONArray(albumId)
                val songs = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    val artistNamesArr = obj.optJSONArray("artistNames")
                    val artistNames = if (artistNamesArr != null) {
                        (0 until artistNamesArr.length()).map { artistNamesArr.optString(it, "") }.filter { it.isNotBlank() }
                    } else emptyList()
                    Song(
                        id = obj.getString("id"), title = obj.getString("title"),
                        artist = obj.getString("artist"), thumbnail = obj.getString("thumbnail"),
                        duration = obj.optLong("duration", 0L),
                        albumId = obj.optString("albumId", ""),
                        albumTitle = obj.optString("albumTitle", ""),
                        isExplicit = obj.optBoolean("isExplicit", false),
                        year = obj.optString("year", ""),
                        artistNames = artistNames
                    )
                }
                map[albumId] = songs
            }
            map
        } catch (_: Exception) { emptyMap() }
    }

    fun exportData(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val root = JSONObject()
                val songsArr = JSONArray()
                _likedSongs.value.forEach { song ->
                    songsArr.put(JSONObject().apply {
                        put("id", song.id); put("title", song.title)
                        put("artist", song.artist); put("thumbnail", song.thumbnail)
                    })
                }
                val albumsArr = JSONArray()
                _savedAlbums.value.forEach { album ->
                    albumsArr.put(JSONObject().apply {
                        put("id", album.id); put("title", album.title)
                        put("artist", album.artist); put("thumbnail", album.thumbnail)
                        put("duration", album.duration); put("songCount", album.songCount)
                        put("youtubeUrl", album.youtubeUrl); put("year", album.year)
                    })
                }
                val playlistsArr = JSONArray()
                withContext(Dispatchers.IO) {
                    playlistDao.getPlaylists().forEach { playlist ->
                        val playlistSongs = playlistDao.getPlaylistSongs(playlist.id)
                        val playlistSongsArr = JSONArray()
                        playlistSongs.forEach { song ->
                            playlistSongsArr.put(JSONObject().apply {
                                put("id", song.songId); put("title", song.title)
                                put("artist", song.artist); put("thumbnail", song.thumbnail)
                                put("isExplicit", song.isExplicit); put("albumId", song.albumId)
                            })
                        }
                        playlistsArr.put(JSONObject().apply {
                            put("id", playlist.id); put("name", playlist.name)
                            put("emoji", playlist.emoji); put("songs", playlistSongsArr)
                        })
                    }
                }
                root.put("liked_songs", songsArr)
                root.put("saved_albums", albumsArr)
                root.put("custom_playlists", playlistsArr)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(root.toString(2).toByteArray())
                }
                Toast.makeText(context, "Exported! :)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed :( ${e.message}", Toast.LENGTH_LONG).show()
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
                        id = obj.getString("id"), title = obj.getString("title"),
                        artist = obj.getString("artist"), thumbnail = obj.getString("thumbnail"),
                        isCached = false, cacheFailed = true
                    )
                }
                val albums = (0 until root.getJSONArray("saved_albums").length()).map { i ->
                    val obj = root.getJSONArray("saved_albums").getJSONObject(i)
                    Album(
                        id = obj.getString("id"), title = obj.getString("title"),
                        artist = obj.getString("artist"), thumbnail = obj.getString("thumbnail"),
                        duration = obj.optLong("duration", 0L),
                        songCount = obj.optInt("songCount", 0),
                        youtubeUrl = obj.optString("youtubeUrl", "")
                    )
                }

                // Exports from before custom playlists were included won't have this key at
                // all — that also tells us the export predates this version's metadata fixes
                // (multi-artist comma splitting, album title stamping), so flag a one-time
                // refresh the next time the user downloads their library.
                val hasCustomPlaylists = root.has("custom_playlists")
                var importedPlaylistCount = 0
                var importedPlaylistSongCount = 0
                if (hasCustomPlaylists) {
                    withContext(Dispatchers.IO) {
                        val playlistsArr = root.getJSONArray("custom_playlists")
                        for (i in 0 until playlistsArr.length()) {
                            val pObj = playlistsArr.getJSONObject(i)
                            val playlistId = pObj.getString("id")
                            playlistDao.insertPlaylist(
                                PlaylistEntity(
                                    id = playlistId,
                                    name = pObj.getString("name"),
                                    emoji = pObj.optString("emoji", "🎵")
                                )
                            )
                            importedPlaylistCount++
                            val songsArr = pObj.getJSONArray("songs")
                            for (j in 0 until songsArr.length()) {
                                val sObj = songsArr.getJSONObject(j)
                                playlistDao.insertSong(
                                    PlaylistSongEntity(
                                        playlistId = playlistId,
                                        songId = sObj.getString("id"),
                                        title = sObj.getString("title"),
                                        artist = sObj.getString("artist"),
                                        thumbnail = sObj.getString("thumbnail"),
                                        isExplicit = sObj.optBoolean("isExplicit", false),
                                        albumId = sObj.optString("albumId", "")
                                    )
                                )
                                importedPlaylistSongCount++
                            }
                        }
                    }
                }

                _likedSongs.value = songs
                _savedAlbums.value = albums
                saveLikedSongs()
                saveSavedAlbums()

                if (!hasCustomPlaylists) {
                    prefs.edit().putBoolean("needs_metadata_refresh", true).apply()
                }

                val summary = buildString {
                    append("Imported :)  ${songs.size} songs, ${albums.size} albums")
                    if (hasCustomPlaylists) append(", $importedPlaylistCount playlists ($importedPlaylistSongCount songs)")
                }
                Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed :( ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun cacheLyricsForSong(song: Song) {
        try {
            if (CacheManager.isLyricsCached(getApplication(), song.id)) return
            val result = LyricsRepository.getLyrics(
                title = song.title,
                artist = com.rkd.audiobasics.api.Innertube.splitArtistNames(song.artist).firstOrNull() ?: song.artist,
                duration = song.duration
            ) ?: return
            CacheManager.saveLyrics(getApplication(), song.id, LyricsCache.serialize(result))
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        stopProgressTracking()
        syncJob?.cancel()
        cacheAllJob?.cancel()
        sleepTimerJob?.cancel()
        sleepTimerSongWatcherJob?.cancel()
        getApplication<Application>().unregisterComponentCallbacks(systemThemeCallback)
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }

    companion object {
        const val LIKED_PLAYLIST_ID = "__liked__"
        const val SLEEP_TIMER_OFF = 0
        const val SLEEP_TIMER_CUSTOM = 1
        const val SLEEP_TIMER_END_OF_SONG = 2
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}
