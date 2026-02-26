package com.yt.lite.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yt.lite.MainActivity
import com.yt.lite.api.Innertube
import kotlinx.coroutines.runBlocking

@UnstableApi
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val dataSourceFactory = DataSource.Factory {
            val httpDataSource = httpFactory.createDataSource()
            httpDataSource
        }

        val resolvingFactory = ResolvingDataSource.Factory(dataSourceFactory) { dataSpec ->
            val uriString = dataSpec.uri.toString()
            when {
                uriString.startsWith("file://") -> {
                    // Use file data source for cached files
                    val filePath = uriString.removePrefix("file://")
                    dataSpec.withUri(Uri.parse("file://$filePath"))
                }
                uriString.contains("youtube.com") || uriString.contains("youtu.be") -> {
                    val videoId = extractVideoId(uriString)
                    if (videoId != null) {
                        val realUrl = runBlocking {
                            Innertube.getStreamUrl(this@MusicService, videoId)
                        }
                        if (realUrl != null) {
                            dataSpec.withUri(Uri.parse(realUrl))
                        } else {
                            dataSpec
                        }
                    } else {
                        dataSpec
                    }
                }
                else -> dataSpec
            }
        }

        val combinedFactory = DataSource.Factory {
            val uri = it?.uri?.toString() ?: ""
            if (uri.startsWith("file://")) {
                FileDataSource()
            } else {
                httpFactory.createDataSource()
            }
        }

        val finalFactory = ResolvingDataSource.Factory(combinedFactory) { dataSpec ->
            val uriString = dataSpec.uri.toString()
            when {
                uriString.startsWith("file://") -> dataSpec
                uriString.contains("youtube.com") || uriString.contains("youtu.be") -> {
                    val videoId = extractVideoId(uriString)
                    if (videoId != null) {
                        val realUrl = runBlocking {
                            Innertube.getStreamUrl(this@MusicService, videoId)
                        }
                        if (realUrl != null) dataSpec.withUri(Uri.parse(realUrl))
                        else dataSpec
                    } else dataSpec
                }
                else -> dataSpec
            }
        }

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(finalFactory))
            .setHandleAudioBecomingNoisy(true)
            .build()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
