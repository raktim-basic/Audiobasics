package com.yt.lite.api

import android.content.Context
import android.net.Uri
import android.util.Log
import timber.log.Timber
import com.yt.lite.api.potoken.PoTokenGenerator
import com.yt.lite.api.cipher.CipherDeobfuscator
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
import java.net.URLDecoder
import java.util.UUID
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
    private const val YTM_CLIENT_VERSION = "1.20260520.01.00"

    private const val CHROME_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

    private fun ytmContext(): JSONObject = JSONObject().put(
        "client", JSONObject()
            .put("clientName", YTM_CLIENT_NAME)
            .put("clientVersion", YTM_CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", "US")
    )

    private fun ytmPost(endpoint: String, body: JSONObject): JSONObject? {
        return try {
            body.put("context", ytmContext())
            val request = Request.Builder()
                .url("$YTM_BASE/$endpoint?key=$YTM_KEY")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", CHROME_USER_AGENT)
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

    private fun ytThumbnail(videoId: String) =
        "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"

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

    private fun parseDurationString(t: String): Long {
        val p = t.trim().split(":")
        return when (p.size) {
            2 -> (p[0].toLongOrNull() ?: 0) * 60000 +
                    (p[1].toLongOrNull() ?: 0) * 1000
            3 -> (p[0].toLongOrNull() ?: 0) * 3600000 +
                    (p[1].toLongOrNull() ?: 0) * 60000 +
                    (p[2].toLongOrNull() ?: 0) * 1000
            else -> 0L
        }
    }

    private fun parseDurationMs(item: JSONObject): Long {
        val fixedCols = item.optJSONArray("fixedColumns")
        if (fixedCols != null) {
            for (fc in 0 until fixedCols.length()) {
                val runs = fixedCols.optJSONObject(fc)
                    ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs") ?: continue
                for (r in 0 until runs.length()) {
                    val t = runs.optJSONObject(r)?.optString("text", "") ?: ""
                    if (t.matches(Regex("\\d+:\\d{2}"))) return parseDurationString(t)
                }
            }
        }
        val flexCols = item.optJSONArray("flexColumns") ?: return 0L
        for (fc in 0 until flexCols.length()) {
            val runs = flexCols.optJSONObject(fc)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs") ?: continue
            for (r in 0 until runs.length()) {
                val t = runs.optJSONObject(r)?.optString("text", "") ?: ""
                if (t.matches(Regex("\\d+:\\d{2}"))) return parseDurationString(t)
            }
        }
        return 0L
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
                                val t = runs.optJSONObject(r)?.optString("text", "") ?: ""
                                if (t == " • " || t == "•" || t.isBlank()) continue
                                if (r == runs.length() - 1 && t.contains(":")) {
                                    durationMs = parseDurationString(t)
                                } else if (artist.isEmpty()) {
                                    artist = t
                                }
                            }
                        }

                        out.add(Song(
                            id = videoId,
                            title = title,
                            artist = artist,
                            thumbnail = ytThumbnail(videoId),
                            duration = durationMs,
                            isExplicit = parseExplicit(item.optJSONArray("badges"))
                        ))
                    } catch (_: Exception) {}
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
                                val t = runs.optJSONObject(r)?.optString("text", "") ?: ""
                                if (t == " • " || t == "•" || t.isBlank()) continue
                                nonSepCount++
                                if (nonSepCount == 1) continue
                                if (nonSepCount == 2 && !t.all { c -> c.isDigit() }) {
                                    artist = t; break
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
                                .optString("url", "") else ""

                        out.add(Song(
                            id = browseId,
                            title = title,
                            artist = "(Album) ${artist.ifBlank { "Unknown Artist" }}",
                            thumbnail = thumb,
                            isAlbum = true
                        ))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
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

    suspend fun getAlbumSongs(
        browseId: String,
        fallbackArtist: String = ""
    ): Pair<Album?, List<Song>> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        try {
            val step1 = ytmPost("browse", JSONObject().put("browseId", browseId))
                ?: return@withContext Pair(null, emptyList())

            var playlistId: String? = step1
                .optJSONObject("microformat")
                ?.optJSONObject("microformatDataRenderer")
                ?.optString("urlCanonical")
                ?.substringAfterLast("=")
                ?.takeIf { it.isNotBlank() }

            if (playlistId == null && browseId.startsWith("OLAK5uy_")) {
                playlistId = browseId
            }

            if (playlistId == null) {
                playlistId = Regex("\"playlistId\":\"(OLAK5uy_[^\"]+)\"")
                    .find(step1.toString())?.groupValues?.get(1)
            }

            if (playlistId != null) {
                val step2 = ytmPost(
                    "browse",
                    JSONObject().put("browseId", "VL$playlistId")
                )

                if (step2 != null) {
                    val shelfContents = step2
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
                                    .optJSONObject("musicResponsiveListItemRenderer")
                                    ?: continue

                                val videoId = item
                                    .optJSONObject("playlistItemData")
                                    ?.optString("videoId")
                                    ?.takeIf { it.isNotBlank() }
                                    ?: item.optJSONObject("overlay")
                                        ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                        ?.optJSONObject("content")
                                        ?.optJSONObject("musicPlayButtonRenderer")
                                        ?.optJSONObject("playNavigationEndpoint")
                                        ?.optJSONObject("watchEndpoint")
                                        ?.optString("videoId")
                                    ?: continue

                                val flexCols = item.optJSONArray("flexColumns")
                                    ?: continue
                                val col0 = flexCols.optJSONObject(0)
                                    ?.optJSONObject(
                                        "musicResponsiveListItemFlexColumnRenderer"
                                    )
                                val songTitle = extractText(col0, "text")
                                if (songTitle.isBlank()) continue

                                songs.add(Song(
                                    id = videoId,
                                    title = songTitle,
                                    artist = fallbackArtist.ifBlank { "Unknown Artist" },
                                    thumbnail = ytThumbnail(videoId),
                                    duration = parseDurationMs(item),
                                    albumId = browseId,
                                    isExplicit = parseExplicit(
                                        item.optJSONArray("badges")
                                    )
                                ))
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        Pair(null, songs)
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

    // Clients to try in order. WEB_REMIX is the main one YouTube Music uses.
    // Fallbacks handle age-restricted and regional restrictions.
    private data class YTClient(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String = CHROME_USER_AGENT,
        val usePoTokenInBody: Boolean = false,  // Add poToken to request body (web clients)
        val appendPotToUrl: Boolean = false     // Append pot= to stream URL (mobile clients)
    )

    private val STREAM_CLIENTS = listOf(
        // IOS — returns direct URLs, needs pot= appended to stream URL
        YTClient("IOS", "21.03.1",
            userAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)",
            usePoTokenInBody = false,
            appendPotToUrl = true),
        // ANDROID — most reliable, also needs pot= on URL
        YTClient("ANDROID", "19.44.38",
            userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
            usePoTokenInBody = false,
            appendPotToUrl = true),
        // Web clients — poToken in body + pot= on URL
        YTClient("WEB_REMIX", "1.20260213.01.00",
            usePoTokenInBody = true, appendPotToUrl = true),
        YTClient("WEB", "2.20260213.00.00",
            usePoTokenInBody = true, appendPotToUrl = true),
    )

    private val poTokenGenerator = PoTokenGenerator()

    private suspend fun getInnerTubeStreamFast(
        context: Context,
        videoId: String,
        poTokenResult: com.yt.lite.api.potoken.PoTokenResult? = null
    ): String? {
        for (client in STREAM_CLIENTS) {
            try {
                Timber.d("Trying client: ${client.clientName} | poToken=${poTokenResult?.playerRequestPoToken?.take(10)}...")
                val url = tryClientForStream(videoId, client, poTokenResult?.playerRequestPoToken, poTokenResult?.streamingDataPoToken)
                if (!url.isNullOrEmpty()) {
                    Timber.d("Stream resolved via ${client.clientName} ✅")
                    return url
                }
            } catch (e: Exception) {
                Log.w("Innertube", "Client ${client.clientName} failed: ${e.message}")
            }
        }

        Timber.e("All native clients FAILED for videoId=$videoId ❌")
        return null
    }

    private suspend fun tryClientForStream(
        videoId: String,
        client: YTClient,
        playerRequestPoToken: String?,
        streamingDataPoToken: String?
    ): String? {
        val clientContext = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", client.clientVersion)
            .put("hl", "en")
            .put("gl", "US")

        val body = JSONObject()
            .put("videoId", videoId)
            .put("context", JSONObject().put("client", clientContext))

        // Only add poToken to request body for web clients
        if (client.usePoTokenInBody && playerRequestPoToken != null) {
            body.put("serviceIntegrityDimensions",
                JSONObject().put("poToken", playerRequestPoToken))
        }

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=$YTM_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", client.userAgent)
            .addHeader("X-Youtube-Client-Name", client.clientName)
            .addHeader("X-Youtube-Client-Version", client.clientVersion)
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        val response = ytMusicClient.newCall(request).execute()
        val text = response.body?.string() ?: return null
        val json = JSONObject(text)

        val status = json.optJSONObject("playabilityStatus")?.optString("status")
        if (status != "OK") {
            Timber.w("${client.clientName} rejected: playabilityStatus=$status")
            return null
        }

        val formats = json.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats") ?: return null

        // Pick best audio format by bitrate, skip alr=yes for mobile clients
        var bestUrl: String? = null
        var bestBitrate = -1

        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            if (!f.optString("mimeType").startsWith("audio/")) continue

            var url = f.optString("url", "")

            // Mobile clients: skip alr=yes adaptive loading URLs — use standard formats
            if (!client.usePoTokenInBody && url.contains("alr=yes")) continue

            if (url.isEmpty()) {
                val signatureCipher = f.optString("signatureCipher", "")
                    .ifEmpty { f.optString("cipher", "") }
                if (signatureCipher.isNotEmpty()) {
                    url = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId) ?: ""
                }
            }

            if (url.isEmpty()) continue

            val bitrate = f.optInt("bitrate", 0)
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = url
            }
        }

        if (bestUrl == null) return null

        // Web clients: apply n-transform
        if (client.usePoTokenInBody) {
            bestUrl = CipherDeobfuscator.transformNParamInUrl(bestUrl!!)
        }

        // Append pot= to stream URL for all clients that support it
        if (client.appendPotToUrl && streamingDataPoToken != null) {
            val separator = if ("?" in bestUrl!!) "&" else "?"
            bestUrl = "${bestUrl}${separator}pot=${android.net.Uri.encode(streamingDataPoToken)}"
            Timber.d("${client.clientName}: appended pot= to stream URL ✅")
        }

        return bestUrl
    }

    private fun parseCipher(cipher: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        cipher.split("&").forEach { param ->
            val parts = param.split("=")
            if (parts.size == 2) {
                map[parts[0]] = URLDecoder.decode(parts[1], "UTF-8")
            }
        }
        return map
    }

    suspend fun getStreamUrl(context: Context, videoId: String, forceFallback: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            // Generate poToken once at this level — shared by native engine AND NewPipe fallback
            val sharedPoToken = try {
                val sessionId = UUID.randomUUID().toString()
                withContext(Dispatchers.IO) {
                    poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                }
            } catch (e: Exception) {
                Log.w("Innertube", "PoToken generation failed: ${e.message}")
                null
            }

            // Pre-inject into NewPipe downloader so it's ready if we fall through
            sharedPoToken?.let {
                NewPipeDownloader.poToken = it.playerRequestPoToken
                Log.d("Innertube", "PoToken ready for both native and NewPipe")
            }

            if (!forceFallback) {
                for (i in 1..2) {
                    val fastUrl = getInnerTubeStreamFast(context, videoId, sharedPoToken)
                    if (!fastUrl.isNullOrEmpty()) {
                        Log.d("Innertube", "Stream resolved via InnerTube natively (Try $i)")
                        return@withContext fastUrl
                    }
                }
            }

            Timber.w("Falling to NewPipe (forceFallback=$forceFallback)")
            initNewPipe()

            try {
                val extractor = ServiceList.YouTube
                    .getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                extractor.fetchPage()
                val streams: List<AudioStream> = extractor.audioStreams
                streams.filter { it.content != null && it.content.isNotEmpty() }
                    .maxByOrNull { it.averageBitrate }?.content
            } catch (e: Exception) {
                Timber.e("NewPipe ALSO failed: ${e.message} ❌")
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

    // Firefox 140 ESR - required to pass YouTube integrity checks post-May 2026
    private const val FIREFOX_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    // Visitor data and poToken injected from outside before NewPipe is used
    @Volatile var visitorData: String? = null
    @Volatile var poToken: String? = null

    override fun execute(
        request: org.schabi.newpipe.extractor.downloader.Request
    ): Response {
        val rb = Request.Builder().url(request.url())

        // Firefox 140 UA - passes YouTube bot checks
        rb.header("User-Agent", FIREFOX_USER_AGENT)

        val requestedHeaders = request.headers() ?: emptyMap()
        requestedHeaders.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true)) {
                v.forEach { rb.addHeader(k, it) }
            }
        }

        if (!requestedHeaders.containsKey("Accept-Language")) {
            rb.addHeader("Accept-Language", "en-US,en;q=0.9")
        }
        if (!requestedHeaders.containsKey("Cookie")) {
            rb.addHeader("Cookie", "CONSENT=YES+; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
        }

        // Inject visitor data if available - makes YouTube treat us as returning user
        visitorData?.let { rb.addHeader("X-Visitor-Data", it) }

        // Inject poToken for integrity checks on player requests
        if (request.url().contains("/youtubei/v1/player") ||
            request.url().contains("/youtubei/v1/next")) {
            poToken?.let { rb.addHeader("X-Goog-Visitor-Id", it) }
        }

        when (request.httpMethod()) {
            "POST" -> {
                val body = request.dataToSend() ?: ByteArray(0)
                rb.post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            }
            else -> rb.get()
        }

        val response = client.newCall(rb.build()).execute()

        // Extract and persist visitor data from response for future requests
        response.header("X-Visitor-Data")?.let { visitorData = it }

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
