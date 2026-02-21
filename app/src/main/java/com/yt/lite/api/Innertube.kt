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

    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
    private const val YT_BASE = "https://www.youtube.com/youtubei/v1"

    private suspend fun post(
        endpoint: String,
        body: JsonObject,
        userAgent: String = "Mozilla/5.0",
        base: String = YT_BASE
    ): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$base/$endpoint?prettyPrint=false")
                .post(body.toString().toRequestBody(MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent)
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
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20240101.01.00")
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("query", query)
            put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA==")
        }
        val resp = post("search", body, base = MUSIC_BASE) ?: return emptyList()
        return parseSearch(resp)
    }

    suspend fun getStreamUrl(videoId: String): String? {
        // TVHTML5_SIMPLY_EMBEDDED_PLAYER returns plain unsigned URLs — no cipher needed
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                    put("clientVersion", "2.0")
                    put("hl", "en")
                    put("gl", "US")
                }
                putJsonObject("thirdParty") {
                    put("embedUrl", "https://www.youtube.com/")
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val resp = post("player", body) ?: return null

        val status = resp["playabilityStatus"]?.jsonObject
            ?.get("status")?.jsonPrimitive?.content
        if (status != "OK") return null

        val adaptiveFormats = resp["streamingData"]?.jsonObject
            ?.get("adaptiveFormats")?.jsonArray

        val formats = resp["streamingData"]?.jsonObject
            ?.get("formats")?.jsonArray

        // Try adaptiveFormats first, then formats
        val allAudio = (adaptiveFormats ?: JsonArray(emptyList()))
            .map { it.jsonObject }
            .filter { it["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/") == true }
            .filter { it["url"] != null }

        if (allAudio.isNotEmpty()) {
            return (allAudio.firstOrNull {
                it["mimeType"]?.jsonPrimitive?.content?.contains("mp4") == true
            } ?: allAudio.maxByOrNull {
                it["bitrate"]?.jsonPrimitive?.intOrNull ?: 0
            })?.get("url")?.jsonPrimitive?.content
        }

        // Fallback: try regular formats (these have both audio+video but will still play)
        return (formats ?: JsonArray(emptyList()))
            .map { it.jsonObject }
            .filter { it["url"] != null }
            .maxByOrNull { it["bitrate"]?.jsonPrimitive?.intOrNull ?: 0 }
            ?.get("url")?.jsonPrimitive?.content
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
