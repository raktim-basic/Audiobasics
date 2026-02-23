package com.yt.lite.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
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

        // Base HTTP factory with YouTube headers
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com"
                )
            )

        // Resolving factory — intercepts stub YouTube URLs and gets real stream URL
        val resolvingFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val uri = dataSpec.uri
            val host = uri.host ?: return@Factory dataSpec

            if (host.contains("youtube.com") || host.contains("youtu.be")) {
                val videoId = uri.getQueryParameter("v")
                    ?: uri.lastPathSegment
                    ?: return@Factory dataSpec

                android.util.Log.d("YTLite", "Resolving stream for: $videoId")

                val streamUrl = runBlocking {
                    try {
                        Innertube.getStreamUrl(this@MusicService, videoId)
                    } catch (e: Exception) {
                        android.util.Log.e("YTLite", "Failed to resolve: ${e.message}")
                        null
                    }
                }

                if (streamUrl != null) {
                    android.util.Log.d("YTLite", "Resolved: ${streamUrl.take(60)}")
                    dataSpec.withUri(Uri.parse(streamUrl))
                } else {
                    dataSpec // fall through, will error naturally
                }
            } else {
                dataSpec // already a real stream URL, pass through
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
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .build()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
