package com.yt.lite.lyrics

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object LyricsRepository {
    private const val TAG = "LyricsRepo"
    private const val PREFS_NAME = "ytlite_lyrics"
    private const val CACHE_PREFIX = "lyrics_"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        Log.d(TAG, "Initialized")
    }

    private fun getPrefs(): SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long
    ): LyricsResult? = withContext(Dispatchers.IO) {
        Log.d(TAG, "getLyrics called: title=$title, artist=$artist, duration=$duration")
        
        val cacheKey = "${CACHE_PREFIX}${title.lowercase()}_${artist.lowercase()}".replace(" ", "_")
        
        // Check cache
        getCachedLyrics(cacheKey)?.let {
            Log.d(TAG, "Returning cached lyrics")
            return@withContext it
        }
        
        // Fetch from LRCLIB
        val fetched = fetchFromLrclib(title, artist, duration)
        if (fetched != null) {
            cacheLyrics(cacheKey, fetched)
            Log.d(TAG, "Fetched and cached new lyrics")
            return@withContext fetched
        }
        
        Log.d(TAG, "No lyrics found")
        return@withContext null
    }

    private fun getCachedLyrics(key: String): LyricsResult? {
        val json = getPrefs().getString(key, null) ?: return null
        return try {
            val obj = JSONObject(json)
            val syncedLines = obj.optJSONArray("synced")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val line = arr.getJSONObject(i)
                    LyricsLine(line.getString("text"), line.getLong("timeMs"))
                }
            } ?: emptyList()
            val plainText = obj.optString("plain", "")
            LyricsResult(syncedLines, plainText)
        } catch (e: Exception) {
            Log.e(TAG, "Cache parse error", e)
            null
        }
    }

    private fun cacheLyrics(key: String, result: LyricsResult) {
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
        getPrefs().edit().putString(key, obj.toString()).apply()
    }

    private suspend fun fetchFromLrclib(
        title: String,
        artist: String,
        duration: Long
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedArtist = URLEncoder.encode(artist, "UTF-8")
        val durationSec = duration / 1000
        val url = "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist&duration=$durationSec"
        
        Log.d(TAG, "Request URL: $url")
        
        return@withContext try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "YTLite/1.0")
            
            val responseCode = conn.responseCode
            Log.d(TAG, "Response code: $responseCode")
            
            if (responseCode != 200) {
                Log.e(TAG, "HTTP error: $responseCode")
                return@withContext null
            }
            
            val text = conn.inputStream.bufferedReader().readText()
            Log.d(TAG, "Response length: ${text.length}")
            
            val json = JSONObject(text)
            val syncedLyrics = json.optString("syncedLyrics", "")
            val plainLyrics = json.optString("plainLyrics", "")
            
            Log.d(TAG, "syncedLyrics length: ${syncedLyrics.length}, plainLyrics length: ${plainLyrics.length}")
            
            val lines = if (syncedLyrics.isNotBlank()) {
                parseLrc(syncedLyrics)
            } else emptyList()
            
            if (lines.isEmpty() && plainLyrics.isBlank()) {
                Log.d(TAG, "No lyrics content")
                return@withContext null
            }
            
            LyricsResult(lines, plainLyrics)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch exception", e)
            null
        }
    }

    private fun parseLrc(lrc: String): List<LyricsLine> {
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2})\\](.*)")
        return lrc.lines().mapNotNull { line ->
            regex.matchEntire(line)?.let { match ->
                val minutes = match.groupValues[1].toInt()
                val seconds = match.groupValues[2].toInt()
                val millis = match.groupValues[3].toInt()
                val timeMs = (minutes * 60L + seconds) * 1000L + millis * 10L
                val text = match.groupValues[4].trim()
                if (text.isNotBlank()) LyricsLine(text, timeMs) else null
            }
        }.sortedBy { it.timeMs }
    }
}
