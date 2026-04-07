package com.yt.lite.lyrics

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object LyricsRepository {

    private const val PREFS_NAME = "ytlite_lyrics"
    private const val CACHE_PREFIX = "lyrics_"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun getPrefs(): SharedPreferences? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext null
        val cacheKey = "${CACHE_PREFIX}${title.lowercase()}_${artist.lowercase()}".replace(" ", "_")

        // 1. Try cache
        getCachedLyrics(cacheKey)?.let { return@withContext it }

        // 2. Fetch from LRCLIB
        val fetched = fetchLyricsFromLrclib(title, artist, duration)
        if (fetched != null) {
            cacheLyrics(cacheKey, fetched)
        }
        return@withContext fetched
    }

    private fun getCachedLyrics(key: String): LyricsResult? {
        val prefs = getPrefs() ?: return null
        val json = prefs.getString(key, null) ?: return null
        return try {
            val obj = JSONObject(json)
            val syncedLines = obj.optJSONArray("synced")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val line = arr.getJSONObject(i)
                    LyricsLine(
                        text = line.getString("text"),
                        timeMs = line.getLong("timeMs")
                    )
                }
            } ?: emptyList()
            val plainText = obj.optString("plain", "")
            LyricsResult(syncedLines, plainText)
        } catch (_: Exception) { null }
    }

    private fun cacheLyrics(key: String, result: LyricsResult) {
        val prefs = getPrefs() ?: return
        val obj = JSONObject().apply {
            put("plain", result.plainText)
            put("synced", org.json.JSONArray().apply {
                result.syncedLines.forEach { line ->
                    put(JSONObject().apply {
                        put("text", line.text)
                        put("timeMs", line.timeMs)
                    })
                }
            })
        }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private suspend fun fetchLyricsFromLrclib(
        title: String,
        artist: String,
        duration: Long
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val queryTitle = title.lowercase().replace(" ", "%20")
        val queryArtist = artist.lowercase().replace(" ", "%20")
        val url = "https://lrclib.net/api/get?track_name=$queryTitle&artist_name=$queryArtist&duration=${duration / 1000}"
        return@withContext try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val responseCode = conn.responseCode
            if (responseCode != 200) return@withContext null
            val text = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(text)
            val syncedLyrics = json.optString("syncedLyrics", "")
            val plainLyrics = json.optString("plainLyrics", "")

            val lines = if (syncedLyrics.isNotBlank()) {
                parseLrc(syncedLyrics)
            } else emptyList()

            if (lines.isEmpty() && plainLyrics.isBlank()) return@withContext null
            LyricsResult(lines, plainLyrics)
        } catch (_: Exception) { null }
    }

    private fun parseLrc(lrc: String): List<LyricsLine> {
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2})\\](.*)")
        return lrc.lines().mapNotNull { line ->
            regex.matchEntire(line)?.let { match ->
                val minutes = match.groupValues[1].toInt()
                val seconds = match.groupValues[2].toInt()
                val millis = match.groupValues[3].toInt()
                val timeMs = (minutes * 60 + seconds) * 1000L + millis * 10L
                val text = match.groupValues[4].trim()
                if (text.isNotBlank()) LyricsLine(text, timeMs) else null
            }
        }.sortedBy { it.timeMs }
    }
}
