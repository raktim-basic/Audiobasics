package com.yt.lite.ui

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.yt.lite.api.Innertube
import com.yt.lite.data.Song
import com.yt.lite.player.MusicService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MusicViewModel(app: Application) : AndroidViewModel(app) {

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
        val future = MediaController.Builder(getApplication(), token).buildAsync()
        future.addListener(
            {
                try {
                    controller = future.get()
                    controller?.addListener(listener)
                } catch (_: Exception) {}
            },
            { runnable -> runnable.run() }
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
                val url = Innertube.getStreamUrl(song.id)
                    ?: throw Exception("Could not get stream URL")
                val item = MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .build()
                    )
                    .build()
                controller?.run {
                    setMediaItem(item)
                    prepare()
                    play()
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Playback failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun togglePlayPause() {
        controller?.run { if (isPlaying) pause() else play() }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release()
        super.onCleared()
    }
}
