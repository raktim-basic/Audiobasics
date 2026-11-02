package com.rkd.audiobasics.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.rkd.audiobasics.cache.CacheManager
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Remembers the last queue, the song that was current and where it was, so reopening the app
 * after it was cleared from recents (and the player service stopped) brings the player bar
 * and queue back, paused at the same spot.
 *
 * Lives in the service because the service is the one thing that is still running when the
 * app is cleared, e.g. when a song is paused from the notification afterwards.
 */
class LastSessionStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()

    /** Saves the player's current queue, current item and position. Call on the main thread. */
    fun save(player: Player) {
        val json = snapshot(player)
        writer.execute {
            try {
                if (json == null) {
                    if (file.exists()) file.delete()
                } else {
                    val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                    tmp.writeText(json)
                    if (!tmp.renameTo(file)) {
                        file.writeText(json)
                        tmp.delete()
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "LastSession: save failed")
            }
        }
    }

    /** Lets a pending write finish; used as the service goes away. */
    fun flush() {
        writer.shutdown()
        try {
            writer.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    class Restored(val items: List<MediaItem>, val index: Int, val positionMs: Long)

    /** Reads the saved session, or null if there is none. */
    fun load(): Restored? {
        return try {
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("items")
            val items = ArrayList<MediaItem>(arr.length())
            for (i in 0 until arr.length()) items.add(buildItem(arr.getJSONObject(i)))
            if (items.isEmpty()) return null
            Restored(
                items = items,
                index = root.optInt("index", 0).coerceIn(0, items.lastIndex),
                positionMs = root.optLong("position", 0L).coerceAtLeast(0L)
            )
        } catch (e: Exception) {
            Timber.w(e, "LastSession: load failed")
            null
        }
    }

    private fun snapshot(player: Player): String? {
        val count = player.mediaItemCount
        if (count == 0) return null
        val current = player.currentMediaItemIndex
        val items = JSONArray()
        var savedIndex = 0
        for (i in 0 until count) {
            val obj = player.getMediaItemAt(i).toJson() ?: continue
            if (i == current) savedIndex = items.length()
            items.put(obj)
        }
        if (items.length() == 0) return null
        // A song that already finished restarts from the beginning rather than sitting at its end.
        val position = if (player.playbackState == Player.STATE_ENDED) 0L
        else player.currentPosition.coerceAtLeast(0L)
        return JSONObject()
            .put("index", savedIndex)
            .put("position", position)
            .put("items", items)
            .toString()
    }

    private fun MediaItem.toJson(): JSONObject? {
        val id = mediaId.takeIf { it.isNotBlank() } ?: return null
        val meta = mediaMetadata
        val extras = meta.extras
        return JSONObject()
            .put("id", id)
            .put("title", meta.title?.toString() ?: "")
            .put("artist", meta.artist?.toString() ?: "")
            .put("art", meta.artworkUri?.toString() ?: "")
            .put("dur", meta.durationMs ?: 0L)
            .put("albumId", extras?.getString(ALBUM_ID) ?: "")
            .put("albumTitle", extras?.getString(ALBUM_TITLE) ?: "")
            .put("year", extras?.getString(YEAR) ?: "")
            .put("explicit", extras?.getBoolean(IS_EXPLICIT) ?: false)
            .put("artistNames", JSONArray(extras?.getStringArrayList(ARTIST_NAMES) ?: emptyList<String>()))
            .put("artistIds", JSONArray(extras?.getStringArrayList(ARTIST_IDS) ?: emptyList<String>()))
    }

    private fun buildItem(o: JSONObject): MediaItem {
        val id = o.getString("id")
        // Same rule the app uses when it starts a song: the downloaded file if there is one,
        // otherwise the stream, which the service resolves when playback actually starts.
        val uri = uriFor(appContext, id)
        val cachedThumb = CacheManager.getCachedThumbPath(appContext, id)
        val art = if (cachedThumb != null) Uri.parse("file://$cachedThumb")
        else o.optString("art").takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

        val names = ArrayList<String>()
        o.optJSONArray("artistNames")?.let { for (i in 0 until it.length()) names.add(it.getString(i)) }
        val ids = ArrayList<String>()
        o.optJSONArray("artistIds")?.let { for (i in 0 until it.length()) ids.add(it.getString(i)) }
        val explicit = o.optBoolean("explicit", false)
        val extras = Bundle().apply {
            putString(ALBUM_ID, o.optString("albumId"))
            putString(ALBUM_TITLE, o.optString("albumTitle"))
            putString(YEAR, o.optString("year"))
            putBoolean(IS_EXPLICIT, explicit)
            if (explicit) putLong("android.media.IS_EXPLICIT", 1L)
            putStringArrayList(ARTIST_NAMES, names)
            putStringArrayList(ARTIST_IDS, ids)
        }
        val duration = o.optLong("dur", 0L)
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(o.optString("title"))
                    .setArtist(o.optString("artist"))
                    .setArtworkUri(art)
                    .apply { if (duration > 0) setDurationMs(duration) }
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    companion object {
        private const val FILE_NAME = "last_session.json"

        /** The downloaded file if there is one, otherwise the stream (resolved when playback starts). */
        fun uriFor(context: Context, id: String): String {
            val cached = CacheManager.getCachedFilePath(context.applicationContext, id)
            return if (cached != null) "file://$cached" else "https://www.youtube.com/watch?v=$id"
        }

        // Must match the keys MusicViewModel.buildMediaItem writes (its SongExtraKeys).
        private const val ALBUM_ID = "albumId"
        private const val ALBUM_TITLE = "albumTitle"
        private const val YEAR = "year"
        private const val IS_EXPLICIT = "isExplicit"
        private const val ARTIST_NAMES = "artistNames"
        private const val ARTIST_IDS = "artistIds"
    }
}
