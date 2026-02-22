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
import kotlinx.coroutines.guava.await
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

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue

    private val _likedSongs = MutableStateFlow<List<Song>>(loadLikedSongs())
    val likedSongs: StateFlow<List<Song>> = _likedSongs

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
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

    fun play(song: Song) {
        viewModelScope.launch {
            _currentSong.value = song
            _isLoading.value = true
            _error.value = null
            if (_queue.value.none { it.id == song.id }) {
                _queue.value = _queue.value + song
            }
            try {
                val url = Innertube.getStreamUrl(getApplication(), song.id)
                if (url == null) {
                    val msg = "Could not get stream URL"
                    _error.value = msg
                    Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.d("YTLite", "Playing: ${url.take(80)}")

                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setArtworkUri(android.net.Uri.parse(song.thumbnail))
                            .build()
                    )
                    .build()

                controller?.run {
                    setMediaItem(mediaItem)
                    prepare()
                    play()
                } ?: run {
                    Toast.makeText(getApplication(), "Player not ready, try again", Toast.LENGTH_SHORT).show()
                }

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

    fun togglePlayPause() {
        controller?.run { if (isPlaying) pause() else play() }
    }

    fun toggleLike(song: Song) {
        _likedSongs.value = if (_likedSongs.value.any { it.id == song.id }) {
            _likedSongs.value.filter { it.id != song.id }
        } else {
            _likedSongs.value + song
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
        MediaController.releaseFuture(controllerFuture!!)
        super.onCleared()
    }
}
