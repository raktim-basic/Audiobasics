package com.yt.lite.api

import android.content.Context
import android.util.Log
import com.yt.lite.data.Album
import com.yt.lite.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import java.util.concurrent.TimeUnit

object Innertube {

    private var initialized = false

    private val ytMusicClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // YouTube Music API constants
    private const val YTM_BASE = "https://music.youtube.com/youtubei/v1"
    private const val YTM_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val YTM_CLIENT_NAME = "WEB_REMIX"
    private const val YTM_CLIENT_VERSION = "1.20240101.01.00"

    private fun ytmContext(): JSONObject {
        return JSONObject().put(
            "client", JSONObject()
                .put("clientName", YTM_CLIENT_NAME)
                .put("clientVersion", YTM_CLIENT_VERSION)
                .put("hl", "en")
                .put("gl", "US")
        )
    }

    private fun ytmPost(endpoint: String, body: JSONObject): JSONObject? {
        return try {
            body.put("context", ytmContext())
            val request = Request.Builder()
                .url("$YTM_BASE/$endpoint?key=$YTM_KEY")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Origin", "https://music.youtube.com")
                .addHeader("Referer", "https://music.youtube.com/")
                .addHeader("X-YouTube-Client-Name", "67")
                .addHeader("X-YouTube-Client-Version", YTM_CLIENT_VERSION)
                .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            val response = ytMusicClient.newCall(request).execute()
            val text = response.body?.string() ?: return null
            JSONObject(text)
        } catch (e: Exception) {
            Log.e("Innertube", "YTM post error: ${e.message}")
            null
        }
    }

    private fun ytThumbnail(videoId: String): String {
        return "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
    }

    // Parse explicit badge from YouTube Music response
    private fun parseExplicit(badges: JSONArray?): Boolean {
        if (badges == null) return false
        for (i in 0 until badges.length()) {
            try {
                val badge = badges.getJSONObject(i)
                val label = badge
                    .optJSONObject("musicInlineBadgeRenderer")
                    ?.optJSONObject("accessibilityData")
                    ?.optJSONObject("accessibilityData")
                    ?.optString("label") ?: ""
                if (label.equals("Explicit", ignoreCase = true)) return true
            } catch (_: Exception) {}
        }
        return false
    }

    // Extract text from YouTube Music runs
    private fun extractText(obj: JSONObject?, key: String): String {
        return try {
            val runs = obj?.optJSONObject(key)?.optJSONArray("runs")
            if (runs != null && runs.length() > 0) {
                runs.getJSONObject(0).optString("text", "")
            } else ""
        } catch (_: Exception) { "" }
    }

    // Extract video ID from navigationEndpoint
    private fun extractVideoId(endpoint: JSONObject?): String? {
        return try {
            endpoint?.optJSONObject("watchEndpoint")?.optString("videoId")
                ?: endpoint?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")?.optString("videoId")
        } catch (_: Exception) { null }
    }

    // Extract playlist ID
    private fun extractPlaylistId(endpoint: JSONObject?): String? {
        return try {
            endpoint?.optJSONObject("browseEndpoint")?.optString("browseId")
                ?: endpoint?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")?.optString("browseId")
        } catch (_: Exception) { null }
    }

    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Song>()
        val songs = mutableListOf<Song>()
        val albums = mutableListOf<Song>()

        try {
            val body = JSONObject()
                .put("query", query)
                .put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF") // Songs filter

            val songsResponse = ytmPost("search", body)
            parseSongsFromYTM(songsResponse, songs)

            // Also search albums
            val albumBody = JSONObject()
                .put("query", query)
                .put("params", "EgWKAQIYAWoMEA4QChADEAQQCRAF") // Albums filter

            val albumsResponse = ytmPost("search", albumBody)
            parseAlbumsFromYTM(albumsResponse, albums)

        } catch (e: Exception) {
            Log.e("Innertube", "YTM search error: ${e.message}")
        }

        // If YTM failed, fall back to NewPipe
        if (songs.isEmpty()) {
            Log.w("Innertube", "YTM returned no songs, falling back to NewPipe")
            fallbackSearch(query, songs, albums)
        }

