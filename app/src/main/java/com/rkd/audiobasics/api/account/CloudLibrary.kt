package com.rkd.audiobasics.api.account

import com.rkd.audiobasics.api.Innertube
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.data.Song
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** A song as the cloud holds it. [setVideoId] is the playlist-entry ID needed to edit it later. */
internal data class CloudSong(val song: Song, val setVideoId: String?)

internal data class CloudPlaylist(
    val id: String,
    val title: String,
    /** True for playlists this account created (editable); false for ones it only saved. */
    val owned: Boolean
)

/**
 * A list that arrived COMPLETE. A [CloudList] is only ever produced when every page was
 * fetched without error and, where the page advertises a size, the numbers add up. Anything
 * less comes back as an [AccountResult] failure instead, so a half-fetched library can never
 * be mistaken for "the cloud holds exactly this".
 */
internal data class CloudList<T>(
    val items: List<T>,
    val pages: Int,
    /** Song count the page header advertised, when it carried one. */
    val advertisedCount: Int?,
    /** Rows the page returned (before any were dropped as unparseable). */
    val rowCount: Int,
    /** Rows that were present but couldn't be turned into items. Never hidden. */
    val skippedRows: Int
)

/**
 * Reads the signed-in account's library: liked songs, saved albums, playlists and playlist
 * contents. Read-only. All calls block, so callers must be on a background dispatcher.
 *
 * The rules that make this safe to build a mirror on:
 *  - every page is followed to the end; any page failing fails the whole fetch
 *  - a response whose layout isn't recognised is a ParseError, never an empty list
 *  - if the header says "N songs" and N rows didn't arrive, the fetch fails
 */
internal object CloudLibrary {

    private const val TAG = "Sync"
    private const val MAX_PAGES = 100
    private const val PAGE_PAUSE_MS = 250L

    fun fetchLikedSongs(session: AccountSession): AccountResult<CloudList<CloudSong>> =
        fetchPaged(
            session, "liked songs", "VLLM",
            parseFirst = { json -> parseSongPage(json, first = true) },
            parseNext = { json -> parseSongPage(json, first = false) }
        )

    fun fetchPlaylistSongs(session: AccountSession, playlistId: String): AccountResult<CloudList<CloudSong>> =
        fetchPaged(
            session, "playlist $playlistId", "VL$playlistId",
            parseFirst = { json -> parseSongPage(json, first = true) },
            parseNext = { json -> parseSongPage(json, first = false) }
        )

    fun fetchSavedAlbums(session: AccountSession): AccountResult<CloudList<Album>> =
        fetchPaged(
            session, "saved albums", "FEmusic_liked_albums",
            parseFirst = { json -> parseGridPage(json, true) { parseAlbum(it) } },
            parseNext = { json -> parseGridPage(json, false) { parseAlbum(it) } }
        )

    fun fetchPlaylists(session: AccountSession): AccountResult<CloudList<CloudPlaylist>> =
        fetchPaged(
            session, "playlists", "FEmusic_liked_playlists",
            parseFirst = { json -> parseGridPage(json, true) { parsePlaylist(it) } },
            parseNext = { json -> parseGridPage(json, false) { parsePlaylist(it) } }
        )

    // ─── Paging core ─────────────────────────────────────────────────────────

    private class Page<T>(
        val items: List<T>,
        val rows: Int,
        val skipped: Int,
        val continuation: String?,
        val advertised: Int?
    )

