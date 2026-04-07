package com.yt.lite.lyrics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class LyricsResult(
    val syncedLines: List<LyricLine>,
    val plainText: String,
    val hasSynced: Boolean
)

object LyricsRepository {

    private const val BASE_URL = "https://lrclib.net/api"

    suspend fun getLyrics(title: String, artist: String, duration: Long): LyricsResult? {
        return withContext(Dispatchers.IO) {
            try {
                // Try synced lyrics first
                val synced = fetchSynced(title, artist, duration)
                if (synced != null) return@withContext synced

                // Fall back to plain lyrics
                val plain = fetchPlain(title, artist)
                if (plain != null) return@withContext plain

                null
            } catch (e: Exception) {
                Log.e("LyricsRepository", "Error fetching lyrics: ${e.message}")
                null
            }
        }
    }

    private fun fetchSynced(title: String, artist: String, duration: Long): LyricsResult? {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val durationSecs = duration / 1000
            val url = "$BASE_URL/get?track_name=$encodedTitle&artist_name=$encodedArtist&duration=$durationSecs"

            val response = URL(url).readText()
            val json = JSONObject(response)

            val syncedLyrics = json.optString("syncedLyrics", "")
            if (syncedLyrics.isNotBlank()) {
                val lines = parseLrc(syncedLyrics)
                return LyricsResult(
                    syncedLines = lines,
                    plainText = lines.joinToString("\n") { it.text },
                    hasSynced = true
                )
            }

            val plainLyrics = json.optString("plainLyrics", "")
            if (plainLyrics.isNotBlank()) {
                return LyricsResult(
                    syncedLines = emptyList(),
                    plainText = plainLyrics,
                    hasSynced = false
                )
            }

            null
        } catch (e: Exception) {
            Log.e("LyricsRepository", "Synced fetch failed: ${e.message}")
            null
        }
    }

    private fun fetchPlain(title: String, artist: String): LyricsResult? {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val url = "$BASE_URL/search?q=$encodedTitle+$encodedArtist"

            val response = URL(url).readText()
            val arr = JSONArray(response)
            if (arr.length() == 0) return null

            val json = arr.getJSONObject(0)
            val plainLyrics = json.optString("plainLyrics", "")
            if (plainLyrics.isBlank()) return null

            LyricsResult(
                syncedLines = emptyList(),
                plainText = plainLyrics,
                hasSynced = false
            )
        } catch (e: Exception) {
            Log.e("LyricsRepository", "Plain fetch failed: ${e.message}")
            null
        }
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")

        lrc.lines().forEach { line ->
            val match = regex.find(line) ?: return@forEach
            val (min, sec, ms, text) = match.destructured
            val timeMs = min.toLong() * 60000 +
                    sec.toLong() * 1000 +
                    (if (ms.length == 2) ms.toLong() * 10 else ms.toLong())
            lines.add(LyricLine(timeMs, text.trim()))
        }

        return lines.sortedBy { it.timeMs }
    }
}
