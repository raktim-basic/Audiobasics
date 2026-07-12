package com.rkd.audiobasics.api

import android.content.Context
import android.util.Log
import timber.log.Timber
import com.rkd.audiobasics.api.cipher.CipherDeobfuscator
import com.rkd.audiobasics.api.cipher.FunctionNameExtractor
import com.rkd.audiobasics.api.cipher.PlayerJsFetcher
import com.rkd.audiobasics.api.potoken.PoTokenGenerator
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.data.Song
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
    /**
     * Extracts individual artist names from a raw "runs" JSONArray (the artist-credit portion
     * of a song/album subtitle, already truncated to before the first "•"), treating run
     * boundaries as the only source of truth for where one artist name ends and another
     * begins — never re-splitting a single run's own text on comma/ampersand.
     *
     * This is the fix for artist names that contain a comma or ampersand as part of the name
     * itself (e.g. "Tyler, The Creator", "Earth, Wind & Fire") — YouTube always gives these as
     * ONE run with the full name as its text, while genuinely separate co-artists are given as
     * separate runs joined by a literal separator run (", " or " & "). Splitting the joined
     * display string by comma after the fact is lossy and can't tell these cases apart; reading
     * the runs directly can.
     */
    /**
     * Extracts individual artist names from a raw "runs" list (the artist-credit portion of a
     * song/album subtitle, already truncated to before the first "•").
     *
     * Reconstructs the segment's full text (including any comma runs, e.g. "Tyler" / ", " /
     * "The Creator" → "Tyler, The Creator") and delegates to [splitArtistNames], so both the
     * "never split on a bare comma" rule and the [COMMA_IS_SEPARATOR_IN] allowlist for known
     * genuine multi-artist comma credits apply consistently, whether the source data gave us
     * the credit as separate runs or as one already-joined string.
     */
    private fun extractArtistNamesFromRuns(runs: List<JSONObject>): List<String> {
        val text = runs.joinToString("") { it.optString("text", "") }
        return splitArtistNames(text)
    }



    private var newPipeInitialized = false

    private val httpClient = OkHttpClient.Builder()
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
    private const val FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    // Visitor data fetched from YouTube on first use — used as poToken session ID.
    // Using a real YouTube visitor ID gives BotGuard enough entropy to produce
    // valid 110-128 byte tokens. Random UUIDs can produce short tokens for some videos.
    @Volatile var visitorData: String? = null

    // Cached signature timestamp from player.js — required for WEB_REMIX to return OK.
    // Changes when YouTube updates their player JS (every few days/weeks).
    @Volatile private var signatureTimestamp: Int? = null
    @Volatile private var signatureTimestampFetchedAt: Long = 0
    private const val SIG_TIMESTAMP_TTL = 6 * 60 * 60 * 1000L // 6 hours

    private val poTokenGenerator = PoTokenGenerator()

    // ─── Startup init ────────────────────────────────────────────────────────────

    // Call this once from MusicService.onCreate() — fetches visitorData + signatureTimestamp
    // so they're ready before the first song plays.
    suspend fun init() = withContext(Dispatchers.IO) {
        if (visitorData == null) fetchVisitorData()
        if (signatureTimestamp == null) fetchSignatureTimestamp()
    }

    private suspend fun fetchVisitorData() {
        try {
            // Hit the YouTube homepage with a browser UA — YouTube sets X-Visitor-Data
            // in the response which is a real visitor session identifier.
            val request = Request.Builder()
                .url("https://www.youtube.com/")
                .header("User-Agent", FIREFOX_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            val vd = response.header("X-Visitor-Data")
                ?: response.header("x-visitor-data")
            if (!vd.isNullOrBlank()) {
                visitorData = vd
                NewPipeDownloader.visitorData = vd
                Timber.d("visitorData fetched: ${vd.take(20)}... ✅")
            } else {
                // Fallback: extract from HTML
                val body = response.body?.string() ?: ""
                val match = Regex(""""visitorData"\s*:\s*"([^"]+)"""").find(body)
                    ?: Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""").find(body)
                val extracted = match?.groupValues?.get(1)
                if (!extracted.isNullOrBlank()) {
                    visitorData = extracted
                    NewPipeDownloader.visitorData = extracted
                    Timber.d("visitorData extracted from HTML: ${extracted.take(20)}... ✅")
                } else {
                    Timber.w("Could not fetch visitorData — will use UUID fallback")
                }
            }
        } catch (e: Exception) {
            Timber.w("fetchVisitorData failed: ${e.message}")
        }
    }

    private suspend fun fetchSignatureTimestamp() {
        try {
            val now = System.currentTimeMillis()
            if (signatureTimestamp != null &&
                now - signatureTimestampFetchedAt < SIG_TIMESTAMP_TTL) return

            val result = PlayerJsFetcher.getPlayerJs()
            if (result != null) {
                val (playerJs, _) = result
                val sts = FunctionNameExtractor.extractSignatureTimestamp(playerJs)
                if (sts != null) {
                    signatureTimestamp = sts
                    signatureTimestampFetchedAt = now
                    Timber.d("signatureTimestamp=$sts ✅")
                } else {
                    Timber.w("extractSignatureTimestamp returned null")
                }
            } else {
                Timber.w("PlayerJsFetcher returned null")
            }
        } catch (e: Exception) {
            Timber.w("fetchSignatureTimestamp failed: ${e.message}")
        }
    }

    // ─── YTM helpers (search / album / metadata) ─────────────────────────────────

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
                .addHeader("User-Agent", FIREFOX_UA)
                .addHeader("Origin", "https://music.youtube.com")
                .addHeader("Referer", "https://music.youtube.com/")
                .addHeader("X-YouTube-Client-Name", "67")
                .addHeader("X-YouTube-Client-Version", YTM_CLIENT_VERSION)
                .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            val response = httpClient.newCall(request).execute()
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

    // Comma-joined phrases where the comma is a genuine separator between two distinct artists
    // (as opposed to being part of one artist's own name, e.g. "Tyler, The Creator"). These are
    // checked first and replaced with "&" before the general split below, so both halves come
    // out as separate artists. Kanye West releases in particular are often credited under
    // multiple names/aliases for the same person or collective (DONDA, Kanye West, Ye) joined
    // by a bare comma in YouTube's own data, which the generic no-comma-split rule can't
    // distinguish from a name like "Tyler, The Creator" — so these need to be listed explicitly.
    private val COMMA_IS_SEPARATOR_IN = listOf(
        "DONDA, Kanye West" to "DONDA & Kanye West",
        "Kanye West, Ye" to "Kanye West & Ye",
        "DONDA, Ye" to "DONDA & Ye"
    )

    private fun normalizeKnownCommaSeparators(artist: String): String {
        var result = artist
        COMMA_IS_SEPARATOR_IN.forEach { (from, to) ->
            result = result.replace(from, to, ignoreCase = true)
        }
        return result
    }

    // Splits an artist credit string like "Future & Metro Boomin" or "Future and Metro Boomin"
    // into individual, trimmed names. Deliberately does NOT split on comma — YouTube uses a
    // comma both between genuinely separate co-artists (rare; usually they use "&" or "and")
    // and inside a single artist's own name (e.g. "Tyler, The Creator", "Earth, Wind & Fire"),
    // and there's no reliable way to tell these apart from the string alone. Only "&" and the
    // word "and" are safe, unambiguous separators. Also doesn't try to detect "(feat. ...)" —
    // YTM doesn't consistently mark guest features that way, so callers should instead use this
    // to intersect names across every track on an album and keep only the ones present on all.
    // A short explicit allowlist (see [COMMA_IS_SEPARATOR_IN]) covers known cases where a comma
    // really does separate two distinct artist credits.
    fun splitArtistNames(artist: String): List<String> {
        return normalizeKnownCommaSeparators(artist).trim()
            .split(Regex("\\s*&\\s*|\\s+and\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim().trim(',').trim() }
            .filter { it.isNotBlank() }
    }

    /** Same as [splitArtistNames] but also splits on "feat." — used where a full song credit
     *  string (e.g. "Tyler, The Creator feat. Kali Uchis") needs breaking into individual
     *  artist names for display/linking. Still never splits on a bare, unlisted comma. */
    fun splitArtistNamesWithFeat(artist: String): List<String> {
        return normalizeKnownCommaSeparators(artist).trim()
            .split(Regex("\\s*&\\s*|\\s+and\\s+|\\s*feat\\.\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim().trim(',').trim() }
            .filter { it.isNotBlank() }
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
            2 -> (p[0].toLongOrNull() ?: 0) * 60000 + (p[1].toLongOrNull() ?: 0) * 1000
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
                    ?.optJSONObject("text")?.optJSONArray("runs") ?: continue
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
                ?.optJSONObject("text")?.optJSONArray("runs") ?: continue
            for (r in 0 until runs.length()) {
                val t = runs.optJSONObject(r)?.optString("text", "") ?: ""
                if (t.matches(Regex("\\d+:\\d{2}"))) return parseDurationString(t)
            }
        }
        return 0L
    }

    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        try {
            parseSongsFromYTM(ytmPost("search",
                JSONObject().put("query", query).put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF")), songs)
        } catch (e: Exception) {
            Log.e("Innertube", "YTM search error: ${e.message}")
        }
        if (songs.isEmpty()) {
            val albumsUnused = mutableListOf<Song>()
            fallbackSearch(query, songs, albumsUnused)
        }
        songs.sortedByDescending { it.isExplicit }
    }

    private fun parseSongsFromYTM(response: JSONObject?, out: MutableList<Song>) {
        try {
            val sections = response
                ?.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")?.getJSONObject(0)
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
                            ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
                        val videoId = extractVideoId(overlay?.optJSONObject("playNavigationEndpoint")) ?: continue
                        val flexCols = item.optJSONArray("flexColumns") ?: continue
                        val col0 = flexCols.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val title = extractText(col0, "text"); if (title.isBlank()) continue
                        val col1 = flexCols.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val runs = col1?.optJSONObject("text")?.optJSONArray("runs")
                        var artist = ""; var durationMs = 0L
                        if (runs != null) {
                            val artistParts = mutableListOf<String>()
                            var hitDot = false
                            for (r in 0 until runs.length()) {
                                val t = runs.optJSONObject(r)?.optString("text", "") ?: ""
                                if (t == " • " || t == "•") {
                                    if (!hitDot) { hitDot = true; continue } else break
                                }
                                if (t.isBlank()) continue
                                if (!hitDot) artistParts.add(t)
                                else if (t.contains(":") && t.length <= 7) durationMs = parseDurationString(t)
                            }
                            artist = artistParts.joinToString("")
                        }
                        // Extract albumId from album-name run's navigation endpoint (segment 1)
                        var albumId = ""
                        if (runs != null) {
                            var seg = 0
                            for (r in 0 until runs.length()) {
                                val runObj = runs.optJSONObject(r) ?: continue
                                val t = runObj.optString("text", "")
                                if (t == " • " || t == "•") { seg++; continue }
                                if (t.isBlank()) continue
                                if (seg == 1) {
                                    albumId = runObj.optJSONObject("navigationEndpoint")
                                        ?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
                                    break
                                }
                            }
                            if (albumId.isBlank()) {
                                val segments = mutableListOf<String>()
                                var cur = StringBuilder()
                                for (r in 0 until runs.length()) {
                                    val t = runs.optJSONObject(r)?.optString("text", "") ?: ""
                                    if (t == " • " || t == "•") { segments.add(cur.toString()); cur = StringBuilder() }
                                    else cur.append(t)
                                }
                                segments.add(cur.toString())
                                Timber.tag("AlbumIdDebug").d("parseSongsFromYTM song='%s' albumId BLANK, segments=%s", title, segments)
                            }
                        }
                        out.add(Song(id = videoId, title = title, artist = artist,
                            thumbnail = ytThumbnail(videoId), duration = durationMs,
                            albumId = albumId,
                            isExplicit = parseExplicit(item.optJSONArray("badges"))))
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) { Log.e("Innertube", "parseSongsFromYTM error: ${e.message}") }
    }

    private fun parseAlbumsFromYTM(response: JSONObject?, out: MutableList<Song>) {
        try {
            val sections = response
                ?.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")?.getJSONObject(0)
                ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents") ?: return
            for (si in 0 until sections.length()) {
                val items = sections.optJSONObject(si)
                    ?.optJSONObject("musicShelfRenderer")?.optJSONArray("contents") ?: continue
                for (ii in 0 until items.length()) {
                    try {
                        val item = items.getJSONObject(ii)
                            .optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        val flexCols = item.optJSONArray("flexColumns") ?: continue
                        val col0 = flexCols.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val title = extractText(col0, "text"); if (title.isBlank()) continue
                        val col1 = flexCols.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        val runs = col1?.optJSONObject("text")?.optJSONArray("runs")
                        var artist = ""
                        if (runs != null) {
                            // Collect all runs in the second segment (artist names between first and second " • ")
                            val parts = mutableListOf<String>()
                            var segment = 0
                            for (r in 0 until runs.length()) {
                                val t = runs.optJSONObject(r)?.optString("text", "") ?: ""
                                if (t == " • " || t == "•") { segment++; if (segment >= 2) break; continue }
                                if (t.isBlank()) continue
                                if (segment == 1) parts.add(t) // second segment = artist
                            }
                            artist = parts.joinToString("").trim()
                            // If first segment wasn't "Album"/"EP" type, it might be the artist itself
                            if (artist.isBlank()) {
                                segment = 0; val fallbackParts = mutableListOf<String>()
                                for (r in 0 until runs.length()) {
                                    val t = runs.optJSONObject(r)?.optString("text", "") ?: ""
                                    if (t == " • " || t == "•") { segment++; if (segment >= 1) break; continue }
                                    if (t.isBlank() || t.all { c -> c.isDigit() }) continue
                                    if (segment == 0 && t.lowercase() !in listOf("album", "ep", "single")) fallbackParts.add(t)
                                }
                                artist = fallbackParts.joinToString("").trim()
                            }
                        }
                        var browseId = col0?.optJSONObject("text")?.optJSONArray("runs")
                            ?.getJSONObject(0)?.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
                        if (browseId.isEmpty()) {
                            browseId = item.optJSONObject("overlay")
                                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
                                ?.optJSONObject("playNavigationEndpoint")
                                ?.optJSONObject("watchPlaylistEndpoint")
                                ?.optString("playlistId") ?: ""
                        }
                        if (browseId.isEmpty()) continue
                        val thumbnails = item.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        val thumb = if (thumbnails != null && thumbnails.length() > 0)
                            thumbnails.getJSONObject(thumbnails.length() - 1).optString("url", "") else ""
                        // Extract year from subtitle runs (any 4-digit segment)
                        var albumYear = ""
                        if (runs != null) {
                            for (r in 0 until runs.length()) {
                                val t = runs.optJSONObject(r)?.optString("text", "") ?: ""
                                if (t.length == 4 && t.all { c -> c.isDigit() }) { albumYear = t; break }
                            }
                        }
                        out.add(Song(id = browseId, title = title,
                            artist = "(Album) ${artist.ifBlank { "Unknown Artist" }}",
                            thumbnail = thumb, isAlbum = true, year = albumYear))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun fallbackSearch(query: String, songs: MutableList<Song>, albums: MutableList<Song>) {
        try {
            initNewPipe()
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            for (item in extractor.initialPage.items) {
                try {
                    when (item) {
                        is org.schabi.newpipe.extractor.playlist.PlaylistInfoItem -> {
                            if (albums.size < 3) albums.add(Song(
                                id = extractIdFromUrl(item.url), title = item.name ?: continue,
                                artist = "(Album) ${item.uploaderName ?: ""}",
                                thumbnail = item.thumbnails.maxByOrNull { it.width * it.height }?.url ?: "",
                                isAlbum = true))
                        }
                        is org.schabi.newpipe.extractor.stream.StreamInfoItem -> {
                            val dur = item.duration; if (dur < 60 || dur > 900) continue
                            val title = item.name ?: continue
                            songs.add(Song(id = extractIdFromUrl(item.url), title = title,
                                artist = item.uploaderName ?: "", thumbnail = ytThumbnail(extractIdFromUrl(item.url)),
                                duration = dur * 1000,
                                isExplicit = title.lowercase().contains("explicit") || title.lowercase().contains("dirty")))
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.e("Innertube", "Fallback search error: ${e.message}") }
    }

    suspend fun getAlbumSongs(browseId: String, fallbackArtist: String = "", caller: String = "unknown"): Pair<Album?, List<Song>> =
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<Song>()
            var albumTitle = ""
            var albumArtist = fallbackArtist
            var albumYear = ""
            var albumThumb = ""
            try {
                // Playlist-style ids (e.g. OLAK5uy_..., PL..., RDCLAK...) must be browsed with
                // a "VL" prefix — passing them bare causes an INVALID_ARGUMENT (400) error.
                // Album-style ids (MPREb_...) must NOT be prefixed.
                val effectiveBrowseId = if (!browseId.startsWith("MPREb_") && !browseId.startsWith("VL")) {
                    "VL$browseId"
                } else browseId
                val step1 = ytmPost("browse", JSONObject().put("browseId", effectiveBrowseId))
                    ?: return@withContext Pair(null, emptyList())
                Timber.tag("AlbumIdDebug").d("getAlbumSongs[%s] INPUT browseId='%s' effectiveBrowseId='%s' topLevelKeys=%s", caller, browseId, effectiveBrowseId, step1.keys().asSequence().toList())

                // ── Metadata: album browse responses (MPREb_ ids) don't return a header
                // renderer at all — the only reliable source of title/artist/thumbnail is
                // the microformat block, which is always present. ──
                val microformatData = step1.optJSONObject("microformat")
                    ?.optJSONObject("microformatDataRenderer")

                // microformat title is formatted as "<Album Title> - Album by <Artist>"
                // (sometimes "- Single by" / "- EP by"). Split it apart.
                val rawTitle = microformatData?.optString("title", "") ?: ""
                val titleMatch = Regex("""^(.*?)\s*-\s*(?:Album|Single|EP|Playlist)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE)
                    .find(rawTitle)
                if (titleMatch != null) {
                    albumTitle = titleMatch.groupValues[1].trim()
                    if (albumArtist.isBlank() || albumArtist == fallbackArtist) {
                        albumArtist = titleMatch.groupValues[2].trim()
                    }
                } else if (rawTitle.isNotBlank()) {
                    albumTitle = rawTitle
                }

                val thumbArr = microformatData?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                if (thumbArr != null && thumbArr.length() > 0) {
                    albumThumb = thumbArr.getJSONObject(thumbArr.length() - 1).optString("url", "")
                }

                // Also still try a classic header renderer. Per ViMusic's proven approach,
                // for twoColumnBrowseResultsRenderer responses the header is NOT a top-level
                // "header" key — it's the first item inside the primary tab's own section list:
                // contents.twoColumnBrowseResultsRenderer.tabs[0].tabRenderer.content
                //   .sectionListRenderer.contents[0].musicResponsiveHeaderRenderer
                var header = step1.optJSONObject("header")
                val twoColumn = step1.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
                if (header == null) {
                    header = twoColumn?.optJSONObject("header")
                }
                var detailHeader = header?.optJSONObject("musicDetailHeaderRenderer")
                    ?: header?.optJSONObject("musicImmersiveHeaderRenderer")
                    ?: header?.optJSONObject("musicResponsiveHeaderRenderer")
                if (detailHeader == null) {
                    val tabHeaderContainer = twoColumn?.optJSONArray("tabs")
                        ?.optJSONObject(0)?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")?.optJSONObject(0)
                    detailHeader = tabHeaderContainer?.optJSONObject("musicResponsiveHeaderRenderer")
                        ?: tabHeaderContainer?.optJSONObject("musicDetailHeaderRenderer")
                        ?: tabHeaderContainer?.optJSONObject("musicImmersiveHeaderRenderer")
                }
                // Also check the single-column response shape (used for some browse variants)
                if (detailHeader == null) {
                    val singleColHeaderContainer = step1.optJSONObject("contents")
                        ?.optJSONObject("singleColumnBrowseResultsRenderer")
                        ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")?.optJSONObject(0)
                    detailHeader = singleColHeaderContainer?.optJSONObject("musicResponsiveHeaderRenderer")
                        ?: singleColHeaderContainer?.optJSONObject("musicDetailHeaderRenderer")
                        ?: (header?.optJSONObject("musicDetailHeaderRenderer"))
                }
                if (detailHeader != null) {
                    val headerTitle = extractText(detailHeader, "title")
                    if (headerTitle.isNotBlank()) albumTitle = headerTitle
                    val headerThumbArr = detailHeader.optJSONObject("thumbnail")
                        ?.optJSONObject("croppedSquareThumbnailRenderer")
                        ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        ?: detailHeader.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    if (headerThumbArr != null && headerThumbArr.length() > 0)
                        albumThumb = headerThumbArr.getJSONObject(headerThumbArr.length() - 1).optString("url", "")
                    // Subtitle segments: typically "Album • Artist • Year • N songs"
                    val subtitleRuns = detailHeader.optJSONObject("subtitle")?.optJSONArray("runs")
                    if (subtitleRuns != null) {
                        val segments = mutableListOf<String>()
                        var curSeg = StringBuilder()
                        for (r in 0 until subtitleRuns.length()) {
                            val t = subtitleRuns.optJSONObject(r)?.optString("text", "") ?: ""
                            if (t == " • " || t == "•") {
                                val s = curSeg.toString().trim()
                                if (s.isNotBlank()) segments.add(s)
                                curSeg = StringBuilder()
                            } else curSeg.append(t)
                        }
                        val s = curSeg.toString().trim(); if (s.isNotBlank()) segments.add(s)
                        for (seg in segments) {
                            when {
                                seg.length == 4 && seg.all { it.isDigit() } -> albumYear = seg
                                seg.lowercase() in listOf("album", "ep", "single", "playlist") -> {}
                                seg.matches(Regex("\\d+ songs?")) -> {}
                                albumArtist == fallbackArtist && seg.isNotBlank() -> albumArtist = seg
                            }
                        }
                    }
                    // Some header variants (musicResponsiveHeaderRenderer) put the artist in
                    // straplineTextOne instead of a dedicated "artist" field.
                    val straplineRuns = detailHeader.optJSONObject("straplineTextOne")?.optJSONArray("runs")
                    if (straplineRuns != null && straplineRuns.length() > 0 && (albumArtist.isBlank() || albumArtist == fallbackArtist)) {
                        val parts = (0 until straplineRuns.length())
                            .mapNotNull { straplineRuns.optJSONObject(it)?.optString("text", "")?.takeIf { t -> t.isNotBlank() } }
                        if (parts.isNotEmpty()) albumArtist = parts.joinToString("")
                    }
                    val artistRuns = detailHeader.optJSONObject("artist")?.optJSONArray("runs")
                    if (artistRuns != null && artistRuns.length() > 0) {
                        val parts = (0 until artistRuns.length())
                            .mapNotNull { artistRuns.optJSONObject(it)?.optString("text", "")?.takeIf { t -> t.isNotBlank() } }
                        if (parts.isNotEmpty()) albumArtist = parts.joinToString("")
                    }
                }

                // ── Year: microformat's description usually contains a release date
                // ("...released on April 14, 2017...") — pull the year from there first. ──
                if (albumYear.isBlank()) {
                    val description = microformatData?.optString("description", "") ?: ""
                    albumYear = Regex("""released on [A-Za-z]+ \d{1,2},\s*(\d{4})""")
                        .find(description)?.groupValues?.get(1) ?: ""
                }
                if (albumYear.isBlank()) {
                    val uploadDate = microformatData?.optString("uploadDate", "")
                    if (!uploadDate.isNullOrBlank() && uploadDate.length >= 4) albumYear = uploadDate.take(4)
                }
                if (albumYear.isBlank()) {
                    albumYear = Regex(""""year"\s*:\s*"(\d{4})"""")
                        .find(step1.toString())?.groupValues?.get(1) ?: ""
                }
                if (albumYear.isBlank()) {
                    val description = microformatData?.optString("description", "") ?: ""
                    albumYear = Regex("""(?<![\d])(20[0-2]\d|19[5-9]\d)(?![\d])""")
                        .find(description)?.groupValues?.get(1) ?: ""
                }

                // ── Tracklist: the browse response for MPREb_ ids already contains the
                // full tracklist directly under secondaryContents — no second call needed.
                // (A second browse using a derived VL<playlistId> id is unreliable and can
                // 400 for these ids, so we no longer make that call.) ──
                val shelfContents = step1.optJSONObject("contents")
                    ?.optJSONObject("twoColumnBrowseResultsRenderer")
                    ?.optJSONObject("secondaryContents")
                    ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                    ?.optJSONObject(0)?.let { firstSection ->
                        firstSection.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
                            ?: firstSection.optJSONObject("musicPlaylistShelfRenderer")?.optJSONArray("contents")
                    }
                Timber.tag("AlbumIdDebug").d("getAlbumSongs browseId='%s' shelfContentsFound=%s shelfLen=%s", browseId, shelfContents != null, shelfContents?.length())
                if (shelfContents == null) {
                    Timber.tag("AlbumIdDebug").d("getAlbumSongs browseId='%s' NO SHELF. topLevelKeys=%s twoColKeys=%s", browseId, step1.keys().asSequence().toList(), step1.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")?.keys()?.asSequence()?.toList())
                    val singleCol = step1.optJSONObject("contents")?.optJSONObject("singleColumnBrowseResultsRenderer")
                    if (singleCol != null) {
                        Timber.tag("AlbumIdDebug").d("getAlbumSongs browseId='%s' HAS singleColumnBrowseResultsRenderer, keys=%s", browseId, singleCol.keys().asSequence().toList())
                    }
                }
                if (shelfContents != null) {
                    for (i in 0 until shelfContents.length()) {
                        try {
                            val item = shelfContents.getJSONObject(i)
                                .optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            val videoId = item.optJSONObject("playlistItemData")
                                ?.optString("videoId")?.takeIf { it.isNotBlank() }
                                ?: item.optJSONObject("overlay")
                                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                    ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
                                    ?.optJSONObject("playNavigationEndpoint")
                                    ?.optJSONObject("watchEndpoint")?.optString("videoId") ?: continue
                            val col0 = item.optJSONArray("flexColumns")?.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            val songTitle = extractText(col0, "text"); if (songTitle.isBlank()) continue
                            // Per-song artist from col1 subtitle (same logic as parseSongsFromYTM)
                            val col1 = item.optJSONArray("flexColumns")?.optJSONObject(1)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            val col1Runs = col1?.optJSONObject("text")?.optJSONArray("runs")
                            var songArtist = albumArtist.ifBlank { fallbackArtist.ifBlank { "Unknown Artist" } }
                            var songArtistNames: List<String> = emptyList()
                            if (col1Runs != null) {
                                val parts = mutableListOf<String>()
                                val preDotRuns = mutableListOf<JSONObject>()
                                var hitDot = false
                                for (r in 0 until col1Runs.length()) {
                                    val runObj = col1Runs.optJSONObject(r)
                                    val t = runObj?.optString("text", "") ?: ""
                                    if (t == " • " || t == "•") { if (!hitDot) { hitDot = true; continue } else break }
                                    if (t.isBlank()) continue
                                    if (!hitDot) {
                                        parts.add(t)
                                        if (runObj != null) preDotRuns.add(runObj)
                                    }
                                }
                                if (parts.isNotEmpty()) songArtist = parts.joinToString("")
                                songArtistNames = extractArtistNamesFromRuns(preDotRuns)
                            }
                            songs.add(Song(id = videoId, title = songTitle, artist = songArtist,
                                artistNames = songArtistNames,
                                thumbnail = ytThumbnail(videoId), duration = parseDurationMs(item),
                                albumId = browseId, albumTitle = albumTitle,
                                isExplicit = parseExplicit(item.optJSONArray("badges"))))
                        } catch (_: Exception) {}
                    }
                }
                // ── Album artist: derive from the tracklist itself rather than trusting the
                // header/microformat alone, since YTM's album-level artist credit is often
                // incomplete (e.g. shows only "Future" for an album that's really
                // "Future & Metro Boomin" throughout). An artist only belongs in the album
                // credit if they appear on EVERY track — this naturally excludes one-off
                // guest features (which show up in the same comma/& format, with no
                // reliable "feat." marker to detect) while keeping the true core artist(s). ──
                if (songs.isNotEmpty()) {
                    try {
                        val perSongNames = songs.map { splitArtistNames(it.artist) }
                        val commonNames = perSongNames.reduce { acc, names -> acc.intersect(names.toSet()).toList() }
                        if (commonNames.isNotEmpty()) {
                            // Preserve the order names appear in the first song's artist string.
                            val firstSongOrder = perSongNames.first()
                            val ordered = firstSongOrder.filter { it in commonNames }
                            if (ordered.isNotEmpty()) albumArtist = ordered.joinToString(" & ")
                        } else if (albumArtist == fallbackArtist) {
                            // No name is common to every track (e.g. a various-artists
                            // compilation) and the header gave us nothing either — fall back
                            // to the single most common per-song artist string.
                            albumArtist = songs.groupBy { it.artist }
                                .maxByOrNull { it.value.size }?.key ?: fallbackArtist
                        }
                    } catch (e: Exception) {
                        Timber.tag("AlbumIdDebug").d("getAlbumSongs browseId='%s' artist intersection FAILED: %s", browseId, e.message)
                    }
                }
            } catch (e: Exception) { Log.e("Innertube", "getAlbumSongs error: ${e.message}") }
            Timber.tag("AlbumIdDebug").d("getAlbumSongs[%s] RESULT browseId='%s' albumTitle='%s' albumArtist='%s' albumYear='%s' songCount=%d", caller, browseId, albumTitle, albumArtist, albumYear, songs.size)
            Pair(Album(id = browseId, title = albumTitle, artist = albumArtist,
                thumbnail = albumThumb, year = albumYear), songs)
        }
    suspend fun getVideoMetadata(videoId: String): Song? = withContext(Dispatchers.IO) {
        initNewPipe()
        try {
            val extractor = ServiceList.YouTube
                .getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            val title = extractor.name ?: return@withContext null
            Song(id = videoId, title = title, artist = extractor.uploaderName ?: "",
                thumbnail = ytThumbnail(videoId), duration = extractor.length * 1000L,
                isExplicit = title.lowercase().contains("explicit"))
        } catch (e: Exception) { Log.e("Innertube", "Metadata error: ${e.message}"); null }
    }

    /**
     * Re-resolves a song through the YTM search path (the same one used for browsing/search
     * results) rather than the plain video extractor, so it picks up the correct multi-artist
     * splitting and album linkage that older library exports may be missing or have wrong.
     * Matches the result back to [song] by video ID so the original song's identity is never
     * lost; falls back to the original untouched [song] if no confident match is found or the
     * lookup fails, so refreshing metadata can never destroy an existing library entry.
     */
    suspend fun refreshSongMetadata(song: Song): Song = withContext(Dispatchers.IO) {
        try {
            val results = search("${song.title} ${song.artist}".trim())
            results.firstOrNull { it.id == song.id }
                ?: song // no confident match — keep what we already had rather than guess
        } catch (e: Exception) {
            Log.e("Innertube", "refreshSongMetadata error for ${song.id}: ${e.message}")
            song
        }
    }

    // ─── Stream resolution ───────────────────────────────────────────────────────

    private data class YTClient(
        val clientName: String,
        val clientVersion: String,
        val clientId: String,
        val userAgent: String,
        val usePoTokenInBody: Boolean = false,
        val appendPotToUrl: Boolean = false,
        val useSignatureTimestamp: Boolean = false
    )

    // WEB_REMIX first — its CDN URLs have no per-URL chunk request limit.
    // IOS/ANDROID as fallbacks — limited to ~16 chunks per URL (~64s at 512KB).
    private val STREAM_CLIENTS = listOf(
        YTClient("WEB_REMIX", "1.20260213.01.00", "67", FIREFOX_UA,
            usePoTokenInBody = true, appendPotToUrl = true, useSignatureTimestamp = true),
        YTClient("WEB", "2.20260213.00.00", "1", FIREFOX_UA,
            usePoTokenInBody = true, appendPotToUrl = true, useSignatureTimestamp = true),
        YTClient("IOS", "21.03.1", "5",
            "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)",
            usePoTokenInBody = false, appendPotToUrl = true, useSignatureTimestamp = false),
        YTClient("ANDROID", "19.44.38", "3",
            "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip",
            usePoTokenInBody = false, appendPotToUrl = true, useSignatureTimestamp = false),
    )

    fun parseExpiry(url: String): Long {
        val expireUnixSec = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return if (expireUnixSec != null) expireUnixSec * 1000L
        else System.currentTimeMillis() + 6 * 60 * 60 * 1000L
    }

    private data class ResolvedFormat(
        val url: String,
        val bitrate: Int,
        val mimeType: String,
        val itag: Int,
        val hasAlr: Boolean
    )

    // Pure selection — no suspend, no side effects.
    // Web clients prefer opus for quality; mobile clients prefer mp4a for compatibility.
    private fun selectBestAudioFormat(formats: List<ResolvedFormat>, client: YTClient): ResolvedFormat? {
        if (formats.isEmpty()) return null
        val sorted = formats.sortedWith(
            compareByDescending<ResolvedFormat> { if (!it.hasAlr) 1 else 0 }
                .thenByDescending { it.bitrate }
                .thenByDescending {
                    if (client.usePoTokenInBody) { if (it.mimeType.contains("opus")) 1 else 0 }
                    else { if (it.mimeType.contains("mp4a")) 1 else 0 }
                }
        )
        val best = sorted.first()
        Timber.d("${client.clientName}: selected itag=${best.itag} bitrate=${best.bitrate} mime=${best.mimeType} alr=${best.hasAlr}")
        return best
    }

    // Validate stream URL with a HEAD request before using it.
    // Catches URLs that resolve but are already expired or geo-blocked.
    private fun validateStreamUrl(url: String, clientName: String): Boolean {
        return try {
            val response = httpClient.newCall(
                Request.Builder().url(url).head().build()
            ).execute()
            val ok = response.isSuccessful
            Timber.d("$clientName: stream validate HTTP ${response.code} → ${if (ok) "✅" else "❌"}")
            ok
        } catch (e: Exception) {
            Timber.w("$clientName: stream validate exception: ${e.message}")
            false
        }
    }

    private suspend fun tryClientForStream(
        videoId: String,
        client: YTClient,
        playerRequestPoToken: String?,
        streamingDataPoToken: String?,
        sigTimestamp: Int?
    ): String? {
        val clientContext = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", client.clientVersion)
            .put("hl", "en")
            .put("gl", "US")

        // Include visitorData in context for web clients — ties request to real browser session
        visitorData?.let { clientContext.put("visitorData", it) }

        val body = JSONObject()
            .put("videoId", videoId)
            .put("context", JSONObject().put("client", clientContext))

        // poToken in body for web clients (WEB_REMIX, WEB)
        if (client.usePoTokenInBody && playerRequestPoToken != null) {
            body.put("serviceIntegrityDimensions",
                JSONObject().put("poToken", playerRequestPoToken))
        }

        // signatureTimestamp in playbackContext for web clients — required for OK status
        if (client.useSignatureTimestamp && sigTimestamp != null) {
            body.put("playbackContext", JSONObject().put("contentPlaybackContext",
                JSONObject().put("signatureTimestamp", sigTimestamp)))
        }

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=$YTM_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", client.userAgent)
            .addHeader("X-YouTube-Client-Name", client.clientId)
            .addHeader("X-YouTube-Client-Version", client.clientVersion)
            .apply {
                if (client.usePoTokenInBody) {
                    addHeader("Origin", "https://www.youtube.com")
                    addHeader("Referer", "https://www.youtube.com/")
                }
                visitorData?.let { addHeader("X-Visitor-Data", it) }
            }
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        val response = httpClient.newCall(request).execute()
        val text = response.body?.string() ?: return null
        val json = JSONObject(text)

        val status = json.optJSONObject("playabilityStatus")?.optString("status")
        if (status != "OK") {
            Timber.w("${client.clientName}: playabilityStatus=$status")
            return null
        }

        val adaptiveFormats = json.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats") ?: return null
        if (adaptiveFormats.length() == 0) return null

        // Pre-resolve all URLs in suspend context (cipher deobfuscation is suspend)
        val resolvedFormats = mutableListOf<ResolvedFormat>()
        for (i in 0 until adaptiveFormats.length()) {
            val f = adaptiveFormats.getJSONObject(i)
            val mimeType = f.optString("mimeType", "")
            if (!mimeType.startsWith("audio/")) continue
            val contentLength = f.optString("contentLength", "")
            if (contentLength.isBlank() || contentLength == "0") continue

            var url = f.optString("url", "")
            if (url.isEmpty()) {
                val cipher = f.optString("signatureCipher", "").ifEmpty { f.optString("cipher", "") }
                if (cipher.isNotEmpty()) url = CipherDeobfuscator.deobfuscateStreamUrl(cipher, videoId) ?: ""
            }
            if (url.isEmpty()) continue

            resolvedFormats.add(ResolvedFormat(
                url = url,
                bitrate = f.optInt("bitrate", 0),
                mimeType = mimeType,
                itag = f.optInt("itag", 0),
                hasAlr = url.contains("alr=yes")
            ))
        }

        if (resolvedFormats.isEmpty()) return null

        val best = selectBestAudioFormat(resolvedFormats, client) ?: return null

        // Remove alr=yes — ExoPlayer can't handle YouTube's adaptive loading protocol
        var finalUrl = best.url
            .replace("&alr=yes", "").replace("alr=yes&", "").replace("alr=yes", "")

        // n-param transform for web clients (unlocks throttle-free URLs)
        if (client.usePoTokenInBody) {
            finalUrl = CipherDeobfuscator.transformNParamInUrl(finalUrl)
        }

        // Append pot= to stream URL
        if (client.appendPotToUrl && streamingDataPoToken != null) {
            val sep = if ("?" in finalUrl) "&" else "?"
            finalUrl = "${finalUrl}${sep}pot=${android.net.Uri.encode(streamingDataPoToken)}"
            Timber.d("${client.clientName}: appended pot= ✅")
        }

        return finalUrl
    }

    // Returns Pair(streamUrl, expiryMs) or null.
    // Tries WEB_REMIX first (no CDN chunk limit), falls back to IOS/ANDROID.
    // Validates each URL with a HEAD request before returning.
    suspend fun getStreamUrl(
        context: Context,
        videoId: String,
        forceFallback: Boolean = false
    ): Pair<String, Long>? = withContext(Dispatchers.IO) {

        // Ensure visitorData + signatureTimestamp are ready
        if (visitorData == null) fetchVisitorData()
        if (signatureTimestamp == null) fetchSignatureTimestamp()

        val sigTimestamp = signatureTimestamp

        // Generate poToken — use visitorData as session ID for consistent entropy
        // Use first 36 chars of visitorData as session ID — full string causes 598-byte tokens
        // 36 chars gives same entropy as a UUID while staying within BotGuard expected range
        val sessionId = visitorData?.take(36) ?: java.util.UUID.randomUUID().toString()
        val poToken = try {
            poTokenGenerator.getWebClientPoToken(videoId, sessionId)
        } catch (e: Exception) {
            Log.w("Innertube", "PoToken generation failed: ${e.message}")
            null
        }

        poToken?.let { NewPipeDownloader.poToken = it.playerRequestPoToken }

        if (!forceFallback) {
            for (client in STREAM_CLIENTS) {
                try {
                    Timber.d("Trying client: ${client.clientName} sigTs=$sigTimestamp poToken=${poToken?.playerRequestPoToken?.take(10)}...")
                    val url = tryClientForStream(
                        videoId, client,
                        poToken?.playerRequestPoToken,
                        poToken?.streamingDataPoToken,
                        if (client.useSignatureTimestamp) sigTimestamp else null
                    ) ?: continue

                    // Validate stream URL before caching — skip validation for last client
                    val isLastClient = client == STREAM_CLIENTS.last()
                    if (!isLastClient && !validateStreamUrl(url, client.clientName)) {
                        Timber.w("${client.clientName}: stream validation failed, trying next")
                        continue
                    }

                    Timber.d("Stream resolved via ${client.clientName} ✅")
                    return@withContext url to parseExpiry(url)
                } catch (e: Exception) {
                    Log.w("Innertube", "Client ${client.clientName} failed: ${e.message}")
                }
            }
            Timber.e("All native clients FAILED for videoId=$videoId ❌")
        }

        // NewPipe fallback
        Timber.w("Falling to NewPipe (forceFallback=$forceFallback)")
        initNewPipe()
        try {
            val extractor = ServiceList.YouTube
                .getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            val url = extractor.audioStreams
                .filter { it.content != null && it.content.isNotEmpty() }
                .maxByOrNull { it.averageBitrate }?.content
            if (url != null) url to parseExpiry(url) else null
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
                for (item in extractor.relatedItems?.items ?: emptyList()) {
                    if (results.size >= limit) break
                    try {
                        if (item is org.schabi.newpipe.extractor.stream.StreamInfoItem) {
                            val dur = item.duration; if (dur < 60 || dur > 900) continue
                            val id = extractIdFromUrl(item.url)
                            results.add(Song(id = id, title = item.name ?: continue,
                                artist = item.uploaderName ?: "", thumbnail = ytThumbnail(id),
                                duration = dur * 1000))
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) { Log.e("Innertube", "Related songs error: ${e.message}") }
            results
        }


    // ── Album search ─────────────────────────────────────────────────────────
    suspend fun searchAlbums(query: String): List<com.rkd.audiobasics.data.Album> = withContext(Dispatchers.IO) {
        try {
            val albums = mutableListOf<Song>()
            parseAlbumsFromYTM(ytmPost("search",
                JSONObject().put("query", query).put("params", "EgWKAQIYAWoMEA4QChADEAQQCRAF")), albums)
            albums.map { song ->
                com.rkd.audiobasics.data.Album(id = song.id, title = song.title,
                    artist = song.artist.removePrefix("(Album) "), thumbnail = song.thumbnail,
                    year = song.year)
            }
        } catch (e: Exception) { Log.e("Innertube", "searchAlbums error: ${e.message}"); emptyList() }
    }

    // ── Artist search ─────────────────────────────────────────────────────────
    suspend fun searchArtists(query: String): List<com.rkd.audiobasics.data.Artist> =
        withContext(Dispatchers.IO) {
            try {
                val response = ytmPost("search",
                    JSONObject().put("query", query).put("params", "EgWKAQIgAWoMEA4QChADEAQQCRAF"))
                    ?: return@withContext emptyList()
                val artists = mutableListOf<com.rkd.audiobasics.data.Artist>()
                val tabs = response.optJSONObject("contents")
                    ?.optJSONObject("tabbedSearchResultsRenderer")?.optJSONArray("tabs")
                    ?: return@withContext emptyList()
                val contents = tabs.optJSONObject(0)?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents") ?: return@withContext emptyList()
                for (i in 0 until contents.length()) {
                    val items = contents.optJSONObject(i)
                        ?.optJSONObject("musicShelfRenderer")?.optJSONArray("contents") ?: continue
                    for (j in 0 until items.length()) {
                        try {
                            val r = items.optJSONObject(j)
                                ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            val browseId = r.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("browseEndpoint")?.optString("browseId") ?: continue
                            if (!browseId.startsWith("UC") && !browseId.startsWith("MPLA")) continue
                            val name = r.optJSONArray("flexColumns")?.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")?.optJSONArray("runs")
                                ?.optJSONObject(0)?.optString("text") ?: continue
                            val thumbs = r.optJSONObject("thumbnail")
                                ?.optJSONObject("musicThumbnailRenderer")
                                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                            val thumb = if (thumbs != null && thumbs.length() > 0)
                                thumbs.getJSONObject(thumbs.length() - 1).optString("url", "") else ""
                            artists.add(com.rkd.audiobasics.data.Artist(id = browseId, name = name, thumbnail = thumb))
                        } catch (_: Exception) {}
                    }
                }
                artists
            } catch (e: Exception) { Log.e("Innertube", "searchArtists error: ${e.message}"); emptyList() }
        }

    // ── Get artist page ───────────────────────────────────────────────────────
    suspend fun getArtistPage(browseId: String): com.rkd.audiobasics.data.ArtistPage? =
        withContext(Dispatchers.IO) {
            try {
                val json = ytmPost("browse", JSONObject().put("browseId", browseId))
                    ?: return@withContext null
                val header = json.optJSONObject("header")
                val immersive = header?.optJSONObject("musicImmersiveHeaderRenderer")
                val artistName = immersive?.optJSONObject("title")
                    ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                val thumbArr = immersive?.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                val artistThumb = if (thumbArr != null && thumbArr.length() > 0)
                    thumbArr.getJSONObject(thumbArr.length() - 1).optString("url", "") else ""
                val artist = com.rkd.audiobasics.data.Artist(id = browseId, name = artistName, thumbnail = artistThumb)
                val sections = json.optJSONObject("contents")
                    ?.optJSONObject("singleColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                    ?: return@withContext com.rkd.audiobasics.data.ArtistPage(artist, emptyList(), emptyList(), emptyList())
                val popularSongs = mutableListOf<Song>()
                val albums = mutableListOf<com.rkd.audiobasics.data.Album>()
                val singles = mutableListOf<com.rkd.audiobasics.data.Album>()
                for (i in 0 until sections.length()) {
                    val section = sections.optJSONObject(i) ?: continue
                    section.optJSONObject("musicShelfRenderer")?.let { shelf ->
                        val items = shelf.optJSONArray("contents") ?: return@let
                        for (j in 0 until minOf(items.length(), 10)) {
                            try {
                                val r = items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                                val videoId = r.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
                                    ?: r.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                        ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
                                        ?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
                                        ?.optString("videoId")?.takeIf { it.isNotBlank() }
                                    ?: r.optJSONArray("flexColumns")?.optJSONObject(0)
                                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                        ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)
                                        ?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                                        ?.optString("videoId")?.takeIf { it.isNotBlank() }
                                    ?: continue
                                val col0 = r.optJSONArray("flexColumns")?.optJSONObject(0)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                val title = extractText(col0, "text"); if (title.isBlank()) continue
                                val col1 = r.optJSONArray("flexColumns")?.optJSONObject(1)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                val col1Runs = col1?.optJSONObject("text")?.optJSONArray("runs")
                                var songArtist = artistName
                                var songArtistNames: List<String> = emptyList()
                                if (col1Runs != null) {
                                    val parts = mutableListOf<String>(); var hitDot = false
                                    val preDotRuns = mutableListOf<JSONObject>()
                                    for (r2 in 0 until col1Runs.length()) {
                                        val runObj = col1Runs.optJSONObject(r2)
                                        val t = runObj?.optString("text", "") ?: ""
                                        if (t == " • " || t == "•") { if (!hitDot) { hitDot = true; continue } else break }
                                        if (t.isBlank()) continue
                                        if (!hitDot) {
                                            parts.add(t)
                                            if (runObj != null) preDotRuns.add(runObj)
                                        }
                                    }
                                    if (parts.isNotEmpty()) songArtist = parts.joinToString("")
                                    songArtistNames = extractArtistNamesFromRuns(preDotRuns)
                                }
                                val thumb = r.optJSONObject("thumbnail")
                                    ?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
                                    ?.optJSONArray("thumbnails")?.let { t -> t.optJSONObject(t.length() - 1)?.optString("url") }
                                    ?: ytThumbnail(videoId)
                                popularSongs.add(Song(id = videoId, title = title, artist = songArtist,
                                    artistNames = songArtistNames, thumbnail = thumb))
                            } catch (_: Exception) {}
                        }
                    }
                    for (carouselKey in listOf("musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer")) {
                        section.optJSONObject(carouselKey)?.let { carousel ->
                            val carouselHeader = carousel.optJSONObject("header")
                                ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                                ?: carousel.optJSONObject("header")
                                    ?.optJSONObject("musicCarouselShelfHeaderRenderer")
                            val hdrTitle = carouselHeader?.optJSONObject("title")
                                ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                            val isAlbums = hdrTitle.contains("album", ignoreCase = true)
                            val isSingles = hdrTitle.contains("single", ignoreCase = true) || hdrTitle.contains("EP")
                            if (!isAlbums && !isSingles) return@let

                            // "See all" endpoint: first check title run navigation, then moreContentButton
                            val titleNavEndpoint = carouselHeader
                                ?.optJSONObject("title")?.optJSONArray("runs")
                                ?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("browseEndpoint")
                            val moreEndpoint = titleNavEndpoint
                                ?: carouselHeader?.optJSONObject("moreContentButton")
                                    ?.optJSONObject("buttonRenderer")
                                    ?.optJSONObject("navigationEndpoint")
                                    ?.optJSONObject("browseEndpoint")

                            val allItems = mutableListOf<JSONObject>()
                            val carouselItems = carousel.optJSONArray("contents")
                            if (carouselItems != null)
                                for (j in 0 until carouselItems.length())
                                    carouselItems.optJSONObject(j)?.let { allItems.add(it) }

                            // If there's a "See all" endpoint, fetch the full list
                            if (moreEndpoint != null) {
                                try {
                                    val moreBrowseId = moreEndpoint.optString("browseId", "")
                                    val moreParams = moreEndpoint.optString("params", "")
                                    if (moreBrowseId.isNotBlank()) {
                                        val moreBody = JSONObject().put("browseId", moreBrowseId)
                                        if (moreParams.isNotBlank()) moreBody.put("params", moreParams)
                                        val moreJson = ytmPost("browse", moreBody)
                                        // Parse grid items from the full list response
                                        // Try multiple response shapes for the full discography page
                                        val moreContents = moreJson?.optJSONObject("contents")
                                        val gridItems = moreContents
                                            ?.optJSONObject("singleColumnBrowseResultsRenderer")
                                            ?.optJSONArray("tabs")?.optJSONObject(0)
                                            ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                                            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                                            ?.let { sections ->
                                                val allGridItems = org.json.JSONArray()
                                                for (s in 0 until sections.length()) {
                                                    val shelf = sections.optJSONObject(s)
                                                        ?.optJSONObject("musicShelfRenderer")
                                                        ?.optJSONArray("contents")
                                                        ?: sections.optJSONObject(s)
                                                            ?.optJSONObject("gridRenderer")
                                                            ?.optJSONArray("items")
                                                    if (shelf != null)
                                                        for (k in 0 until shelf.length()) allGridItems.put(shelf.getJSONObject(k))
                                                }
                                                if (allGridItems.length() > 0) allGridItems else null
                                            }
                                            ?: moreContents?.optJSONObject("gridRenderer")?.optJSONArray("items")
                                            ?: moreContents?.optJSONObject("twoColumnBrowseResultsRenderer")
                                                ?.optJSONArray("tabs")?.optJSONObject(0)
                                                ?.optJSONObject("tabRenderer")?.optJSONObject("content")
                                                ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                                                ?.optJSONObject(0)?.optJSONObject("musicCarouselShelfRenderer")
                                                ?.optJSONArray("contents")
                                        if (gridItems != null) {
                                            allItems.clear()
                                            for (j in 0 until gridItems.length())
                                                gridItems.optJSONObject(j)?.let { allItems.add(it) }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            for (item in allItems) {
                                try {
                                    val r = item.optJSONObject("musicTwoRowItemRenderer")
                                        ?: item.optJSONObject("musicResponsiveListItemRenderer")
                                        ?: continue
                                    val albumBrowseId = r.optJSONObject("navigationEndpoint")
                                        ?.optJSONObject("browseEndpoint")?.optString("browseId") ?: continue
                                    val title = r.optJSONObject("title")?.optJSONArray("runs")
                                        ?.optJSONObject(0)?.optString("text") ?: continue
                                    val subtitleRuns = r.optJSONObject("subtitle")?.optJSONArray("runs")
                                    val year = subtitleRuns?.let { runs ->
                                        (0 until runs.length()).map { runs.optJSONObject(it)?.optString("text") ?: "" }
                                            .firstOrNull { it.length == 4 && it.all { c -> c.isDigit() } }
                                    } ?: ""
                                    val thumbArr2 = r.optJSONObject("thumbnailRenderer")
                                        ?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
                                        ?.optJSONArray("thumbnails")
                                        ?: r.optJSONObject("thumbnail")
                                            ?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
                                            ?.optJSONArray("thumbnails")
                                    val thumb = if (thumbArr2 != null && thumbArr2.length() > 0)
                                        thumbArr2.getJSONObject(0).optString("url", "") else ""
                                    val album = com.rkd.audiobasics.data.Album(id = albumBrowseId, title = title,
                                        artist = artistName, thumbnail = thumb, year = year)
                                    if (isAlbums) { if (albums.none { it.id == albumBrowseId }) albums.add(album) }
                                    else { if (singles.none { it.id == albumBrowseId }) singles.add(album) }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
                com.rkd.audiobasics.data.ArtistPage(artist = artist, popularSongs = popularSongs, albums = albums, singles = singles)
            } catch (e: Exception) { Log.e("Innertube", "getArtistPage error: ${e.message}"); null }
        }

    // ── Search artist by name ─────────────────────────────────────────────────
    suspend fun searchArtistByName(name: String): com.rkd.audiobasics.data.ArtistPage? =
        withContext(Dispatchers.IO) {
            try {
                val all = searchArtists(name)
                val best = all.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: all.firstOrNull()
                    ?: return@withContext null
                getArtistPage(best.id)
            } catch (e: Exception) { Log.e("Innertube", "searchArtistByName error: ${e.message}"); null }
        }

    private fun initNewPipe() {
        if (!newPipeInitialized) { NewPipe.init(NewPipeDownloader); newPipeInitialized = true }
    }

    private fun extractIdFromUrl(url: String): String {
        Regex("v=([a-zA-Z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
        Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
        Regex("list=([a-zA-Z0-9_-]+)").find(url)?.let { return it.groupValues[1] }
        return url.substringAfterLast("/").substringBefore("?")
    }
}

// NewPipe downloader

object NewPipeDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    @Volatile var visitorData: String? = null
    @Volatile var poToken: String? = null

    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val rb = Request.Builder().url(request.url()).header("User-Agent", FIREFOX_UA)
        val hdrs = request.headers() ?: emptyMap()
        hdrs.forEach { (k, v) -> if (!k.equals("User-Agent", ignoreCase = true)) v.forEach { rb.addHeader(k, it) } }
        if (!hdrs.containsKey("Accept-Language")) rb.addHeader("Accept-Language", "en-US,en;q=0.9")
        if (!hdrs.containsKey("Cookie"))
            rb.addHeader("Cookie", "CONSENT=YES+; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
        visitorData?.let { rb.addHeader("X-Visitor-Data", it) }
        if (request.url().contains("/youtubei/v1/player") || request.url().contains("/youtubei/v1/next"))
            poToken?.let { rb.addHeader("X-Goog-Visitor-Id", it) }
        when (request.httpMethod()) {
            "POST" -> rb.post((request.dataToSend() ?: ByteArray(0))
                .toRequestBody("application/json".toMediaTypeOrNull()))
            else -> rb.get()
        }
        val response = client.newCall(rb.build()).execute()
        response.header("X-Visitor-Data")?.let { visitorData = it }
        val body = response.body?.string() ?: ""
        val headers = mutableMapOf<String, MutableList<String>>()
        response.headers.names().forEach { name -> headers[name] = response.headers(name).toMutableList() }
        return Response(response.code, response.message, headers, body, request.url())
    }
}
