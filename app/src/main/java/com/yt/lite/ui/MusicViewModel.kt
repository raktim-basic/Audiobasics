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

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Full queue of Songs (metadata only, no stream URLs yet)
    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue

    private val _likedSongs = MutableStateFlow<List<Song>>(loadLikedSongs())
    val likedSongs: StateFlow<List<Song>> = _likedSongs

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // When player moves to next/prev item, resolve which song it is
            val index = controller?.currentMediaItemIndex ?: return
            _currentSong.value = _queue.value.getOrNull(index)
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                _isPlaying.value = false
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
                } catch (e: Exception) {
                    Log.e("YTLite", "MediaController init failed", e)
                }
            },
            { it.run() }
        )
    }

    // Play a single song, replacing queue
    fun play(song: Song) {
        playWithQueue(song, listOf(song))
    }

    // Play a song within a specific queue (e.g. liked songs playlist)
    fun playWithQueue(song: Song, queue: List<Song>) {
        viewModelScope.launch {
            _currentSong.value = song
            _isLoading.value = true
            _error.value = null
            _queue.value = queue

            try {
                val url = Innertube.getStreamUrl(getApplication(), song.id)
                if (url == null) {
                    Toast.makeText(getApplication(), "Could not get stream URL", Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.d("YTLite", "Playing: ${song.title}")

                // Build MediaItems for entire queue
                // Only the current song has a real URI — others are stubs
                // When player transitions, onMediaItemTransition fires and we load the next URL
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
                } ?: Toast.makeText(getApplication(), "Player not ready, try again", Toast.LENGTH_SHORT).show()

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

    // Add song at end of queue
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

    // Play song right after the current one
    fun playNext(song: Song) {
        val currentIndex = controller?.currentMediaItemIndex ?: 0
        val insertIndex = currentIndex + 1
        val newQueue = _queue.value.toMutableList()
        // Remove if already in queue
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

    fun togglePlayPause() {
        controller?.run { if (isPlaying) pause() else play() }
        _isPlaying.value = controller?.isPlaying ?: false
    }

    fun toggleLike(song: Song) {
        // New liked songs appear at top
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
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
