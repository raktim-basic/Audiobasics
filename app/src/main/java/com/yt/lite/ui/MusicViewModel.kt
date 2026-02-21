package com.yt.lite.ui

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.yt.lite.api.Innertube
import com.yt.lite.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@UnstableApi
class MusicViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ytlite", Context.MODE_PRIVATE)
    private val player = ExoPlayer.Builder(app).build()

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

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }
        })
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
                val url = Innertube.getStreamUrl(song.id)
                if (url == null) {
                    val msg = "No stream URL returned for ${song.id}"
                    _error.value = msg
                    Toast.makeText(getApplication(), "Error: $msg", Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.d("YTLite", "Stream URL: $url")
                Toast.makeText(getApplication(), "Loading: ${song.title}", Toast.LENGTH_SHORT).show()

                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
                        "Referer" to "https://music.youtube.com/",
                        "Origin" to "https://music.youtube.com"
                    ))

                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(
                        MediaItem.Builder()
                            .setUri(url)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(song.title)
                                    .setArtist(song.artist)
                                    .build()
                            )
                            .build()
                    )

                player.setMediaSource(mediaSource)
                player.prepare()
                player.play()

            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                _error.value = msg
                Log.e("YTLite", "Playback error", e)
                Toast.makeText(getApplication(), "Error: $msg", Toast.LENGTH_LONG).show()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
        _isPlaying.value = player.isPlaying
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
        player.release()
        super.onCleared()
    }
}
