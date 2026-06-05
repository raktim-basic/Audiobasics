package com.yt.lite.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
@AndroidEntryPoint
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cache: videoId -> Pair(streamUrl, expiryTimeMs)
    private val songUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val STREAM_URL_TTL_MS = 5 * 60 * 60 * 1000L // 5 hours

    override fun onCreate() {
        super.onCreate()

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
                    delegate = if (dataSpec.uri.scheme == "file") FileDataSource()
                    else httpFactory.createDataSource()
                    return delegate.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int) =
                    delegate.read(buffer, offset, length)

                override fun getUri(): Uri? = delegate.uri
                override fun close() = delegate.close()
                override fun addTransferListener(
                    transferListener: androidx.media3.datasource.TransferListener
                ) = delegate.addTransferListener(transferListener)
            }
        }

        // Resolver: ONLY does cache lookups — never blocks for network
        val finalResolvingFactory = ResolvingDataSource.Factory(finalFactory) { dataSpec ->
            val uriString = dataSpec.uri.toString()
            when {
                uriString.startsWith("file://") -> dataSpec

                uriString.contains("youtube.com") || uriString.contains("youtu.be") -> {
                    val videoId = extractVideoId(uriString)
                    if (videoId == null) {
                        Timber.e("Could not extract videoId from URI")
                        dataSpec
                    } else {
                        val cached = songUrlCache[videoId]
                        if (cached != null && cached.second > System.currentTimeMillis()) {
                            Timber.d("Resolver: cache hit for $videoId ✅")
                            dataSpec.withUri(Uri.parse(cached.first))
                        } else {
                            // Cache miss — wait for pre-resolve to finish (max 15s)
                            Timber.d("Resolver: waiting for pre-resolve of $videoId...")
                            preResolveUrl(videoId, force = false)
                            var waited = 0
                            while (waited < 15000) {
                                val ready = songUrlCache[videoId]
                                if (ready != null && ready.second > System.currentTimeMillis()) {
                                    Timber.d("Resolver: pre-resolve ready for $videoId ✅")
                                    return@Factory dataSpec.withUri(Uri.parse(ready.first))
                                }
                                Thread.sleep(200)
                                waited += 200
                            }
                            Timber.e("Resolver: timed out waiting for $videoId ❌")
                            throw DataSourceException(
                                "Stream URL timed out for $videoId",
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                            )
                        }
                    }
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
            .setMediaSourceFactory(DefaultMediaSourceFactory(finalResolvingFactory))
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Listen for media item transitions to pre-resolve the next song's URL
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                val uri = mediaItem?.localConfiguration?.uri?.toString() ?: return
                val videoId = extractVideoId(uri) ?: return
                preResolveUrl(videoId, force = false)

                // Also pre-resolve next item in queue
                val nextIndex = player.currentMediaItemIndex + 1
                if (nextIndex < player.mediaItemCount) {
                    val nextUri = player.getMediaItemAt(nextIndex)
                        .localConfiguration?.uri?.toString() ?: return
                    val nextVideoId = extractVideoId(nextUri) ?: return
                    preResolveUrl(nextVideoId, force = false)
                }
            }
        })

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
    }

    // Pre-resolve stream URL on IO coroutine — no blocking, no thread starvation
    fun preResolveUrl(videoId: String, force: Boolean = false) {
        val cached = songUrlCache[videoId]
        if (!force && cached != null && cached.second > System.currentTimeMillis()) {
            Timber.d("preResolve: already cached for $videoId ✅")
            return
        }
        serviceScope.launch {
            Timber.d("preResolve: fetching stream for $videoId...")
            val url = Innertube.getStreamUrl(this@MusicService, videoId)
            if (url != null) {
                songUrlCache[videoId] = url to (System.currentTimeMillis() + STREAM_URL_TTL_MS)
                Timber.d("preResolve: cached stream for $videoId ✅")
            } else {
                Timber.e("preResolve: failed for $videoId ❌")
            }
        }
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