    private fun <T> fetchPaged(
        session: AccountSession,
        label: String,
        browseId: String,
        parseFirst: (JSONObject) -> AccountResult<Page<T>>,
        parseNext: (JSONObject) -> AccountResult<Page<T>>
    ): AccountResult<CloudList<T>> {
        val items = ArrayList<T>()
        var rows = 0
        var skipped = 0
        var advertised: Int? = null
        var token: String? = null
        var pages = 0
        val seenTokens = HashSet<String>()

        while (true) {
            if (pages >= MAX_PAGES) {
                Timber.tag(TAG).w("$label: gave up after $MAX_PAGES pages (not applying anything)")
                return AccountResult.ParseError("Too many pages while reading $label")
            }
            if (pages > 0) Thread.sleep(PAGE_PAUSE_MS)

            val response = if (token == null) {
                AccountClient.post(session, "browse", JSONObject().put("browseId", browseId))
            } else {
                AccountClient.post(session, "browse", JSONObject(), continuation = token)
            }
            val json = when (response) {
                is AccountResult.Success -> response.value
                is AccountResult.SessionExpired -> return response
                is AccountResult.NetworkError -> return response
                is AccountResult.ServerError -> return response
                is AccountResult.ParseError -> return response
            }

            val parsed = if (token == null) parseFirst(json) else parseNext(json)
            val page = when (parsed) {
                is AccountResult.Success -> parsed.value
                is AccountResult.SessionExpired -> return parsed
                is AccountResult.NetworkError -> return parsed
                is AccountResult.ServerError -> return parsed
                is AccountResult.ParseError -> {
                    Timber.tag(TAG).w("$label: page ${pages + 1} not understood: ${parsed.message}")
                    return parsed
                }
            }

            pages++
            items += page.items
            rows += page.rows
            skipped += page.skipped
            if (pages == 1) advertised = page.advertised
            Timber.tag(TAG).d("$label: page $pages rows=${page.rows} more=${page.continuation != null}")

            val next = page.continuation ?: break
            if (!seenTokens.add(next)) {
                Timber.tag(TAG).w("$label: the same continuation came back twice (not applying anything)")
                return AccountResult.ParseError("Paging loop while reading $label")
            }
            token = next
        }

        if (advertised != null && advertised != rows) {
            Timber.tag(TAG).w("$label: header says $advertised but $rows rows arrived (not applying anything)")
            return AccountResult.ParseError("$label: expected $advertised entries but got $rows")
        }
        return AccountResult.Success(CloudList(items, pages, advertised, rows, skipped))
    }

    // ─── Song pages (liked songs, playlists) ─────────────────────────────────

    private fun parseSongPage(json: JSONObject, first: Boolean): AccountResult<Page<CloudSong>> {
        val shelf = (if (first) playlistShelf(json) else continuationShelf(json))
        if (shelf == null) {
            return AccountResult.ParseError("Unrecognised song list layout (${shapeOf(json)})")
        }
        var rows = 0
        var skipped = 0
        val songs = ArrayList<CloudSong>()
        for (i in 0 until shelf.contents.length()) {
            val item = shelf.contents.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
            rows++
            val song = parseSongRow(item)
            if (song == null) skipped++ else songs += song
        }
        return AccountResult.Success(
            Page(
                items = songs,
                rows = rows,
                skipped = skipped,
                continuation = nextToken(shelf),
                advertised = if (first) advertisedSongCount(json) else null
            )
        )
    }

