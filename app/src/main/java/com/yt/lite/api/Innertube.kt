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
import java.util.concurrent.TimeUnit

object Innertube {

    private var initialized = false

    private fun init() {
        if (!initialized) {
            NewPipe.init(NewPipeDownloader)
            initialized = true
        }
    }

    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        init()
        val albums = mutableListOf<Song>()
        val songs = mutableListOf<Song>()

        try {
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            val items = extractor.initialPage.items

            for (item in items) {
                try {
                    when {
                        item is org.schabi.newpipe.extractor.playlist.PlaylistInfoItem -> {
                            if (albums.size < 3) {
                                albums.add(
                                    Song(
                                        id = extractId(item.url),
                                        title = item.name ?: continue,
                                        artist = "(Album) ${item.uploaderName ?: ""}",
                                        thumbnail = item.thumbnails.firstOrNull()?.url ?: "",
                                        isAlbum = true
                                    )
                                )
                            }
                        }
                        item is org.schabi.newpipe.extractor.stream.StreamInfoItem -> {
                            songs.add(
                                Song(
                                    id = extractId(item.url),
                                    title = item.name ?: continue,
                                    artist = item.uploaderName ?: "",
                                    thumbnail = item.thumbnails.firstOrNull()?.url ?: "",
                                    duration = item.duration * 1000
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Innertube", "Skipping item: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("Innertube", "Search error: ${e.message}")
        }

        val results = mutableListOf<Song>()
        results.addAll(albums)
        results.addAll(songs)
        results
    }

    suspend fun getStreamUrl(context: Context, videoId: String): String? =
        withContext(Dispatchers.IO) {
            init()
            try {
                val url = "https://www.youtube.com/watch?v=$videoId"
                val extractor = ServiceList.YouTube.getStreamExtractor(url)
                extractor.fetchPage()

                val audioStreams: List<AudioStream> = extractor.audioStreams
                val stream = audioStreams
                    .filter { it.content != null && it.content.isNotEmpty() }
                    .maxByOrNull { it.averageBitrate }

                stream?.content
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
                        if (item is org.schabi.newpipe.extractor.stream.StreamInfoItem) {
                            results.add(
                                Song(
                                    id = extractId(item.url),
                                    title = item.name ?: continue,
                                    artist = item.uploaderName ?: "",
                                    thumbnail = item.thumbnails.firstOrNull()?.url ?: "",
                                    duration = item.duration * 1000
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("Innertube", "Skipping related item: ${e.message}")
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
                    thumbnail = extractor.thumbnails.firstOrNull()?.url ?: "",
                    songCount = items.size,
                    youtubeUrl = url
                )

                for (item in items) {
                    try {
                        if (item is org.schabi.newpipe.extractor.stream.StreamInfoItem) {
                            songs.add(
                                Song(
                                    id = extractId(item.url),
                                    title = item.name ?: continue,
                                    artist = item.uploaderName ?: "",
                                    thumbnail = item.thumbnails.firstOrNull()?.url ?: "",
                                    duration = item.duration * 1000,
                                    albumId = playlistId
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
        val vMatch = Regex("v=([a-zA-Z0-9_-]{11})").find(url)
        if (vMatch != null) return vMatch.groupValues[1]
        val shortMatch = Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(url)
        if (shortMatch != null) return shortMatch.groupValues[1]
        val listMatch = Regex("list=([a-zA-Z0-9_-]+)").find(url)
        if (listMatch != null) return listMatch.groupValues[1]
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