        // Explicit songs first, then others
        val sortedSongs = songs.sortedByDescending { it.isExplicit }
        results.addAll(albums.take(3))
        results.addAll(sortedSongs)
        results
    }

    private fun parseSongsFromYTM(response: JSONObject?, out: MutableList<Song>) {
        try {
            val sections = response
                ?.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
                ?.getJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return

            for (si in 0 until sections.length()) {
                val section = sections.optJSONObject(si) ?: continue
                val items = section
                    .optJSONObject("musicShelfRenderer")
                    ?.optJSONArray("contents") ?: continue

                for (ii in 0 until items.length()) {
                    try {
                        val item = items.getJSONObject(ii)
                            .optJSONObject("musicResponsiveListItemRenderer") ?: continue

                        val overlay = item.optJSONObject("overlay")
                            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("musicPlayButtonRenderer")
                        val playEndpoint = overlay?.optJSONObject("playNavigationEndpoint")
                        val videoId = extractVideoId(playEndpoint) ?: continue

                        // Title from first column
                        val flexCols = item.optJSONArray("flexColumns") ?: continue
                        val col0 = flexCols.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val title = extractText(col0, "text")
                        if (title.isBlank()) continue

                        // Artist + explicit from second column
                        val col1 = flexCols.optJSONObject(1)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val col1Text = col1?.optJSONObject("text")
                        val runs = col1Text?.optJSONArray("runs")
                        var artist = ""
                        var durationMs = 0L
                        if (runs != null) {
                            for (r in 0 until runs.length()) {
                                val run = runs.optJSONObject(r) ?: continue
                                val t = run.optString("text", "")
                                if (t == " • " || t == "•" || t.isBlank()) continue
                                // Last run is usually duration
                                if (r == runs.length() - 1 && t.contains(":")) {
                                    val parts = t.split(":")
                                    durationMs = when (parts.size) {
                                        2 -> (parts[0].toLongOrNull() ?: 0) * 60000 +
                                                (parts[1].toLongOrNull() ?: 0) * 1000
                                        3 -> (parts[0].toLongOrNull() ?: 0) * 3600000 +
                                                (parts[1].toLongOrNull() ?: 0) * 60000 +
                                                (parts[2].toLongOrNull() ?: 0) * 1000
                                        else -> 0L
                                    }
                                } else if (artist.isEmpty()) {
                                    artist = t
                                }
                            }
                        }

                        val badges = item.optJSONArray("badges")
                        val explicit = parseExplicit(badges)

                        out.add(
                            Song(
                                id = videoId,
                                title = title,
                                artist = artist,
                                thumbnail = ytThumbnail(videoId),
                                duration = durationMs,
                                isExplicit = explicit
                            )
                        )
                    } catch (e: Exception) {
                        Log.w("Innertube", "Skipping song item: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Innertube", "parseSongsFromYTM error: ${e.message}")
        }
    }

    private fun parseAlbumsFromYTM(response: JSONObject?, out: MutableList<Song>) {
        try {
            val sections = response
                ?.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
                ?.getJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return

            for (si in 0 until sections.length()) {
                val section = sections.optJSONObject(si) ?: continue
                val items = section
                    .optJSONObject("musicShelfRenderer")
                    ?.optJSONArray("contents") ?: continue

                for (ii in 0 until items.length()) {
                    if (out.size >= 3) break
                    try {
                        val item = items.getJSONObject(ii)
                            .optJSONObject("musicResponsiveListItemRenderer") ?: continue

                        val flexCols = item.optJSONArray("flexColumns") ?: continue
                        val col0 = flexCols.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val title = extractText(col0, "text")
                        if (title.isBlank()) continue

                        val col1 = flexCols.optJSONObject(1)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val col1Text = col1?.optJSONObject("text")
                        val runs = col1Text?.optJSONArray("runs")
                        var artist = ""
                        if (runs != null) {
                            for (r in 0 until runs.length()) {
                                val t = runs.optJSONObject(r)?.optString("text", "") ?: ""
                                if (t != " • " && t != "•" && t.isNotBlank() &&
                                    !t.contains(":") && artist.isEmpty()) {
                                    artist = t
                                }
                            }
                        }

                        // Get playlist/browse ID
                        val menuItems = item.optJSONObject("menu")
                            ?.optJSONObject("menuRenderer")
                            ?.optJSONArray("items")
                        var browseId = ""
                        if (menuItems != null) {
                            for (m in 0 until menuItems.length()) {
                                val nav = menuItems.optJSONObject(m)
                                    ?.optJSONObject("menuNavigationItemRenderer")
                                    ?.optJSONObject("navigationEndpoint")
                                val bid = extractPlaylistId(nav)
                                if (bid != null && bid.isNotEmpty()) {
                                    browseId = bid
                                    break
                                }
                            }
                        }
                        if (browseId.isEmpty()) continue

                        // Get thumbnail
                        val thumbnails = item.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        val thumb = if (thumbnails != null && thumbnails.length() > 0) {
                            thumbnails.getJSONObject(thumbnails.length() - 1)
                                .optString("url", "")
                        } else ""

                        out.add(
                            Song(
                                id = browseId,
                                title = title,
                                artist = "(Album) $artist",
                                thumbnail = thumb,
                                isAlbum = true
                            )
                        )
                    } catch (e: Exception) {
                        Log.w("Innertube", "Skipping album item: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Innertube", "parseAlbumsFromYTM error: ${e.message}")
        }
    }

    // NewPipe fallback if YTM fails
    private fun fallbackSearch(
        query: String,
        songs: MutableList<Song>,
        albums: MutableList<Song>
    ) {
        try {
            if (!initialized) { NewPipe.init(NewPipeDownloader); initialized = true }
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            val items = extractor.initialPage.items

            for (item in items) {
                try {
                    when (item) {
                        is org.schabi.newpipe.extractor.playlist.PlaylistInfoItem -> {
                            if (albums.size < 3) {
                                val id = extractIdFromUrl(item.url)
                                albums.add(Song(
                                    id = id,
                                    title = item.name ?: continue,
                                    artist = "(Album) ${item.uploaderName ?: ""}",
                                    thumbnail = item.thumbnails
                                        .maxByOrNull { it.width * it.height }?.url ?: "",
                                    isAlbum = true
                                ))
                            }
                        }
                        is org.schabi.newpipe.extractor.stream.StreamInfoItem -> {
                            val dur = item.duration
                            if (dur < 60 || dur > 900) continue
                            val id = extractIdFromUrl(item.url)
                            val title = item.name ?: continue
                            songs.add(Song(
                                id = id,
                                title = title,
                                artist = item.uploaderName ?: "",
                                thumbnail = ytThumbnail(id),
                                duration = dur * 1000,
                                isExplicit = title.lowercase().contains("explicit") ||
                                    title.lowercase().contains("dirty")
                            ))
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("Innertube", "Fallback search error: ${e.message}")
        }
    }

    suspend fun getVideoMetadata(videoId: String): Song? = withContext(Dispatchers.IO) {
        initNewPipe()
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val extractor = ServiceList.YouTube.getStreamExtractor(url)
            extractor.fetchPage()
            val title = extractor.name ?: return@withContext null
            Song(
                id = videoId,
                title = title,
                artist = extractor.uploaderName ?: "",
                thumbnail = ytThumbnail(videoId),
                duration = extractor.length * 1000L,
                isExplicit = title.lowercase().contains("explicit")
            )
        } catch (e: Exception) {
            Log.e("Innertube", "Metadata error: ${e.message}")
            null
        }
    }

    suspend fun getStreamUrl(context: Context, videoId: String): String? =
        withContext(Dispatchers.IO) {
            initNewPipe()
            try {
                val url = "https://www.youtube.com/watch?v=$videoId"
                val extractor = ServiceList.YouTube.getStreamExtractor(url)
                extractor.fetchPage()
                val streams: List<AudioStream> = extractor.audioStreams
                streams.filter { it.content != null && it.content.isNotEmpty() }
                    .maxByOrNull { it.averageBitrate }?.content
            } catch (e: Exception) {
                Log.e("Innertube", "Stream error for $videoId: ${e.message}")
                null
            }
        }

    suspend fun getRelatedSongs(context: Context, videoId: String, limit: Int = 10): List<Song> =
        withContext(Dispatchers.IO) {
            initNewPipe()
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
                            val dur = item.duration
                            if (dur < 60 || dur > 900) continue
                            val id = extractIdFromUrl(item.url)
                            val title = item.name ?: continue
                            results.add(Song(
                                id = id,
                                title = title,
                                artist = item.uploaderName ?: "",
                                thumbnail = ytThumbnail(id),
                                duration = dur * 1000
                            ))
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e("Innertube", "Related songs error: ${e.message}")
            }
            results
        }

    suspend fun getAlbumSongs(playlistId: String): Pair<Album?, List<Song>> =
        withContext(Dispatchers.IO) {
            initNewPipe()
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
                    thumbnail = extractor.thumbnails
                        .maxByOrNull { it.width * it.height }?.url ?: "",
                    songCount = items.size,
                    youtubeUrl = url
                )
                for (item in items) {
                    try {
                        if (item is org.schabi.newpipe.extractor.stream.StreamInfoItem) {
                            val id = extractIdFromUrl(item.url)
                            val title = item.name ?: continue
                            songs.add(Song(
                                id = id,
                                title = title,
                                artist = item.uploaderName ?: "",
                                thumbnail = ytThumbnail(id),
                                duration = item.duration * 1000,
                                albumId = playlistId,
                                isExplicit = title.lowercase().contains("explicit")
                            ))
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e("Innertube", "Album fetch error: ${e.message}")
            }
            Pair(album, songs)
        }

    private fun initNewPipe() {
        if (!initialized) {
            NewPipe.init(NewPipeDownloader)
            initialized = true
        }
    }

    private fun extractIdFromUrl(url: String): String {
        Regex("v=([a-zA-Z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
        Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
        Regex("list=([a-zA-Z0-9_-]+)").find(url)?.let { return it.groupValues[1] }
        return url.substringAfterLast("/").substringBefore("?")
    }
}

object NewPipeDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val rb = Request.Builder()
            .url(request.url())
            .addHeader("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Cookie",
                "CONSENT=YES+; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")

        request.headers()?.forEach { (k, v) -> v.forEach { rb.addHeader(k, it) } }

        when (request.httpMethod()) {
            "POST" -> {
                val body = request.dataToSend() ?: ByteArray(0)
                rb.post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            }
            else -> rb.get()
        }

        val response = client.newCall(rb.build()).execute()
        val responseBody = response.body?.string() ?: ""
        val headers = mutableMapOf<String, MutableList<String>>()
        response.headers.names().forEach { name ->
            headers[name] = response.headers(name).toMutableList()
        }
        return Response(response.code, response.message, headers, responseBody, request.url())
    }
}
