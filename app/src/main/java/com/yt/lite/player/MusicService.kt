package com.yt.lite.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
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
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
@AndroidEntryPoint
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    // Cache resolved stream URLs so we don't re-fetch on every ExoPlayer retry
    // Key = videoId, Value = Pair(streamUrl, expiryTimeMs)
    private val songUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()

    // Stream URLs from YouTube are valid for ~6 hours
    private val STREAM_URL_TTL_MS = 5 * 60 * 60 * 1000L // 5 hours to be safe

    override fun onCreate() {
        super.onCreate()

        // IOS client UA — matches the client used to resolve the stream
        val iosUserAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(iosUserAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setDefaultRequestProperties(
                mapOf(
                    "Cookie" to "CONSENT=YES+; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg",
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com"
                )
            )

        val finalFactory = DataSource.Factory {
            object : androidx.media3.datasource.DataSource {
                private var delegate: androidx.media3.datasource.DataSource =
                    httpFactory.createDataSource()

                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    delegate = if (dataSpec.uri.scheme == "file") {
                        FileDataSource()
                    } else {
                        httpFactory.createDataSource()
                    }
                    return delegate.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    delegate.read(buffer, offset, length)

                override fun getUri(): Uri? = delegate.uri
                override fun close() = delegate.close()
                override fun addTransferListener(
                    transferListener: androidx.media3.datasource.TransferListener
                ) = delegate.addTransferListener(transferListener)
            }
        }

        val finalResolvingFactory = ResolvingDataSource.Factory(finalFactory) { dataSpec ->
            val uriString = dataSpec.uri.toString()

            when {
                uriString.startsWith("file://") -> dataSpec

                uriString.contains("youtube.com") || uriString.contains("youtu.be") -> {
                    val forceFallback = uriString.contains("fallback=true")
                    val videoId = extractVideoId(uriString)

                    if (videoId == null) {
                        Timber.e("Could not extract videoId from: $uriString")
                        dataSpec
                    } else {
                        // Check cache first — avoids re-fetching on ExoPlayer retries
                        val cached = songUrlCache[videoId]
                        if (cached != null && cached.second > System.currentTimeMillis() && !forceFallback) {
                            Timber.d("Using cached stream URL for $videoId ✅")
                            return@Factory dataSpec.withUri(Uri.parse(cached.first))
                        }

                        Timber.d("Fetching fresh stream URL for $videoId...")
                        val realUrl = runBlocking {
                            Innertube.getStreamUrl(this@MusicService, videoId, forceFallback)
                        }

                        if (realUrl != null) {
                            // Cache the resolved URL
                            songUrlCache[videoId] = realUrl to (System.currentTimeMillis() + STREAM_URL_TTL_MS)
                            Timber.d("Stream URL cached for $videoId ✅")
                            dataSpec.withUri(Uri.parse(realUrl))
                        } else {
                            Timber.e("All stream resolution failed for $videoId ❌")
                            throw DataSourceException(
                                "Failed to resolve stream URL for videoId=$videoId",
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                            )
                        }
                    }
                }

                // Already a resolved stream URL (googlevideo etc) — pass through
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
            .setMediaSourceFactory(DefaultMediaSourceFactory(finalResolvingFactory))
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
        val cleanUrl = url.substringBefore("&fallback=true")
        val patterns = listOf(
            Regex("v=([a-zA-Z0-9_-]{11})"),
            Regex("youtu\\.be/([a-zA-Z0-9_-]{11})"),
            Regex("embed/([a-zA-Z0-9_-]{11})")
        )
        for (pattern in patterns) {
            val match = pattern.find(cleanUrl)
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
        songUrlCache.clear()
        super.onDestroy()
    }
}
