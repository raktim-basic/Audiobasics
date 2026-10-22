package com.rkd.audiobasics.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
import com.rkd.audiobasics.MainActivity
import com.rkd.audiobasics.api.Innertube
import com.rkd.audiobasics.api.cipher.CipherDeobfuscator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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

    // Resolved stream URLs are keyed by video AND the audio quality in effect, so changing the
    // quality setting (or Auto flipping between Wi-Fi and mobile data) re-resolves instead of
    // reusing a URL picked under the old quality.
    private fun urlKey(videoId: String) = "$videoId|${Innertube.effectiveAudioQuality(this)}"

    companion object {
        // 512KB chunks — same as Metrolist. Keeps each Range request small so we
        // don't exhaust the CDN's per-URL request limit.
        private const val CHUNK_LENGTH = 512 * 1024L

        // How long a play request may wait for its stream URL. Only an upper bound now: the
        // wait ends early (and retries once) as soon as the lookup itself finishes without a
        // URL, instead of sitting out a fixed timer.
        private const val RESOLVE_WAIT_MS = 30_000
    }

    // One lookup per song at a time — the player, onMediaItemTransition and the resolver all
    // ask for the same video, and each used to launch (and log) its own copy.
    private val resolveJobs = ConcurrentHashMap<String, Job>()

    private fun isResolving(videoId: String) = resolveJobs[urlKey(videoId)]?.isActive == true

    override fun onCreate() {
        super.onCreate()

        // Startup sequence — runs in parallel on IO:
        // 1. Innertube.init() — fetches visitorData + signatureTimestamp
        // 2. CipherDeobfuscator.warmUp() — pre-creates CipherWebView so it's ready
        //    before the first song plays. Without warmup, CipherWebView creation
        //    takes ~8-10s and causes the resolver to time out.
        serviceScope.launch {
            Innertube.init()
            // warmUp after init so signatureTimestamp is available for player JS analysis
            CipherDeobfuscator.warmUp()
        }
        // Independent of the cipher: build the poToken WebView in parallel so the first
        // song doesn't wait for BotGuard.
        serviceScope.launch { Innertube.warmPoToken() }

        val iosUserAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(iosUserAgent)

        val finalFactory = DefaultDataSource.Factory(this, okHttpDataSourceFactory)

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
                        if (forceFallback) {
                            Timber.w("Resolver: forceFallback=true, clearing cache for $videoId")
                            songUrlCache.remove(urlKey(videoId))
                        }

                        val cached = songUrlCache[urlKey(videoId)]
                        if (cached != null && cached.second > System.currentTimeMillis()) {
                            Timber.d("Resolver: cache hit for $videoId ✅")
                            dataSpec.withUri(Uri.parse(cached.first))
                                .subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
                        } else {
                            Timber.d("Resolver: waiting for pre-resolve of $videoId (forceFallback=$forceFallback)...")
                            preResolveUrl(videoId, force = forceFallback)
                            var waited = 0
                            var restarted = false
                            while (waited < RESOLVE_WAIT_MS) {
                                val ready = songUrlCache[urlKey(videoId)]
                                if (ready != null && ready.second > System.currentTimeMillis()) {
                                    Timber.d("Resolver: pre-resolve ready for $videoId ✅")
                                    return@Factory dataSpec.withUri(Uri.parse(ready.first))
                                        .subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
                                }
                                if (!isResolving(videoId)) {
                                    // The lookup ended without producing a URL. Retry once
                                    // right away; if that fails too, stop waiting.
                                    if (restarted) break
                                    restarted = true
                                    Timber.w("Resolver: lookup for $videoId ended without a URL — retrying once")
                                    preResolveUrl(videoId, force = true)
                                }
                                Thread.sleep(100)
                                waited += 100
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

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                val uri = mediaItem?.localConfiguration?.uri?.toString() ?: return
                val videoId = extractVideoId(uri) ?: return
                preResolveUrl(videoId, force = false)

                // Pre-resolve next item in queue
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

    fun preResolveUrl(videoId: String, force: Boolean = false) {
        val cached = songUrlCache[urlKey(videoId)]
        if (!force && cached != null && cached.second > System.currentTimeMillis()) {
            Timber.d("preResolve: already cached for $videoId ✅")
            return
        }
        val key = urlKey(videoId)
        val running = resolveJobs[key]
        if (running != null) {
            if (running.isActive) return
            // Finished but its completion handler hasn't unregistered it yet — clear it so a
            // retry isn't swallowed by the stale entry.
            resolveJobs.remove(key, running)
        }
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            Timber.d("preResolve: fetching stream for $videoId...")
            val result = Innertube.getStreamUrl(this@MusicService, videoId)
            if (result != null) {
                val (url, expiryMs) = result
                songUrlCache[urlKey(videoId)] = url to expiryMs
                Timber.d("preResolve: cached stream for $videoId ✅ (expires in ${(expiryMs - System.currentTimeMillis()) / 60000}min)")

                // Probe URL to confirm it's alive
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
        if (resolveJobs.putIfAbsent(key, job) != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { resolveJobs.remove(key, job) }
        job.start()
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
