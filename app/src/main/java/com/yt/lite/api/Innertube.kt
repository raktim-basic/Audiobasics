package com.yt.lite.api

import android.content.Context
import android.util.Log
import com.yt.lite.data.Album
import com.yt.lite.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

object Innertube {

    private var initialized = false

    private fun init() {
        if (!initialized) {
            NewPipe.init(NewPipeDownloader)
            initialized = true
        }
    }

    private fun bestThumbnail(thumbnails: List<org.schabi.newpipe.extractor.Image>): String {
        return thumbnails.maxByOrNull { it.width * it.height }?.url ?: ""
    }

    private fun ytThumbnail(videoId: String): String {
        return "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
    }

    // Official YouTube Music album IDs start with OLAK5uy_
    private fun isOfficialAlbum(id: String): Boolean {
        return id.startsWith("OLAK5uy_") || id.startsWith("MPREb_")
    }

    // Detect explicit from title
    private fun isExplicit(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("(explicit)") ||
            lower.contains("[explicit]") ||
            lower.contains("explicit version") ||
            lower.contains("(dirty)") ||
            lower.contains("[dirty]") ||
            lower.contains("(clean)").not() && lower.contains("uncut") ||
            lower.contains("uncensored")
    }

    // Only keep actual music — filter podcasts, gaming videos, etc.
    private fun isMusicStream(item: StreamInfoItem): Boolean {
        val dur = item.duration
        if (dur < 60 || dur > 900) return false // 1min–15min
        val name = (item.name ?: "").lowercase()
        val skip = listOf(
            "podcast", "interview", "episode", "lecture", "audiobook",
            "chapter", "debate", "sermon", "comedy", "stand up", "stand-up",
            "reaction", "commentary", "gameplay", "gaming", "news", "trailer",
            "teaser", "behind the scenes", "vlog", "tutorial", "how to"
        )
        return skip.none { name.contains(it) }
    }

    // Remove duplicate songs — keep explicit version if both exist
    private fun deduplicatePreferExplicit(songs: List<Song>): List<Song> {
        val seen = mutableMapOf<String, Song>()
        for (song in songs) {
            val key = song.title.lowercase()
                .replace("(explicit)", "").replace("[explicit]", "")
                .replace("(dirty)", "").replace("[dirty]", "")
                .replace("(clean)", "").replace("[clean]", "")
                .replace("(radio edit)", "").replace("[radio edit]", "")
                .trim()
            val existing = seen[key]
            if (existing == null) {
                seen[key] = song
            } else if (song.isExplicit && !existing.isExplicit) {
                // Prefer explicit version
                seen[key] = song
            }
        }
        // Return explicit songs first, then non-explicit
        return seen.values.sortedByDescending { it.isExplicit }
    }

    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        init()
        val officialAlbums = mutableListOf<Song>()
        val otherAlbums = mutableListOf<Song>()
        val songs = mutableListOf<Song>()

