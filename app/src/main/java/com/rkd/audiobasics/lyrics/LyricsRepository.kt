package com.rkd.audiobasics.lyrics

import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
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

/**
 * How a lyrics lookup ended. Telling these apart is the point: "Not found" means the lyrics
 * server answered and has nothing for this song; "Error" means we never got a proper answer
 * (offline, timed out, server trouble), so the lyrics may well exist.
 */
sealed interface LyricsOutcome {
    data class Found(val result: LyricsResult) : LyricsOutcome
    data object NotFound : LyricsOutcome
    data object Error : LyricsOutcome
}

object LyricsRepository {

    private const val BASE_URL = "https://lrclib.net/api"

    /** Connect and read timeout, each. A dead connection ends in an Error instead of hanging. */
    private const val TIMEOUT_MS = 6_000

    // What one request came back with.
    private sealed interface Http {
        class Ok(val body: String) : Http
        data object NotFound : Http                       // HTTP 404: the server has nothing
        class Failed(val unreachable: Boolean) : Http     // no answer (true) / other HTTP error (false)
    }

    // What one lookup step (an endpoint) concluded.
    private sealed interface Step {
        class Found(val result: LyricsResult) : Step
        data object NotFound : Step
        class Failed(val unreachable: Boolean) : Step
    }

    /**
     * Looks the song up: synced lyrics first (/get), then the fuzzy plain-text /search.
     * It is only "not found" when every lookup that ran got a real "nothing" answer; if one
     * failed, we can't be sure, so it is an error.
     */
    suspend fun fetchLyrics(title: String, artist: String, duration: Long): LyricsOutcome {
        return withContext(Dispatchers.IO) {
            try {
                Timber.tag("LyricsRepository").d("fetchLyrics: title='$title' artist='$artist' durationMs=$duration")

                val synced = fetchSynced(title, artist, duration)
                if (synced is Step.Found) {
                    Timber.tag("LyricsRepository").d("fetchLyrics: synced step found lyrics (hasSynced=${synced.result.hasSynced})")
                    return@withContext LyricsOutcome.Found(synced.result)
                }
                // The server couldn't be reached at all: a second request would only wait out
                // another timeout, so say so now.
                if (synced is Step.Failed && synced.unreachable) {
                    return@withContext LyricsOutcome.Error
                }

                val plain = fetchPlain(title, artist)
                if (plain is Step.Found) {
                    Timber.tag("LyricsRepository").d("fetchLyrics: /get had nothing, /search found plain lyrics")
                    return@withContext LyricsOutcome.Found(plain.result)
                }

                if (synced is Step.NotFound && plain is Step.NotFound) {
                    Timber.tag("LyricsRepository").d("fetchLyrics: not found (both endpoints answered with nothing)")
                    LyricsOutcome.NotFound
                } else {
                    Timber.tag("LyricsRepository").d("fetchLyrics: error (a lookup failed, so not-found can't be claimed)")
                    LyricsOutcome.Error
                }
            } catch (e: Exception) {
                Timber.tag("LyricsRepository").e("Error fetching lyrics: ${e.message}")
                LyricsOutcome.Error
            }
        }
    }

    /** The old entry point: just the lyrics, or null for both "not found" and "error". */
    suspend fun getLyrics(title: String, artist: String, duration: Long): LyricsResult? =
        (fetchLyrics(title, artist, duration) as? LyricsOutcome.Found)?.result

    private fun httpGet(url: String): Http {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    Http.Ok(conn.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND -> Http.NotFound
                else -> {
                    Timber.tag("LyricsRepository").e("GET failed with HTTP $code: $url")
                    Http.Failed(unreachable = false)
                }
            }
        } catch (e: Exception) {
            // Offline, DNS failure, connect/read timeout...
            Timber.tag("LyricsRepository").e("GET could not reach the server: ${e.message}")
            Http.Failed(unreachable = true)
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchSynced(title: String, artist: String, duration: Long): Step {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedArtist = URLEncoder.encode(artist, "UTF-8")
        val durationSecs = duration / 1000
        val url = "$BASE_URL/get?track_name=$encodedTitle&artist_name=$encodedArtist&duration=$durationSecs"
        Timber.tag("LyricsRepository").d("fetchSynced: GET $url")

        return when (val http = httpGet(url)) {
            is Http.Failed -> Step.Failed(http.unreachable)
            Http.NotFound -> Step.NotFound // no exact track_name/artist_name/duration match
            is Http.Ok -> try {
                val json = JSONObject(http.body)

                val syncedLyrics = json.optString("syncedLyrics", "")
                if (syncedLyrics.isNotBlank()) {
                    val lines = parseLrc(syncedLyrics)
                    return Step.Found(
                        LyricsResult(
                            syncedLines = lines,
                            plainText = lines.joinToString("\n") { it.text },
                            hasSynced = true
                        )
                    )
                }

                val plainLyrics = json.optString("plainLyrics", "")
                if (plainLyrics.isNotBlank()) {
                    Timber.tag("LyricsRepository").d("fetchSynced: /get matched a track but it has no syncedLyrics, only plainLyrics")
                    return Step.Found(
                        LyricsResult(
                            syncedLines = emptyList(),
                            plainText = plainLyrics,
                            hasSynced = false
                        )
                    )
                }

                Step.NotFound // the track exists but carries no lyrics
            } catch (e: Exception) {
                Timber.tag("LyricsRepository").e("Synced response could not be read: ${e.message}")
                Step.Failed(unreachable = false)
            }
        }
    }

    private fun fetchPlain(title: String, artist: String): Step {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedArtist = URLEncoder.encode(artist, "UTF-8")
        val url = "$BASE_URL/search?q=$encodedTitle+$encodedArtist"
        Timber.tag("LyricsRepository").d("fetchPlain: GET $url")

        return when (val http = httpGet(url)) {
            is Http.Failed -> Step.Failed(http.unreachable)
            Http.NotFound -> Step.NotFound
            is Http.Ok -> try {
                val arr = JSONArray(http.body)
                if (arr.length() == 0) return Step.NotFound

                val plainLyrics = arr.getJSONObject(0).optString("plainLyrics", "")
                if (plainLyrics.isBlank()) return Step.NotFound

                Step.Found(
                    LyricsResult(
                        syncedLines = emptyList(),
                        plainText = plainLyrics,
                        hasSynced = false
                    )
                )
            } catch (e: Exception) {
                Timber.tag("LyricsRepository").e("Plain response could not be read: ${e.message}")
                Step.Failed(unreachable = false)
            }
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
