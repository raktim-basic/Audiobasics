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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import androidx.media3.exoplayer.DefaultLoadControl
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

        // SIMPLE OkHttpClient - NO custom interceptors!
        // Let ExoPlayer handle Range headers naturally.
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(iosUserAgent)

        // Wrap with DefaultDataSource to handle file:// URIs automatically
        val finalFactory = DefaultDataSource.Factory(this, okHttpDataSourceFactory)

        // Resolver: cache lookups and pre-resolve (no Range modifications)
        val finalResolvingFactory = ResolvingDataSource.Factory(finalFactory) { dataSpec ->
            val uriString = dataSpec.uri.toString()
            when {
                uriString.startsWith("file://") -> dataSpec

                uriString.contains("youtube.com") || uriString.contains("youtu.be") -> {
                    val forceFallback = uriString.contains("fallback=true")
                    val videoId = extractVideoId(uriString)
                    if (videoId == null) {
                        Timber.e("Could not extract videoId from URI")
                        dataSpec
                    } else {
                        // Invalidate cache on forceFallback so we get a fresh URL
                        if (forceFallback) {
                            Timber.w("Resolver: forceFallback=true, clearing cache for $videoId")
                            songUrlCache.remove(videoId)
                        }

                        val cached = songUrlCache[videoId]
                        if (cached != null && cached.second > System.currentTimeMillis()) {
                            Timber.d("Resolver: cache hit for $videoId ✅")
                            dataSpec.withUri(Uri.parse(cached.first))
                        } else {
                            Timber.d("Resolver: waiting for pre-resolve of $videoId (forceFallback=$forceFallback)...")
                            preResolveUrl(videoId, force = forceFallback)
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

        // Simpler load control – let ExoPlayer decide buffer sizes
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 10 * 60_000, 1_500, 3_000)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setLoadControl(loadControl)
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

                // Probe with GET + Range header (HEAD returns 403 on googlevideo)
                try {
                    val probeClient = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val probeReq = okhttp3.Request.Builder()
                        .url(url)
                        .get()
                        .addHeader("User-Agent", "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)")
                        .addHeader("Range", "bytes=0-1")
                        .build()
                    val probe = probeClient.newCall(probeReq).execute()
                    Timber.d("Stream probe $videoId: HTTP ${probe.code} | type=${probe.header("Content-Type")} | len=${probe.header("Content-Length")} | range=${probe.header("Content-Range")}")
                    probe.close()
                } catch (e: Exception) {
                    Timber.w("Stream probe failed $videoId: ${e.message}")
                }
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
