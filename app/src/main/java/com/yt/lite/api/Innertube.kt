package com.yt.lite.api

import android.content.Context
import com.yt.lite.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamExtractor
import java.util.concurrent.TimeUnit

object Innertube {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val parser = Json { ignoreUnknownKeys = true }
    private val MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"

    private var newPipeInit = false

    private fun ensureNewPipe(ctx: Context) {
        if (!newPipeInit) {
            NewPipe.init(object : org.schabi.newpipe.extractor.downloader.Downloader() {
                override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
                    val rb = okhttp3.Request.Builder().url(request.url())
                    request.headers().forEach { (k, v) -> v.forEach { rb.header(k, it) } }
                    val body = request.dataToSend()?.let {
                        it.toRequestBody("application/x-www-form-urlencoded".toMediaType())
                    }
                    rb.method(request.httpMethod(), body)
                    val resp = http.newCall(rb.build()).execute()
                    val respHeaders = mutableMapOf<String, MutableList<String>>()
                    resp.headers.forEach { (k, v) ->
                        respHeaders.getOrPut(k) { mutableListOf() }.add(v)
                    }
                    return org.schabi.newpipe.extractor.downloader.Response(
                        resp.code,
                        resp.message,
                        respHeaders,
                        resp.body?.string(),
                        resp.request.url.toString()
                    )
                }
            })
            newPipeInit = true
        }
    }

    private suspend fun post(
        endpoint: String,
        body: JsonObject
    ): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$MUSIC_BASE/$endpoint?prettyPrint=false")
                .post(body.toString().toRequestBody(MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0")
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
        val resp = post("search", body) ?: return emptyList()
        return parseSearch(resp)
    }

    suspend fun getStreamUrl(ctx: Context, videoId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                ensureNewPipe(ctx)
                val url = "https://www.youtube.com/watch?v=$videoId"
                val extractor: StreamExtractor =
                    ServiceList.YouTube.getStreamExtractor(url)
                extractor.fetchPage()

                // Get audio-only streams, prefer m4a
                val audioStreams = extractor.audioStreams
                if (audioStreams.isNullOrEmpty()) return@withContext null

                val best = audioStreams
                    .filter { it.content?.isNotEmpty() == true }
                    .maxByOrNull { it.bitrate }

                best?.content
            } catch (e: Exception) {
                android.util.Log.e("YTLite", "NewPipe extraction failed", e)
                null
            }
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
