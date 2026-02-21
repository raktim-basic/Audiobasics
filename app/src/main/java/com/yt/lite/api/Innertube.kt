package com.yt.lite.api

import com.yt.lite.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object Innertube {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val parser = Json { ignoreUnknownKeys = true }
    private val MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private const val BASE = "https://music.youtube.com/youtubei/v1"
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-KOWNER5s0"

    private fun webContext() = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20240101.01.00")
                put("hl", "en")
                put("gl", "US")
            }
        }
    }

    private fun androidContext() = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "ANDROID_MUSIC")
                put("clientVersion", "5.28.1")
                put("androidSdkVersion", 30)
                put("hl", "en")
                put("gl", "US")
            }
        }
    }

    private suspend fun post(
        endpoint: String,
        body: JsonObject,
        userAgent: String = "Mozilla/5.0"
    ): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE/$endpoint?key=$KEY&prettyPrint=false")
                .post(body.toString().toRequestBody(MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent)
                .header("X-Goog-Api-Format-Version", "1")
                .build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            parser.parseToJsonElement(resp.body!!.string()).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    suspend fun search(query: String): List<Song> {
        val body = buildJsonObject {
            webContext().forEach { (k, v) -> put(k, v) }
            put("query", query)
            put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA==")
        }
        val resp = post("search", body) ?: return emptyList()
        return parseSearch(resp)
    }

    suspend fun getHomeSongs(): List<Song> {
        val body = buildJsonObject {
            webContext().forEach { (k, v) -> put(k, v) }
            put("browseId", "FEmusic_home")
        }
        val resp = post("browse", body) ?: return search("top songs 2024")
        return parseHome(resp).ifEmpty { search("top songs 2024") }
    }

    suspend fun getStreamUrl(videoId: String): String? {
        val body = buildJsonObject {
            androidContext().forEach { (k, v) -> put(k, v) }
            put("videoId", videoId)
        }
        val ua = "com.google.android.apps.youtube.music/5.28.1 (Linux; U; Android 10; en_US) gzip"
        val resp = post("player", body, ua) ?: return null

        val formats = resp["streamingData"]?.jsonObject
            ?.get("adaptiveFormats")?.jsonArray ?: return null

        val audio = formats
            .map { it.jsonObject }
            .filter { it["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/") == true }

        return (audio.firstOrNull {
            it["mimeType"]?.jsonPrimitive?.content?.contains("mp4") == true
        } ?: audio.maxByOrNull {
            it["bitrate"]?.jsonPrimitive?.intOrNull ?: 0
        })?.get("url")?.jsonPrimitive?.content
    }

    private fun parseSearch(data: JsonObject): List<Song> {
        val songs = mutableListOf<Song>()
        try {
            val sections = data["contents"]?.jsonObject
                ?.get("tabbedSearchResultsRenderer")?.jsonObject
                ?.get("tabs")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("tabRenderer")?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("sectionListRenderer")?.jsonObject
                ?.get("contents")?.jsonArray ?: return songs
            for (section in sections) {
                val items = section.jsonObject["musicShelfRenderer"]?.jsonObject
                    ?.get("contents")?.jsonArray ?: continue
                for (item in items) {
                    parseSong(item.jsonObject)?.let { songs.add(it) }
                }
            }
        } catch (_: Exception) {}
        return songs
    }

    private fun parseHome(data: JsonObject): List<Song> {
        val songs = mutableListOf<Song>()
        try {
            val sections = data["contents"]?.jsonObject
                ?.get("singleColumnBrowseResultsRenderer")?.jsonObject
                ?.get("tabs")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("tabRenderer")?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("sectionListRenderer")?.jsonObject
                ?.get("contents")?.jsonArray ?: return songs
            for (section in sections) {
                val items = section.jsonObject["musicCarouselShelfRenderer"]?.jsonObject
                    ?.get("contents")?.jsonArray ?: continue
                for (item in items.take(5)) {
                    val r = item.jsonObject["musicTwoRowItemRenderer"]?.jsonObject ?: continue
                    val id = r["navigationEndpoint"]?.jsonObject
                        ?.get("watchEndpoint")?.jsonObject
                        ?.get("videoId")?.jsonPrimitive?.content ?: continue
                    val title = r["title"]?.jsonObject?.get("runs")?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: continue
                    val artist = r["subtitle"]?.jsonObject?.get("runs")?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                    val thumb = r["thumbnailRenderer"]?.jsonObject
                        ?.get("musicThumbnailRenderer")?.jsonObject
                        ?.get("thumbnail")?.jsonObject
                        ?.get("thumbnails")?.jsonArray?.lastOrNull()?.jsonObject
                        ?.get("url")?.jsonPrimitive?.content ?: ""
                    songs.add(Song(id, title, artist, thumb))
                }
                if (songs.size >= 20) break
            }
        } catch (_: Exception) {}
        return songs
    }

    private fun parseSong(item: JsonObject): Song? {
        return try {
            val r = item["musicResponsiveListItemRenderer"]?.jsonObject ?: return null
            val id = r["playlistItemData"]?.jsonObject?.get("videoId")?.jsonPrimitive?.content
                ?: r["overlay"]?.jsonObject
                    ?.get("musicItemThumbnailOverlayRenderer")?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("musicPlayButtonRenderer")?.jsonObject
                    ?.get("playNavigationEndpoint")?.jsonObject
                    ?.get("watchEndpoint")?.jsonObject
                    ?.get("videoId")?.jsonPrimitive?.content ?: return null
            val cols = r["flexColumns"]?.jsonArray ?: return null
            val title = cols.getOrNull(0)?.jsonObject
                ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                ?.get("text")?.jsonObject?.get("runs")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: return null
            val artist = cols.getOrNull(1)?.jsonObject
                ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
                ?.get("text")?.jsonObject?.get("runs")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
            val thumb = r["thumbnail"]?.jsonObject
                ?.get("musicThumbnailRenderer")?.jsonObject
                ?.get("thumbnail")?.jsonObject
                ?.get("thumbnails")?.jsonArray?.lastOrNull()?.jsonObject
                ?.get("url")?.jsonPrimitive?.content ?: ""
            Song(id, title, artist, thumb)
        } catch (_: Exception) { null }
    }
}
