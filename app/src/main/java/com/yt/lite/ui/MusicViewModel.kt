package com.yt.lite.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.widget.Toast
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

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue

    private val _likedSongs = MutableStateFlow<List<Song>>(loadLikedSongs())
    val likedSongs: StateFlow<List<Song>> = _likedSongs

    private val _searchResults = MutableStateFlow<List<Song>>(emptyList())
    val searchResults: StateFlow<List<Song>> = _searchResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
            if (playing) startProgressTracking() else stopProgressTracking()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = controller?.currentMediaItemIndex ?: return
            _currentSong.value = _queue.value.getOrNull(index)
                ?: mediaItem?.toSong()
            _currentPosition.value = 0L
            _duration.value = 0L
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                _isPlaying.value = false
                stopProgressTracking()
            }
            if (state == Player.STATE_READY) {
                _duration.value = controller?.duration?.takeIf { it > 0 } ?: 0L
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
    }

    // Restore current song and queue from controller when app reopens
    private fun restoreStateFromController() {
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) return

        // Restore queue
        val restoredQueue = (0 until ctrl.mediaItemCount).mapNotNull { i ->
            ctrl.getMediaItemAt(i).toSong()
        }
        if (restoredQueue.isNotEmpty()) {
            _queue.value = restoredQueue
        }

        // Restore current song
        val currentIndex = ctrl.currentMediaItemIndex
        _currentSong.value = restoredQueue.getOrNull(currentIndex)

        // Restore playing state
        _isPlaying.value = ctrl.isPlaying
        _duration.value = ctrl.duration.takeIf { it > 0 } ?: 0L
        _currentPosition.value = ctrl.currentPosition.coerceAtLeast(0L)

        if (ctrl.isPlaying) startProgressTracking()
    }

    // Convert MediaItem back to Song using metadata
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
        playWithQueue(song, listOf(song))
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
                val url = Innertube.getStreamUrl(getApplication(), song.id)
                if (url == null) {
                    Toast.makeText(getApplication(), "Could not get stream URL", Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.d("YTLite", "Playing: ${song.title}")

                val songIndex = queue.indexOf(song)
                val mediaItems = queue.mapIndexed { i, s ->
                    MediaItem.Builder()
                        .setMediaId(s.id)
                        .setUri(if (i == songIndex) url else "https://www.youtube.com/watch?v=${s.id}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(s.title)
                                .setArtist(s.artist)
                                .setArtworkUri(android.net.Uri.parse(s.thumbnail))
                                .build()
                        )
                        .build()
                }

                controller?.run {
                    setMediaItems(mediaItems, songIndex, 0L)
                    prepare()
                    play()
                } ?: Toast.makeText(
                    getApplication(),
                    "Player not ready, try again",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                _error.value = msg
                Log.e("YTLite", "Playback error: $msg", e)
                Toast.makeText(getApplication(), "Error: $msg", Toast.LENGTH_LONG).show()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addToQueue(song: Song) {
        val newQueue = _queue.value.toMutableList()
        if (newQueue.none { it.id == song.id }) {
            newQueue.add(song)
            _queue.value = newQueue
            val mediaItem = MediaItem.Builder()
                .setMediaId(song.id)
                .setUri("https://www.youtube.com/watch?v=${song.id}")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setArtworkUri(android.net.Uri.parse(song.thumbnail))
                        .build()
                )
                .build()
            controller?.addMediaItem(mediaItem)
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
        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri("https://www.youtube.com/watch?v=${song.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(android.net.Uri.parse(song.thumbnail))
                    .build()
            )
            .build()
        controller?.addMediaItem(insertIndex, mediaItem)
        Toast.makeText(getApplication(), "Playing next", Toast.LENGTH_SHORT).show()
    }

    fun skipToNext() {
        controller?.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        controller?.seekToPreviousMediaItem()
    }

    fun togglePlayPause() {
        controller?.run { if (isPlaying) pause() else play() }
        _isPlaying.value = controller?.isPlaying ?: false
    }

    fun toggleLike(song: Song) {
        _likedSongs.value = if (_likedSongs.value.any { it.id == song.id }) {
            _likedSongs.value.filter { it.id != song.id }
        } else {
            listOf(song) + _likedSongs.value
        }
        saveLikedSongs()
    }

    private fun saveLikedSongs() {
        val arr = JSONArray()
        _likedSongs.value.forEach { song ->
            val obj = JSONObject()
            obj.put("id", song.id)
            obj.put("title", song.title)
            obj.put("artist", song.artist)
            obj.put("thumbnail", song.thumbnail)
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
                Song(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    artist = obj.getString("artist"),
                    thumbnail = obj.getString("thumbnail")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override fun onCleared() {
        stopProgressTracking()
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