    private fun parseSongRow(item: JSONObject): CloudSong? {
        val overlayEndpoint = item.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
        val itemData = item.optJSONObject("playlistItemData")

        val videoId = itemData?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: Innertube.extractVideoId(overlayEndpoint)?.takeIf { it.isNotBlank() }
            ?: return null
        val setVideoId = itemData?.optString("playlistSetVideoId")?.takeIf { it.isNotBlank() }
            ?: overlayEndpoint?.optJSONObject("watchEndpoint")?.optString("playlistSetVideoId")
                ?.takeIf { it.isNotBlank() }

        val flex = item.optJSONArray("flexColumns") ?: return null
        fun column(i: Int): JSONObject? =
            flex.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")

        val title = column(0)?.optJSONObject("text")?.optJSONArray("runs")
            ?.optJSONObject(0)?.optString("text").orEmpty()
        if (title.isBlank()) return null

        // Artists: every run of the artist column up to the first "•" separator, exactly the
        // way the search/album parsers read it, so names and channel IDs come out identical.
        var artist = ""
        var artistNames: List<String> = emptyList()
        var artistIds: List<String?> = emptyList()
        val artistRuns = column(1)?.optJSONObject("text")?.optJSONArray("runs")
        if (artistRuns != null) {
            val parts = mutableListOf<String>()
            val runObjs = mutableListOf<JSONObject>()
            for (r in 0 until artistRuns.length()) {
                val run = artistRuns.optJSONObject(r) ?: continue
                val text = run.optString("text", "")
                if (text == " • " || text == "•") break
                if (text.isBlank()) continue
                parts += text
                runObjs += run
            }
            artist = parts.joinToString("")
            val pairs = Innertube.extractArtistNameIdPairsFromRuns(runObjs)
            artistNames = pairs.map { it.first }
            artistIds = pairs.map { it.second }
        }

        // Album: the first run anywhere in the later columns that links to an MPREb_ album page.
        var albumId = ""
        var albumTitle = ""
        scan@ for (c in 1 until flex.length()) {
            val runs = column(c)?.optJSONObject("text")?.optJSONArray("runs") ?: continue
            for (r in 0 until runs.length()) {
                val run = runs.optJSONObject(r) ?: continue
                val id = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    ?.optString("browseId").orEmpty()
                if (id.startsWith("MPREb_")) {
                    albumId = id
                    albumTitle = run.optString("text", "")
                    break@scan
                }
            }
        }

        val thumbs = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbnail = thumbs?.takeIf { it.length() > 0 }
            ?.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.isNotBlank() }
            ?.let { Innertube.upscaleThumbnail(it) }
            ?: Innertube.ytThumbnail(videoId)

