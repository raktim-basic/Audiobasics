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

    private const val YTM_BASE = "https://music.youtube.com/youtubei/v1"
    private val YTM_KEY = buildString {
        append("AIzaSyC9XL3ZjWdd")
        append("Xya6X74dJoCTL-WE")
        append("YFDNX30")
    }
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

    private fun parseExplicit(badges: JSONArray?): Boolean {
        if (badges == null) return false
        for (i in 0 until badges.length()) {
            try {
                val label = badges.getJSONObject(i)
                    .optJSONObject("musicInlineBadgeRenderer")
                    ?.optJSONObject("accessibilityData")
                    ?.optJSONObject("accessibilityData")
                    ?.optString("label") ?: ""
                if (label.equals("Explicit", ignoreCase = true)) return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun extractText(obj: JSONObject?, key: String): String {
        return try {
            val runs = obj?.optJSONObject(key)?.optJSONArray("runs")
            if (runs != null && runs.length() > 0)
                runs.getJSONObject(0).optString("text", "")
            else ""
        } catch (_: Exception) { "" }
    }

    private fun extractVideoId(endpoint: JSONObject?): String? {
        return try {
            endpoint?.optJSONObject("watchEndpoint")?.optString("videoId")
                ?: endpoint?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")?.optString("videoId")
        } catch (_: Exception) { null }
    }

    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val albums = mutableListOf<Song>()

        try {
            val songsResponse = ytmPost(
                "search",
                JSONObject().put("query", query)
                    .put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF")
            )
            parseSongsFromYTM(songsResponse, songs)

            val albumsResponse = ytmPost(
                "search",
                JSONObject().put("query", query)
                    .put("params", "EgWKAQIYAWoMEA4QChADEAQQCRAF")
            )
            parseAlbumsFromYTM(albumsResponse, albums)

        } catch (e: Exception) {
            Log.e("Innertube", "YTM search error: ${e.message}")
        }

        if (songs.isEmpty()) {
            Log.w("Innertube", "YTM returned no songs, falling back to NewPipe")
            fallbackSearch(query, songs, albums)
        }

        val results = mutableListOf<Song>()
        results.addAll(albums.take(3))
        results.addAll(songs.sortedByDescending { it.isExplicit })
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
                val items = sections.optJSONObject(si)
                    ?.optJSONObject("musicShelfRenderer")
                    ?.optJSONArray("contents") ?: continue

                for (ii in 0 until items.length()) {
                    try {
                        val item = items.getJSONObject(ii)
                            .optJSONObject("musicResponsiveListItemRenderer") ?: continue

                        val overlay = item.optJSONObject("overlay")
                            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("musicPlayButtonRenderer")
                        val videoId = extractVideoId(
                            overlay?.optJSONObject("playNavigationEndpoint")
                        ) ?: continue

                        val flexCols = item.optJSONArray("flexColumns") ?: continue
                        val col0 = flexCols.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val title = extractText(col0, "text")
                        if (title.isBlank()) continue

                        val col1 = flexCols.optJSONObject(1)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val runs = col1?.optJSONObject("text")?.optJSONArray("runs")
                        var artist = ""
                        var durationMs = 0L

                        if (runs != null) {
                            for (r in 0 until runs.length()) {
                                val t = runs.optJSONObject(r)
                                    ?.optString("text", "") ?: ""
                                if (t == " • " || t == "•" || t.isBlank()) continue
                                if (r == runs.length() - 1 && t.contains(":")) {
                                    val parts = t.trim().split(":")
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

                        out.add(
                            Song(
                                id = videoId,
                                title = title,
                                artist = artist,
                                thumbnail = ytThumbnail(videoId),
                                duration = durationMs,
                                isExplicit = parseExplicit(item.optJSONArray("badges"))
                            )
                        )
                    } catch (e: Exception) {
                        Log.w("Innertube", "Skipping song: ${e.message}")
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
                val items = sections.optJSONObject(si)
                    ?.optJSONObject("musicShelfRenderer")
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
                        val runs = col1?.optJSONObject("text")?.optJSONArray("runs")
                        var artist = ""
                        if (runs != null) {
                            var nonSepCount = 0
                            for (r in 0 until runs.length()) {
                                val t = runs.optJSONObject(r)
                                    ?.optString("text", "") ?: ""
                                if (t == " • " || t == "•" || t.isBlank()) continue
                                nonSepCount++
                                if (nonSepCount == 1) continue
                                if (nonSepCount == 2 && !t.all { c -> c.isDigit() }) {
                                    artist = t
                                    break
                                }
                            }
                        }

                        var browseId = ""
                        val titleRuns = col0?.optJSONObject("text")?.optJSONArray("runs")
                        if (titleRuns != null && titleRuns.length() > 0) {
                            browseId = titleRuns.getJSONObject(0)
                                .optJSONObject("navigationEndpoint")
                                ?.optJSONObject("browseEndpoint")
                                ?.optString("browseId") ?: ""
                        }

                        if (browseId.isEmpty()) {
                            val overlay = item.optJSONObject("overlay")
                                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                ?.optJSONObject("content")
                                ?.optJSONObject("musicPlayButtonRenderer")
                            browseId = overlay?.optJSONObject("playNavigationEndpoint")
                                ?.optJSONObject("watchPlaylistEndpoint")
                                ?.optString("playlistId") ?: ""
                        }

                        if (browseId.isEmpty()) continue

                        val thumbnails = item.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        val thumb = if (thumbnails != null && thumbnails.length() > 0)
                            thumbnails.getJSONObject(thumbnails.length() - 1)
                                .optString("url", "")
                        else ""

                        out.add(
                            Song(
                                id = browseId,
                                title = title,
                                artist = "(Album) ${artist.ifBlank { "Unknown Artist" }}",
                                thumbnail = thumb,
                                isAlbum = true
                            )
                        )
                    } catch (e: Exception) {
                        Log.w("Innertube", "Skipping album: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Innertube", "parseAlbumsFromYTM error: ${e.message}")
        }
    }

    private fun fallbackSearch(
        query: String,
        songs: MutableList<Song>,
        albums: MutableList<Song>
    ) {
        try {
            initNewPipe()
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            for (item in extractor.initialPage.items) {
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

    suspend fun getAlbumSongs(browseId: String): Pair<Album?, List<Song>> =
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<Song>()
            var album: Album? = null

            try {
                var albumTitle = "Unknown Album"
                var albumArtist = "Unknown Artist"
                var albumThumb = ""

                val response = ytmPost("browse", JSONObject().put("browseId", browseId))
                    ?: return@withContext Pair(null, emptyList())

                // Parse header
                val header = response.optJSONObject("header")
                    ?.optJSONObject("musicImmersiveHeaderRenderer")
                    ?: response.optJSONObject("header")
                        ?.optJSONObject("musicDetailHeaderRenderer")

                albumTitle = header?.optJSONObject("title")
                    ?.optJSONArray("runs")?.optJSONObject(0)
                    ?.optString("text") ?: "Unknown Album"

                val subtitleRuns = header?.optJSONObject("subtitle")
                    ?.optJSONArray("runs")
                if (subtitleRuns != null) {
                    var nonSep = 0
                    for (r in 0 until subtitleRuns.length()) {
                        val t = subtitleRuns.optJSONObject(r)
                            ?.optString("text", "") ?: ""
                        if (t == " • " || t == "•" || t.isBlank()) continue
                        nonSep++
                        if (nonSep == 2 && !t.all { c -> c.isDigit() }) {
                            albumArtist = t; break
                        }
                    }
                }

                val thumbArr = header?.optJSONObject("thumbnail")
                    ?.optJSONObject("croppedSquareThumbnailRenderer")
                    ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    ?: header?.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")

                albumThumb = if (thumbArr != null && thumbArr.length() > 0)
                    thumbArr.getJSONObject(thumbArr.length() - 1).optString("url", "")
                else ""

                // PRIMARY PATH (Metrolist method):
                // twoColumnBrowseResultsRenderer → secondaryContents →
                // sectionListRenderer → contents[0] → musicPlaylistShelfRenderer
                val shelfContents = response
                    .optJSONObject("contents")
                    ?.optJSONObject("twoColumnBrowseResultsRenderer")
                    ?.optJSONObject("secondaryContents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                    ?.optJSONObject(0)
                    ?.optJSONObject("musicPlaylistShelfRenderer")
                    ?.optJSONArray("contents")

                if (shelfContents != null) {
                    for (i in 0 until shelfContents.length()) {
                        try {
                            val item = shelfContents.getJSONObject(i)
                                .optJSONObject("musicResponsiveListItemRenderer") ?: continue

                            // Video ID from overlay
                            val videoId = item.optJSONObject("overlay")
                                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                ?.optJSONObject("content")
                                ?.optJSONObject("musicPlayButtonRenderer")
                                ?.optJSONObject("playNavigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId")
                                ?: item.optJSONArray("flexColumns")
                                    ?.optJSONObject(0)
                                    ?.optJSONObject(
                                        "musicResponsiveListItemFlexColumnRenderer"
                                    )
                                    ?.optJSONObject("text")
                                    ?.optJSONArray("runs")
                                    ?.optJSONObject(0)
                                    ?.optJSONObject("navigationEndpoint")
                                    ?.optJSONObject("watchEndpoint")
                                    ?.optString("videoId")
                                ?: continue

                            val flexCols = item.optJSONArray("flexColumns") ?: continue
                            val col0 = flexCols.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            val songTitle = extractText(col0, "text")
                            if (songTitle.isBlank()) continue

                            // Duration — scan all flex columns for MM:SS pattern
                            var durationMs = 0L
                            outer@ for (fc in 0 until flexCols.length()) {
                                val col = flexCols.optJSONObject(fc)
                                    ?.optJSONObject(
                                        "musicResponsiveListItemFlexColumnRenderer"
                                    )
                                val runs = col?.optJSONObject("text")?.optJSONArray("runs")
                                if (runs != null) {
                                    for (r in 0 until runs.length()) {
                                        val t = runs.optJSONObject(r)
                                            ?.optString("text", "") ?: ""
                                        if (t.matches(Regex("\\d+:\\d{2}"))) {
                                            val parts = t.trim().split(":")
                                            durationMs = when (parts.size) {
                                                2 -> (parts[0].toLongOrNull() ?: 0) * 60000 +
                                                        (parts[1].toLongOrNull() ?: 0) * 1000
                                                3 -> (parts[0].toLongOrNull() ?: 0) * 3600000 +
                                                        (parts[1].toLongOrNull() ?: 0) * 60000 +
                                                        (parts[2].toLongOrNull() ?: 0) * 1000
                                                else -> 0L
                                            }
                                            break@outer
                                        }
                                    }
                                }
                            }

                            songs.add(
                                Song(
                                    id = videoId,
                                    title = songTitle,
                                    artist = albumArtist,
                                    thumbnail = ytThumbnail(videoId),
                                    duration = durationMs,
                                    albumId = browseId,
                                    isExplicit = parseExplicit(item.optJSONArray("badges"))
                                )
                            )
                        } catch (_: Exception) {}
                    }
                }

                // FALLBACK: extract OLAK5uy_ and use NewPipe
                if (songs.isEmpty()) {
                    val responseText = response.toString()
                    val playlistId = if (browseId.startsWith("OLAK5uy_")) browseId
                    else Regex("\"playlistId\":\"(OLAK5uy_[^\"]+)\"")
                        .find(responseText)?.groupValues?.get(1)

                    if (playlistId != null) {
                        initNewPipe()
                        for (url in listOf(
                            "https://music.youtube.com/playlist?list=$playlistId",
                            "https://www.youtube.com/playlist?list=$playlistId"
                        )) {
                            if (songs.isNotEmpty()) break
                            try {
                                val extractor =
                                    ServiceList.YouTube.getPlaylistExtractor(url)
                                extractor.fetchPage()
                                if (albumArtist == "Unknown Artist")
                                    albumArtist =
                                        extractor.uploaderName ?: albumArtist
                                var page = extractor.initialPage
                                while (true) {
                                    for (item in page.items) {
                                        try {
                                            if (item is org.schabi.newpipe.extractor
                                                    .stream.StreamInfoItem
                                            ) {
                                                val id = extractIdFromUrl(item.url)
                                                val title = item.name ?: continue
                                                songs.add(Song(
                                                    id = id,
                                                    title = title,
                                                    artist = albumArtist,
                                                    thumbnail = ytThumbnail(id),
                                                    duration = item.duration * 1000,
                                                    albumId = browseId,
                                                    isExplicit = title.lowercase()
                                                        .contains("explicit")
                                                ))
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    if (page.hasNextPage())
                                        page = extractor.getPage(page.nextPage)
                                    else break
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                album = Album(
                    id = browseId,
                    title = albumTitle,
                    artist = albumArtist,
                    thumbnail = albumThumb,
                    songCount = songs.size,
                    youtubeUrl = "https://music.youtube.com/browse/$browseId"
                )

            } catch (_: Exception) {}

            Pair(album, songs)
        }

    suspend fun getVideoMetadata(videoId: String): Song? = withContext(Dispatchers.IO) {
        initNewPipe()
        try {
            val extractor = ServiceList.YouTube
                .getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
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
                val extractor = ServiceList.YouTube
                    .getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                extractor.fetchPage()
                val streams: List<AudioStream> = extractor.audioStreams
                streams.filter { it.content != null && it.content.isNotEmpty() }
                    .maxByOrNull { it.averageBitrate }?.content
            } catch (e: Exception) {
                Log.e("Innertube", "Stream error $videoId: ${e.message}")
                null
            }
        }

    suspend fun getRelatedSongs(context: Context, videoId: String, limit: Int = 10): List<Song> =
        withContext(Dispatchers.IO) {
            initNewPipe()
            val results = mutableListOf<Song>()
            try {
                val extractor = ServiceList.YouTube
                    .getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
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

    override fun execute(
        request: org.schabi.newpipe.extractor.downloader.Request
    ): Response {
        val rb = Request.Builder()
            .url(request.url())
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
            )
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader(
                "Cookie",
                "CONSENT=YES+; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg"
            )

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
        return Response(
            response.code,
            response.message,
            headers,
            responseBody,
            request.url()
        )
    }
}
