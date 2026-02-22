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
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import java.util.concurrent.TimeUnit

object Innertube {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val parser = Json { ignoreUnknownKeys = true }
    private val MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"

    private var newPipeInit = false

    private fun ensureNewPipe() {
        if (newPipeInit) return
        NewPipe.init(object : Downloader() {
            override fun execute(request: NpRequest): NpResponse {
                val methodBody = request.dataToSend()?.toRequestBody()
                val rb = Request.Builder()
                    .url(request.url())
                    .method(request.httpMethod(), methodBody)

                // Add all headers from NewPipe
                request.headers().forEach { (key, values) ->
                    values.forEach { rb.addHeader(key, it) }
                }

                // Override with a real browser UA
                rb.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                // Critical: YouTube GDPR consent cookie
                // Without this YouTube returns "page needs to be reloaded"
                rb.header("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+119; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
                rb.header("Accept-Language", "en-US,en;q=0.9")
                rb.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

                val resp = http.newCall(rb.build()).execute()
                val headers = mutableMapOf<String, MutableList<String>>()
                resp.headers.forEach { (k, v) ->
                    headers.getOrPut(k) { mutableListOf() }.add(v)
                }
                return NpResponse(
                    resp.code,
                    resp.message,
                    headers,
                    resp.body?.string() ?: "",
                    resp.request.url.toString()
                )
            }
        })
        newPipeInit = true
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
                ensureNewPipe()
                val url = "https://www.youtube.com/watch?v=$videoId"
                val extractor = ServiceList.YouTube.getStreamExtractor(url)
                extractor.fetchPage()

                val audioStreams = extractor.audioStreams
                android.util.Log.d("YTLite", "Audio streams: ${audioStreams?.size}")

                if (audioStreams.isNullOrEmpty()) {
                    throw Exception("No audio streams found")
                }

                val best = audioStreams
                    .filter { !it.content.isNullOrEmpty() }
                    .maxByOrNull { it.bitrate }

                android.util.Log.d("YTLite", "Best stream URL: ${best?.content?.take(80)}")
                best?.content

            } catch (e: Exception) {
                android.util.Log.e("YTLite", "NewPipe error: ${e.javaClass.simpleName}: ${e.message}", e)
                throw Exception("${e.javaClass.simpleName}: ${e.message}")
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