        return CloudSong(
            song = Song(
                id = videoId,
                title = title,
                artist = artist,
                thumbnail = thumbnail,
                duration = Innertube.parseDurationMs(item),
                albumId = albumId,
                albumTitle = albumTitle,
                isExplicit = Innertube.parseExplicit(item.optJSONArray("badges")),
                artistNames = artistNames,
                artistIds = artistIds
            ),
            setVideoId = setVideoId
        )
    }

    /** "312 songs" from the page header, when it has one. English only (the client asks for en). */
    private fun advertisedSongCount(json: JSONObject): Int? {
        val tabSection = json.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")?.optJSONObject(0)
        val headers = listOfNotNull(
            tabSection?.optJSONObject("musicResponsiveHeaderRenderer"),
            tabSection?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
                ?.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer"),
            tabSection?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
                ?.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer"),
            json.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer"),
            json.optJSONObject("header")?.optJSONObject("musicHeaderRenderer")
        )
        val pattern = Regex("""^\s*([\d.,]+)\s+(songs?|tracks?)\s*$""", RegexOption.IGNORE_CASE)
        for (header in headers) {
            for (key in listOf("secondSubtitle", "subtitle")) {
                val runs = header.optJSONObject(key)?.optJSONArray("runs") ?: continue
                for (r in 0 until runs.length()) {
                    val text = runs.optJSONObject(r)?.optString("text").orEmpty()
                    val match = pattern.find(text) ?: continue
                    match.groupValues[1].filter { it.isDigit() }.toIntOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    // ─── Library grids (albums, playlists) ───────────────────────────────────

    private fun <T> parseGridPage(
        json: JSONObject,
        first: Boolean,
        parseItem: (JSONObject) -> T?
    ): AccountResult<Page<T>> {
        val shelf = if (first) libraryShelf(json) else continuationShelf(json)
        if (shelf == null) {
            // A genuinely empty library has a message instead of a grid. Anything else we don't
            // recognise is NOT treated as empty.
            if (first && hasEmptyMessage(json)) {
                return AccountResult.Success(Page(emptyList(), 0, 0, null, null))
            }
            return AccountResult.ParseError("Unrecognised library layout (${shapeOf(json)})")
        }
        var rows = 0
        val out = ArrayList<T>()
        for (i in 0 until shelf.contents.length()) {
            val renderer = shelf.contents.optJSONObject(i)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
            rows++
            parseItem(renderer)?.let { out += it }
        }
        return AccountResult.Success(Page(out, rows, 0, nextToken(shelf), null))
    }

    private fun parseAlbum(r: JSONObject): Album? {
        val browseId = r.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?.optString("browseId").orEmpty()
        if (!browseId.startsWith("MPREb_")) return null
        val title = r.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optString("text").orEmpty()
        if (title.isBlank()) return null

        // Subtitle is "<type> • <artist(s)> • <year>"; the type segment is optional.
        val runs = r.optJSONObject("subtitle")?.optJSONArray("runs")
        val segments = mutableListOf(mutableListOf<JSONObject>())
        if (runs != null) {
            for (i in 0 until runs.length()) {
                val run = runs.optJSONObject(i) ?: continue
                val text = run.optString("text", "")
                if (text.trim() == "•") { segments.add(mutableListOf()); continue }
                if (text.isNotBlank()) segments.last().add(run)
            }
        }
        val typeWords = setOf("album", "single", "ep")
        if (segments.size > 1 && segments[0].size == 1 &&
            segments[0][0].optString("text").trim().lowercase() in typeWords
        ) {
            segments.removeAt(0)
        }
        val artistRuns = segments.firstOrNull().orEmpty()
        val year = segments.drop(1).flatten()
            .map { it.optString("text").trim() }
            .firstOrNull { it.length == 4 && it.all(Char::isDigit) }.orEmpty()

        val pairs = Innertube.extractArtistNameIdPairsFromRuns(artistRuns)
        val thumbs = r.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbnail = thumbs?.takeIf { it.length() > 0 }
            ?.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
            .let { Innertube.upscaleThumbnail(it) }

        return Album(
            id = browseId,
            title = title,
            artist = artistRuns.joinToString("") { it.optString("text") }.trim(),
            thumbnail = thumbnail,
            year = year,
            artistNames = pairs.map { it.first },
            artistIds = pairs.mapNotNull { (name, id) -> id?.let { name to it } }.toMap(),
            isExplicit = Innertube.parseExplicit(r.optJSONArray("subtitleBadges"))
        )
    }

    private fun parsePlaylist(r: JSONObject): CloudPlaylist? {
        val browseId = r.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?.optString("browseId").orEmpty()
        if (!browseId.startsWith("VL")) return null
        val id = browseId.removePrefix("VL")
        // "Your likes" and "Episodes for later" are built-in lists, not playlists to mirror.
        if (id == "LM" || id == "SE" || id.isBlank()) return null
        val title = r.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optString("text").orEmpty()
        if (title.isBlank()) return null

        val items = r.optJSONObject("menu")?.optJSONObject("menuRenderer")?.optJSONArray("items")
        var owned = false
        if (items != null) {
            for (i in 0 until items.length()) {
                val icon = items.optJSONObject(i)?.optJSONObject("menuNavigationItemRenderer")
                    ?.optJSONObject("icon")?.optString("iconType")
                if (icon == "EDIT") { owned = true; break }
            }
        }
        return CloudPlaylist(id = id, title = title, owned = owned)
    }

    // ─── Layout helpers ──────────────────────────────────────────────────────

    private class Shelf(val contents: JSONArray, val continuations: JSONArray?)

    private fun shelfOf(o: JSONObject, key: String): Shelf? =
        o.optJSONArray(key)?.let { Shelf(it, o.optJSONArray("continuations")) }

    private fun shelfIn(section: JSONObject?): Shelf? {
        section ?: return null
        section.optJSONObject("musicPlaylistShelfRenderer")?.let { shelfOf(it, "contents")?.let { s -> return s } }
        section.optJSONObject("musicShelfRenderer")?.let { shelfOf(it, "contents")?.let { s -> return s } }
        section.optJSONObject("gridRenderer")?.let { shelfOf(it, "items")?.let { s -> return s } }
        section.optJSONObject("itemSectionRenderer")?.optJSONArray("contents")?.let { inner ->
            for (i in 0 until inner.length()) shelfIn(inner.optJSONObject(i))?.let { return it }
        }
        return null
    }

    private fun singleColumnSections(json: JSONObject): JSONArray? =
        json.optJSONObject("contents")?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")

    private fun twoColumnSecondarySections(json: JSONObject): JSONArray? =
        json.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONObject("secondaryContents")?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")

    /**
     * Only the FIRST section of a playlist page is the song list. Editable playlists carry a
     * "Suggestions" shelf after it, which must never be mistaken for the playlist's songs.
     */
    private fun playlistShelf(json: JSONObject): Shelf? {
        twoColumnSecondarySections(json)?.let { sections ->
            shelfIn(sections.optJSONObject(0))?.let { return it }
        }
        singleColumnSections(json)?.let { sections ->
            shelfIn(sections.optJSONObject(0))?.let { return it }
        }
        return null
    }

    /** Library pages can lead with other sections (chips, headers), so scan for the grid. */
    private fun libraryShelf(json: JSONObject): Shelf? {
        val sections = singleColumnSections(json)
            ?: json.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            ?: return null
        for (i in 0 until sections.length()) shelfIn(sections.optJSONObject(i))?.let { return it }
        return null
    }

    private fun continuationShelf(json: JSONObject): Shelf? {
        val cc = json.optJSONObject("continuationContents")
        if (cc != null) {
            for (key in listOf("musicPlaylistShelfContinuation", "musicShelfContinuation")) {
                cc.optJSONObject(key)?.let { shelfOf(it, "contents")?.let { s -> return s } }
            }
            cc.optJSONObject("gridContinuation")?.let { shelfOf(it, "items")?.let { s -> return s } }
            cc.optJSONObject("sectionListContinuation")?.optJSONArray("contents")?.let { sections ->
                for (i in 0 until sections.length()) shelfIn(sections.optJSONObject(i))?.let { return it }
            }
        }
        val actions = json.optJSONArray("onResponseReceivedActions")
        if (actions != null) {
            for (i in 0 until actions.length()) {
                actions.optJSONObject(i)?.optJSONObject("appendContinuationItemsAction")
                    ?.optJSONArray("continuationItems")?.let { return Shelf(it, null) }
            }
        }
        return null
    }

    private fun nextToken(shelf: Shelf): String? {
        shelf.continuations?.let { list ->
            for (i in 0 until list.length()) {
                list.optJSONObject(i)?.optJSONObject("nextContinuationData")?.optString("continuation")
                    ?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        for (i in 0 until shelf.contents.length()) {
            shelf.contents.optJSONObject(i)?.optJSONObject("continuationItemRenderer")
                ?.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")
                ?.optString("token")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun hasEmptyMessage(json: JSONObject): Boolean {
        val sections = singleColumnSections(json) ?: return false
        for (i in 0 until sections.length()) {
            val inner = sections.optJSONObject(i)?.optJSONObject("itemSectionRenderer")
                ?.optJSONArray("contents") ?: continue
            for (j in 0 until inner.length()) {
                if (inner.optJSONObject(j)?.has("messageRenderer") == true) return true
            }
        }
        return false
    }

    /** Key names only (never values), to make an unrecognised layout diagnosable from the log. */
    private fun shapeOf(json: JSONObject): String {
        fun keys(o: JSONObject?): String = o?.keys()?.asSequence()?.toList()?.sorted()?.joinToString(",") ?: "-"
        val parts = mutableListOf("top=[${keys(json)}]")
        json.optJSONObject("contents")?.let { parts += "contents=[${keys(it)}]" }
        val sections = singleColumnSections(json) ?: twoColumnSecondarySections(json)
        if (sections != null) {
            val described = (0 until minOf(sections.length(), 4))
                .joinToString(" | ") { i -> "[${keys(sections.optJSONObject(i))}]" }
            parts += "sections=${sections.length()}: $described"
        }
        return parts.joinToString("; ")
    }
}
