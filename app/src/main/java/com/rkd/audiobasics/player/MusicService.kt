package com.rkd.audiobasics.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Timeline
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
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.rkd.audiobasics.cache.CacheManager
import com.rkd.audiobasics.cache.LoudnessStore
import java.io.File
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
class MusicService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null

    // A library session (not just a plain session) is what lets Android's media resumption ask
    // "what was playing last?" and show that song in the quick-settings media player after the
    // app has been closed.
    private val libraryCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val isRecent = params?.isRecent == true ||
                params?.extras?.getBoolean("android.service.media.extra.RECENT") == true ||
                params?.extras?.getBoolean("androidx.media.MediaBrowserCompat.EXTRA_RECENT") == true
            val id = if (isRecent) RECENT_ROOT_ID else BROWSE_ROOT_ID
            val root = MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = if (parentId == RECENT_ROOT_ID && page == 0) {
                // SystemUI may reconnect after the playback service has been destroyed. In that
                // case the restored ExoPlayer playlist can exist before it has a current timeline,
                // so currentMediaItem is not a reliable source for the recent item. Prefer the
                // live item, then fall back to the same persisted snapshot used for resumption.
                val current = session.player.currentMediaItem
                    ?.takeIf { it.mediaId.isNotBlank() }
                val recent = current ?: lastSession?.load()?.let { saved ->
                    saved.items.getOrNull(saved.index)
                }
                recent?.let { listOf(recentItemFor(it)) } ?: emptyList()
            } else emptyList()
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val current = session.player.currentMediaItem
                ?.takeIf { it.mediaId == mediaId }
            val saved = if (current == null) {
                lastSession?.load()?.items?.firstOrNull { it.mediaId == mediaId }
            } else null
            val item = current ?: saved
            return if (item != null) {
                Futures.immediateFuture(LibraryResult.ofItem(recentItemFor(item), null))
            } else {
                Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
        }

        // Items asked for by id alone (what the system sends when resuming) get their playable URI.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration != null || item.mediaId.isBlank()) item
                else item.buildUpon()
                    .setUri(LastSessionStore.uriFor(this@MusicService, item.mediaId))
                    .build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        // When the player has nothing loaded (e.g. a headset button after the queue was cleared
        // away), bring back the saved session.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Prefer the queue already restored into the live player. SystemUI can request
            // resumption while the player is idle but still has its playlist loaded.
            val player = mediaSession.player
            if (player.mediaItemCount > 0) {
                val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
                val index = player.currentMediaItemIndex.coerceIn(0, items.lastIndex)
                val positionMs = player.currentPosition.coerceAtLeast(0L)
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(items, index, positionMs)
                )
            }

            // If the service has no live queue, recover the last persisted session.
            val saved = lastSession?.load()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(saved.items, saved.index, saved.positionMs)
            )
        }
    }

    // What the system's media player card shows for this app: the last song, with its cover
    // embedded because the system can't read this app's private files.
    private fun recentItemFor(current: MediaItem): MediaItem {
        val art: ByteArray? = CacheManager.getCachedThumbPath(this, current.mediaId)?.let { path ->
            runCatching {
                File(path).takeIf { it.length() in 1..400_000 }?.readBytes()
            }.getOrNull()
        }
        val meta = current.mediaMetadata.buildUpon()
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply { if (art != null) setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            .build()
        return current.buildUpon().setMediaMetadata(meta).build()
    }

    // Keeps the last queue / song / position on disk so a cleared app comes back where it was.
    private var lastSession: LastSessionStore? = null
    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveNowTask = Runnable { saveSession() }
    private val periodicSaveTask = object : Runnable {
        override fun run() {
            saveSession()
            saveHandler.postDelayed(this, 10_000)
        }
    }

    private fun saveSession() {
        val player = mediaSession?.player ?: return
        lastSession?.save(player)
    }

    // Queue edits and seeks come in bursts; write once things settle.
    private fun saveSessionSoon() {
        saveHandler.removeCallbacks(saveNowTask)
        saveHandler.postDelayed(saveNowTask, 1_000)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Loudness normalisation ───────────────────────────────────────────────────────────
    // YouTube reports how loud each track is (dB above its reference level). When the setting
    // is on, tracks louder than that are turned down by the difference through the player's own
    // volume, so songs sit at a similar level. Quiet tracks are left alone (never boosted, so
    // nothing can clip). Unknown loudness = untouched.
    private val settingsPrefs: SharedPreferences by lazy {
        getSharedPreferences("ytlite", Context.MODE_PRIVATE)
    }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == LOUDNESS_PREF_KEY) saveHandler.post { applyLoudness() }
    }

    /** Sets the player volume for whatever is current. Main thread only. */
    private fun applyLoudness() {
        val player = mediaSession?.player ?: return
        val enabled = settingsPrefs.getBoolean(LOUDNESS_PREF_KEY, true)
        val db = if (enabled) {
            player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
                ?.let { LoudnessStore.get(this, it) }
        } else null
        val volume = if (db != null && db > 0f) {
            Math.pow(10.0, -db / 20.0).toFloat().coerceIn(0.1f, 1f)
        } else 1f
        if (player.volume != volume) player.volume = volume
    }

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

        const val LOUDNESS_PREF_KEY = "loudness_normalization"

        private const val BROWSE_ROOT_ID = "audiobasics_root"
        private const val RECENT_ROOT_ID = "audiobasics_recent"
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
                                    // right away (unless YouTube said the song needs login —
                                    // a retry can't change that); if that fails too, stop.
                                    if (restarted || Innertube.getLastFailure(videoId)?.loginRequired == true) break
                                    restarted = true
                                    Timber.w("Resolver: lookup for $videoId ended without a URL — retrying once")
                                    preResolveUrl(videoId, force = true)
                                }
                                Thread.sleep(100)
                                waited += 100
                            }
                            if (Innertube.getLastFailure(videoId) != null) {
                                // The lookup finished and failed (not a timeout). ExoPlayer would
                                // otherwise re-run this whole resolve 3 more times, each with its
                                // own lookups, before reporting the error — see StreamUnavailableException.
                                Timber.e("Resolver: no stream available for $videoId ❌")
                                throw StreamUnavailableException("No stream available for $videoId")
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
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(finalResolvingFactory)
                    .setLoadErrorHandlingPolicy(NoRetryWhenUnavailablePolicy())
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Bring back the previous queue, song and position, paused. Nothing is prepared, so no
        // network is touched until the user presses play (the session prepares on demand).
        val store = LastSessionStore(this)
        lastSession = store
        store.load()?.let { restored ->
            player.setMediaItems(restored.items, restored.index, restored.positionMs)
            Timber.d("LastSession: restored ${restored.items.size} songs at index ${restored.index}")
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveHandler.removeCallbacks(periodicSaveTask)
                if (isPlaying) {
                    // Keep the saved position fresh while playing, in case the system ends the service.
                    saveHandler.postDelayed(periodicSaveTask, 10_000)
                } else {
                    saveHandler.removeCallbacks(saveNowTask)
                    saveSession()
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) = saveSessionSoon()

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) saveSessionSoon()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) saveSession()
                if (playbackState == Player.STATE_READY) applyLoudness()
            }
        })

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                saveSessionSoon()
                applyLoudness()
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

        mediaSession = MediaLibrarySession.Builder(this, player, libraryCallback)
            .setSessionActivity(pendingIntent)
            .build()

        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)
        applyLoudness()
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
                // This lookup also learned the song's loudness; if it's the one about to play,
                // apply it now.
                saveHandler.post {
                    if (mediaSession?.player?.currentMediaItem?.mediaId == videoId) applyLoudness()
                }
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Whatever happens next (the service may stop if nothing is playing), keep the latest state.
        saveHandler.removeCallbacks(saveNowTask)
        saveSession()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        settingsPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
        saveHandler.removeCallbacks(periodicSaveTask)
        saveHandler.removeCallbacks(saveNowTask)
        saveSession()
        lastSession?.flush()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        songUrlCache.clear()
        super.onDestroy()
    }
}


/** A stream lookup completed and found nothing playable (login required, blocked, ...). */
private class StreamUnavailableException(message: String) : java.io.IOException(message)

/**
 * ExoPlayer retries IO errors up to 3 times with backoff, and every retry re-runs the stream
 * lookup. For a definitive "no stream" answer that just makes the spinner last minutes, so
 * report it right away and let the app's own single fallback retry + error dialog take over.
 * Everything else (real network errors) keeps the default retry behaviour.
 */
@UnstableApi
private class NoRetryWhenUnavailablePolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        var t: Throwable? = loadErrorInfo.exception
        while (t != null) {
            if (t is StreamUnavailableException) return C.TIME_UNSET
            t = t.cause
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