        try {
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            val items = extractor.initialPage.items

            for (item in items) {
                try {
                    when {
                        item is org.schabi.newpipe.extractor.playlist.PlaylistInfoItem -> {
                            val id = extractId(item.url)
                            val song = Song(
                                id = id,
                                title = item.name ?: continue,
                                artist = "(Album) ${item.uploaderName ?: ""}",
                                thumbnail = bestThumbnail(item.thumbnails),
                                isAlbum = true
                            )
                            if (isOfficialAlbum(id)) {
                                if (officialAlbums.size < 3) officialAlbums.add(song)
                            } else {
                                if (otherAlbums.size < 2) otherAlbums.add(song)
                            }
                        }
                        item is StreamInfoItem -> {
                            val id = extractId(item.url)
                            val title = item.name ?: continue
                            // Only add music streams
                            if (isMusicStream(item)) {
                                songs.add(
                                    Song(
                                        id = id,
                                        title = title,
                                        artist = item.uploaderName ?: "",
                                        thumbnail = ytThumbnail(id),
                                        duration = item.duration * 1000,
                                        isExplicit = isExplicit(title)
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Innertube", "Skipping item: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("Innertube", "Search error: ${e.message}")
        }

        // Official albums first, then other albums, then deduped songs (explicit first)
        val allAlbums = (officialAlbums + otherAlbums).take(3)
        val deduped = deduplicatePreferExplicit(songs)

        val results = mutableListOf<Song>()
        results.addAll(allAlbums)
        results.addAll(deduped)
        results
    }

    suspend fun getVideoMetadata(videoId: String): Song? = withContext(Dispatchers.IO) {
        init()
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val extractor = ServiceList.YouTube.getStreamExtractor(url)
            extractor.fetchPage()

            val title = extractor.name ?: return@withContext null
            val artist = extractor.uploaderName ?: ""
            val duration = extractor.length * 1000L

            Song(
                id = videoId,
                title = title,
                artist = artist,
                thumbnail = ytThumbnail(videoId),
                duration = duration,
                isExplicit = isExplicit(title)
            )
        } catch (e: Exception) {
            Log.e("Innertube", "Metadata fetch error for $videoId: ${e.message}")
            null
        }
    }

    suspend fun getStreamUrl(context: Context, videoId: String): String? =
        withContext(Dispatchers.IO) {
            init()
            try {
                val url = "https://www.youtube.com/watch?v=$videoId"
                val extractor = ServiceList.YouTube.getStreamExtractor(url)
                extractor.fetchPage()

                val audioStreams: List<AudioStream> = extractor.audioStreams
                audioStreams
                    .filter { it.content != null && it.content.isNotEmpty() }
                    .maxByOrNull { it.averageBitrate }
                    ?.content
            } catch (e: Exception) {
                Log.e("Innertube", "Stream error for $videoId: ${e.message}")
                null
            }
        }

    suspend fun getRelatedSongs(context: Context, videoId: String, limit: Int = 10): List<Song> =
        withContext(Dispatchers.IO) {
            init()
            val results = mutableListOf<Song>()
            try {
                val url = "https://www.youtube.com/watch?v=$videoId"
                val extractor = ServiceList.YouTube.getStreamExtractor(url)
                extractor.fetchPage()

                val related = extractor.relatedItems?.items ?: return@withContext results

                for (item in related) {
                    if (results.size >= limit) break
                    try {
                        if (item is StreamInfoItem && isMusicStream(item)) {
                            val id = extractId(item.url)
                            val title = item.name ?: continue
                            results.add(
                                Song(
                                    id = id,
                                    title = title,
                                    artist = item.uploaderName ?: "",
                                    thumbnail = ytThumbnail(id),
                                    duration = item.duration * 1000,
                                    isExplicit = isExplicit(title)
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("Innertube", "Skipping related: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("Innertube", "Related songs error: ${e.message}")
            }
            results
        }

    suspend fun getAlbumSongs(playlistId: String): Pair<Album?, List<Song>> =
        withContext(Dispatchers.IO) {
            init()
            val songs = mutableListOf<Song>()
            var album: Album? = null

            try {
                val url = "https://www.youtube.com/playlist?list=$playlistId"
                val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
                extractor.fetchPage()

                val items = extractor.initialPage.items

                album = Album(
                    id = playlistId,
                    title = extractor.name ?: "Unknown Album",
                    artist = extractor.uploaderName ?: "Unknown Artist",
                    thumbnail = bestThumbnail(extractor.thumbnails),
                    songCount = items.size,
                    youtubeUrl = url
                )

                for (item in items) {
                    try {
                        if (item is StreamInfoItem) {
                            val id = extractId(item.url)
                            val title = item.name ?: continue
                            songs.add(
                                Song(
                                    id = id,
                                    title = title,
                                    artist = item.uploaderName ?: "",
                                    thumbnail = ytThumbnail(id),
                                    duration = item.duration * 1000,
                                    albumId = playlistId,
                                    isExplicit = isExplicit(title)
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("Innertube", "Skipping album item: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("Innertube", "Album fetch error: ${e.message}")
            }

            Pair(album, songs)
        }

    private fun extractId(url: String): String {
        Regex("v=([a-zA-Z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
        Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
        Regex("list=([a-zA-Z0-9_-]+)").find(url)?.let { return it.groupValues[1] }
        return url.substringAfterLast("/").substringBefore("?")
    }
}

object NewPipeDownloader : Downloader() {

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val requestBuilder = okhttp3.Request.Builder()
            .url(request.url())
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
            )
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader(
                "Cookie",
                "CONSENT=YES+; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg"
            )

        request.headers()?.forEach { (key, values) ->
            values.forEach { value -> requestBuilder.addHeader(key, value) }
        }

        when (request.httpMethod()) {
            "POST" -> {
                val body = request.dataToSend() ?: ByteArray(0)
                requestBuilder.post(
                    body.toRequestBody("application/json".toMediaTypeOrNull())
                )
            }
            else -> requestBuilder.get()
        }

        val response = client.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string() ?: ""
        val headers = mutableMapOf<String, MutableList<String>>()
        response.headers.names().forEach { name ->
            headers[name] = response.headers(name).toMutableList()
        }

        return Response(
            response.code,
            response.message,
            headers,
            responseBody,
            request.url()
        )
    }
}
