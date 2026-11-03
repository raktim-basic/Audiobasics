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
    /**
     * True when the account can edit this playlist (rename it, add and remove songs). The library
     * tile's menu only hints at this, so the snapshot fetcher confirms it from the playlist page and
     * stores the answer here.
     */
    val owned: Boolean,
    /** YouTube's built-in lists (Episodes for Later, Watch Later): never editable, never removable. */
    val special: Boolean = false
)

/**
 * A list that arrived COMPLETE. A [CloudList] is only ever produced when every page was
 * fetched without error and the paging ended where YouTube said it ends. Anything less comes
 * back as an [AccountResult] failure instead, so a half-fetched library can never be mistaken
 * for "the cloud holds exactly this".
 *
 * The header's "N songs" is NOT required to match: YouTube counts videos that were taken down
 * or made unavailable, but doesn't return them as rows (e.g. header 242, rows 239). The
 * difference is kept in [advertisedCount] / [rowCount] so it can be shown, not treated as failure.
 */
internal data class CloudList<T>(
    val items: List<T>,
    val pages: Int,
    /** Song count the page header advertised, when it carried one. */
    val advertisedCount: Int?,
    /** Rows the page returned (before any were dropped as unparseable). */
    val rowCount: Int,
    /** Rows that were present but couldn't be turned into items. Never hidden. */
    val skippedRows: Int,
    /** For a playlist page: whether the page says the account can edit it (null if not a playlist page). */
    val editable: Boolean? = null
)

/**
 * Reads the signed-in account's library: liked songs, saved albums, playlists and playlist
 * contents. Read-only. All calls block, so callers must be on a background dispatcher.
 *
 * The rules that make this safe to build a mirror on:
 *  - every page is followed to the end; any page failing fails the whole fetch
 *  - a response whose layout isn't recognised is a ParseError, never an empty list
 *  - a list that stops on a full page while the header says more is treated as cut short
 *    (a smaller shortfall is normal: removed videos are counted by the header but not returned)
 */
internal object CloudLibrary {

    private const val TAG = "Sync"
    private const val MAX_PAGES = 100

    /** YouTube's built-in lists that show up in the library: Episodes for Later and Watch Later. */
    val SPECIAL_LISTS = setOf("SE", "WL")

    /** YouTube returns up to 100 rows per page; a final page this full may have been cut off. */
    private const val FULL_PAGE_ROWS = 100
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
        val advertised: Int?,
        val editable: Boolean? = null
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
        var editable: Boolean? = null
        var token: String? = null
        var pages = 0
        var lastPageRows = 0
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
            var json = when (response) {
                is AccountResult.Success -> response.value
                is AccountResult.SessionExpired -> return response
                is AccountResult.NetworkError -> return response
                is AccountResult.ServerError -> return response
                is AccountResult.ParseError -> return response
            }

            var parsed = if (token == null) parseFirst(json) else parseNext(json)
            // A continuation occasionally arrives as a 200 response containing only
            // responseContext/trackingParams. It is not a valid empty final page: YouTube
            // had already advertised another page. Retry that exact token once before
            // refusing the snapshot, so a transient bare response cannot poison sync.
            if (token != null && parsed is AccountResult.ParseError && isMetadataOnly(json)) {
                Timber.tag(TAG).w("$label: page ${pages + 1} returned metadata only; retrying the same continuation once")
                Thread.sleep(500L)
                val retry = AccountClient.post(session, "browse", JSONObject(), continuation = token)
                when (retry) {
                    is AccountResult.Success -> {
                        json = retry.value
                        parsed = parseNext(json)
                    }
                    is AccountResult.SessionExpired -> return retry
                    is AccountResult.NetworkError -> return retry
                    is AccountResult.ServerError -> return retry
                    is AccountResult.ParseError -> return retry
                }
            }
            // Still a bare envelope after the single retry. Only believed as "the list ended"
            // when the header's count is already met: nothing it promised can be missing.
            // Otherwise the sync aborts, and the counts (never content) go in the log so the
            // cause can be told apart: genuine end, YouTube-side cap, or a request mismatch.
            if (token != null && parsed is AccountResult.ParseError && isMetadataOnly(json)) {
                if (advertised != null && rows >= advertised) {
                    Timber.tag(TAG).d("$label: trailing metadata-only page after $rows rows; header says $advertised, so the list is complete")
                    break
                }
                Timber.tag(TAG).w("$label: page ${pages + 1} was metadata only after $rows rows in $pages pages (header says ${advertised ?: "nothing"}); not applying anything")
                return AccountResult.ParseError("$label: YouTube ended paging after $rows rows, but the header says ${advertised ?: "nothing"}")
            }
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
            lastPageRows = page.rows
            items += page.items
            rows += page.rows
            skipped += page.skipped
            if (pages == 1) {
                advertised = page.advertised
                editable = page.editable
            }
            Timber.tag(TAG).d("$label: page $pages rows=${page.rows} more=${page.continuation != null}")

            val next = page.continuation ?: break
            if (!seenTokens.add(next)) {
                Timber.tag(TAG).w("$label: the same continuation came back twice (not applying anything)")
                return AccountResult.ParseError("Paging loop while reading $label")
            }
            token = next
        }

        if (advertised != null && rows < advertised && lastPageRows >= FULL_PAGE_ROWS) {
            Timber.tag(TAG).w("$label: ended on a full page ($lastPageRows rows) but the header says $advertised and only $rows arrived (not applying anything)")
            return AccountResult.ParseError("$label ended on a full page of $lastPageRows, but the header says $advertised and only $rows arrived")
        }
        if (advertised != null && rows != advertised) {
            Timber.tag(TAG).d("$label: header says $advertised, $rows rows arrived (removed or unavailable videos are counted by the header but not returned)")
        }
        return AccountResult.Success(CloudList(items, pages, advertised, rows, skipped, editable))
    }

    /** A successful HTTP response with no payload beyond response metadata. */
    private fun isMetadataOnly(json: JSONObject): Boolean {
        val keys = json.keys().asSequence().toSet()
        return keys.isNotEmpty() && keys.all { it == "responseContext" || it == "trackingParams" }
    }

    // ─── Song pages (liked songs, playlists) ─────────────────────────────────

    private fun parseSongPage(json: JSONObject, first: Boolean): AccountResult<Page<CloudSong>> {
        val shelf = (if (first) playlistShelf(json) else playlistContinuationShelf(json))
        if (shelf == null) {
            if (!first && isMetadataOnly(json)) {
                return AccountResult.ParseError("YouTube returned a metadata-only continuation page (${shapeOf(json)})")
            }
            // An empty playlist arrives as a song-list shelf that has no "contents" at all. That
            // is only believed when the page header doesn't contradict it; a header that claims
            // songs while the list is missing stays an error (never "empty" by guesswork).
            if (first && hasEmptyPlaylistShelf(json)) {
                val advertised = advertisedSongCount(json)
                if (advertised == null || advertised == 0) {
                    Timber.tag(TAG).d("Empty playlist (song list without contents; header says ${advertised ?: "nothing"})")
                    return AccountResult.Success(
                        Page(emptyList(), 0, 0, null, advertised ?: 0, editable = editableSignal(json))
                    )
                }
                return AccountResult.ParseError("The header says $advertised songs, but the song list came back empty")
            }
            return AccountResult.ParseError("Unrecognised song list layout (${shapeOf(json)})")
        }
        var rows = 0
        var skipped = 0
        val songs = ArrayList<CloudSong>()
        val unknownKinds = LinkedHashSet<String>()
        var unknown = 0
        val rowRenderers = collectObjects(shelf.source, "musicResponsiveListItemRenderer")
        if (rowRenderers.isNotEmpty()) {
            // BitChord walks the selected playlist shelf recursively. YouTube has
            // moved row renderers under extra wrapper objects in some continuation
            // responses, so a direct-child-only scan silently loses those pages.
            for (item in rowRenderers) {
                rows++
                val song = parseSongRow(item)
                if (song == null) skipped++ else songs += song
            }
        } else {
            for (i in 0 until shelf.contents.length()) {
                val entry = shelf.contents.optJSONObject(i) ?: continue
                if (!entry.has("continuationItemRenderer")) {
                    unknown++
                    for (k in entry.keys()) unknownKinds += k
                }
            }
        }
        // Entries that are neither songs nor a "load more" marker mean the layout isn't what we
        // think it is. Reading that as "no songs" would look like an empty library.
        if (rows == 0 && unknown > 0) {
            return AccountResult.ParseError("Song list had $unknown entries in an unrecognised format ($unknownKinds)")
        }
        return AccountResult.Success(
            Page(
                items = songs,
                rows = rows,
                skipped = skipped,
                continuation = nextToken(shelf.source),
                advertised = if (first) advertisedSongCount(json) else null,
                editable = if (first) editableSignal(json) else null
            )
        )
    }

    /**
     * Does this playlist page say the account can edit the playlist? Editable playlists carry an
     * "editable playlist header" and their rows carry "remove from playlist" actions; playlists
     * that are only saved have neither. Either is enough, and which one matched is logged.
     */
    private fun editableSignal(json: JSONObject): Boolean? {
        val raw = json.toString()
        val header = raw.contains("musicEditablePlaylistDetailHeaderRenderer")
        val remove = raw.contains("ACTION_REMOVE_VIDEO")
        // A playlist that is only saved (someone else's) leads with the plain header; an editable
        // one wraps its header in the editable renderer. The plain header is the definite
        // "not yours" answer and beats a stray remove action elsewhere on the page.
        val plain = json.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")?.optJSONObject(0)
            ?.has("musicResponsiveHeaderRenderer") == true
        val verdict: Boolean? = when {
            header -> true
            plain -> false
            remove -> true
            else -> null // unknown: the library tile's hint decides
        }
        Timber.tag(TAG).d("Playlist page editable? header=$header, plain-header=$plain, remove-actions=$remove -> $verdict")
        return verdict
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
        val shelf = if (first) libraryShelf(json) else libraryContinuationShelf(json)
        if (shelf == null) {
            if (!first && isMetadataOnly(json)) {
                return AccountResult.ParseError("YouTube returned a metadata-only library continuation (${shapeOf(json)})")
            }
            // A genuinely empty library has a message instead of a grid. Anything else we don't
            // recognise is NOT treated as empty.
            if (first && hasEmptyMessage(json)) {
                return AccountResult.Success(Page(emptyList(), 0, 0, null, null))
            }
            return AccountResult.ParseError("Unrecognised library layout (${shapeOf(json)})")
        }

        val source = shelf.source
        val gridRows = collectObjects(source, "musicTwoRowItemRenderer")
        val listRows = collectObjects(source, "musicResponsiveListItemRenderer")
        val rows = gridRows.size + listRows.size
        val out = ArrayList<T>(rows)
        gridRows.forEach { renderer -> parseItem(renderer)?.let(out::add) }
        // BitChord's library parser reads both the grid and list presentation modes.
        // Normalize list cards into the two-row card shape consumed by parseAlbum/parsePlaylist.
        listRows.forEach { renderer ->
            responsiveLibraryCard(renderer)?.let(parseItem)?.let(out::add)
        }

        if (rows == 0) {
            var unknown = 0
            val unknownKinds = LinkedHashSet<String>()
            for (i in 0 until shelf.contents.length()) {
                val entry = shelf.contents.optJSONObject(i) ?: continue
                if (!entry.has("continuationItemRenderer")) {
                    unknown++
                    for (key in entry.keys()) unknownKinds += key
                }
            }
            if (unknown > 0) {
                return AccountResult.ParseError("Library had $unknown entries in an unrecognised format ($unknownKinds)")
            }
        }
        return AccountResult.Success(Page(out, rows, 0, nextToken(source), null))
    }

    /** Convert BitChord's musicResponsiveListItemRenderer library card to our card parser shape. */
    private fun responsiveLibraryCard(renderer: JSONObject): JSONObject? {
        val columns = renderer.optJSONArray("flexColumns") ?: return null
        val title = columns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text") ?: return null
        val subtitle = columns.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text") ?: JSONObject()
        return JSONObject()
            .put("navigationEndpoint", renderer.optJSONObject("navigationEndpoint") ?: JSONObject())
            .put("title", title)
            .put("subtitle", subtitle)
            .put("thumbnailRenderer", renderer.optJSONObject("thumbnail") ?: JSONObject())
            .put("menu", renderer.optJSONObject("menu") ?: JSONObject())
            .put("subtitleBadges", renderer.optJSONArray("badges") ?: renderer.optJSONArray("subtitleBadges") ?: JSONArray())
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
        // "Your likes" is the liked-songs list, mirrored separately. Everything else in the library
        // is a playlist to show, including other people's and YouTube's built-in lists.
        if (id == "LM" || id.isBlank()) return null
        val title = r.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optString("text").orEmpty()
        if (title.isBlank()) return null

        // The tile's menu varies between accounts, so an edit item is only a hint that it's editable.
        // The playlist page itself settles it (see CloudSnapshotFetcher).
        val items = r.optJSONObject("menu")?.optJSONObject("menuRenderer")?.optJSONArray("items")
        val icons = ArrayList<String>()
        if (items != null) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                item.optJSONObject("menuNavigationItemRenderer")?.optJSONObject("icon")
                    ?.optString("iconType")?.let { icons += it }
                item.optJSONObject("menuServiceItemRenderer")?.optJSONObject("icon")
                    ?.optString("iconType")?.let { icons += it }
                item.optJSONObject("toggleMenuServiceItemRenderer")?.let { t ->
                    t.optJSONObject("defaultIcon")?.optString("iconType")?.let { icons += "toggle:$it" }
                    t.optJSONObject("toggledIcon")?.optString("iconType")?.let { icons += "toggle:$it" }
                }
            }
        }
        val editHint = icons.any { it == "EDIT" || it == "DELETE" }
        val special = id in SPECIAL_LISTS
        Timber.tag(TAG).d("Playlist tile \"$title\": edit-hint=$editHint, built-in=$special, menu=${icons.joinToString(",")}")
        return CloudPlaylist(id = id, title = title, owned = editHint && !special, special = special)
    }

    // ─── Layout helpers ──────────────────────────────────────────────────────

    /** [source] is the scoped renderer (playlist) or whole response (library) used for deep parsing. */
    private class Shelf(val contents: JSONArray, val source: Any?)

    private fun shelfOf(o: JSONObject, key: String): Shelf? =
        o.optJSONArray(key)?.let { Shelf(it, o) }

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

    /** The first section is a playlist song-list shelf that carries no songs and no "load more". */
    private fun hasEmptyPlaylistShelf(json: JSONObject): Boolean {
        val section = twoColumnSecondarySections(json)?.optJSONObject(0)
            ?: singleColumnSections(json)?.optJSONObject(0)
            ?: return false
        val shelf = section.optJSONObject("musicPlaylistShelfRenderer") ?: return false
        return !shelf.has("contents") && !shelf.has("continuations")
    }

    /** Library pages can lead with other sections (chips, headers), so scan for the grid. */
    private fun libraryShelf(json: JSONObject): Shelf? {
        val sections = singleColumnSections(json)
            ?: json.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
        if (sections != null) {
            for (i in 0 until sections.length()) {
                shelfIn(sections.optJSONObject(i))?.let { return Shelf(it.contents, json) }
            }
        }
        // BitChord parses library cards recursively from the complete browse response. The
        // current account parser's section path is retained above, but a card-bearing response
        // is still valid when YouTube moves the grid outside the tabs/section-list wrapper.
        val hasCards = collectObjects(json, "musicTwoRowItemRenderer").isNotEmpty() ||
            collectObjects(json, "musicResponsiveListItemRenderer").isNotEmpty()
        return if (hasCards) Shelf(JSONArray(), json) else null
    }

    /**
     * Playlist continuation parsing follows BitChord's scope: only the playlist shelf is read.
     * In particular, a `musicShelfContinuation` titled "Suggestions" is not part of the user's
     * playlist and must not be merged into the mirrored track list.
     */
    private fun playlistContinuationShelf(json: JSONObject): Shelf? {
        val cc = json.optJSONObject("continuationContents")
        if (cc != null) {
            collectObjects(cc, "musicPlaylistShelfContinuation").firstOrNull()?.let { continuation ->
                return Shelf(continuation.optJSONArray("contents") ?: JSONArray(), continuation)
            }
            collectObjects(cc, "musicPlaylistShelfRenderer").firstOrNull()?.let { renderer ->
                return Shelf(renderer.optJSONArray("contents") ?: JSONArray(), renderer)
            }
            // Some playlist-shaped responses use musicShelfContinuation instead. Do not
            // confuse an owned playlist's separate Suggestions shelf with its real tracks.
            collectObjects(cc, "musicShelfContinuation").firstOrNull()?.let { continuation ->
                val title = continuation.optJSONObject("header")
                    ?.optJSONObject("musicShelfHeaderRenderer")
                    ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)
                    ?.optString("text").orEmpty()
                val hasPlaylistEntryIds = collectObjects(continuation, "musicResponsiveListItemRenderer")
                    .any { row ->
                        row.optJSONObject("playlistItemData")?.optString("playlistSetVideoId")
                            ?.isNotBlank() == true
                    }
                if (!title.equals("Suggestions", ignoreCase = true) && hasPlaylistEntryIds) {
                    return Shelf(continuation.optJSONArray("contents") ?: JSONArray(), continuation)
                }
            }
        }
        val actions = json.optJSONArray("onResponseReceivedActions")
        if (actions != null) {
            for (i in 0 until actions.length()) {
                val items = actions.optJSONObject(i)?.optJSONObject("appendContinuationItemsAction")
                    ?.optJSONArray("continuationItems") ?: continue
                if (collectObjects(items, "musicResponsiveListItemRenderer").isNotEmpty()) {
                    return Shelf(items, items)
                }
            }
        }
        if (nextToken(json) != null) return Shelf(JSONArray(), json)
        return null
    }

    /** Library feed continuations can return a grid, a section list, or either card layout. */
    private fun libraryContinuationShelf(json: JSONObject): Shelf? {
        val cc = json.optJSONObject("continuationContents")
        if (cc != null) {
            cc.optJSONObject("gridContinuation")?.let { continuation ->
                return Shelf(continuation.optJSONArray("items") ?: JSONArray(), json)
            }
            cc.optJSONObject("sectionListContinuation")?.optJSONArray("contents")?.let { sections ->
                for (i in 0 until sections.length()) {
                    shelfIn(sections.optJSONObject(i))?.let { return Shelf(it.contents, json) }
                }
            }
        }
        val actions = json.optJSONArray("onResponseReceivedActions")
        if (actions != null) {
            for (i in 0 until actions.length()) {
                actions.optJSONObject(i)?.optJSONObject("appendContinuationItemsAction")
                    ?.optJSONArray("continuationItems")?.let { return Shelf(it, json) }
            }
        }
        val hasCards = collectObjects(json, "musicTwoRowItemRenderer").isNotEmpty() ||
            collectObjects(json, "musicResponsiveListItemRenderer").isNotEmpty()
        if (hasCards || nextToken(json) != null) return Shelf(JSONArray(), json)
        return null
    }

    /**
     * The WEB_REMIX parser in BitChord prioritises the current continuation renderer over the
     * legacy `continuations` array, and supports commandExecutorCommand as well as the old direct
     * continuationCommand. The old Audiobasics order could select a stale token when both forms
     * were present, then walk into an empty response several pages later.
     */
    private fun nextToken(source: Any?): String? {
        val renderer = collectObjects(source, "continuationItemRenderer").firstOrNull()
        val endpoint = renderer?.optJSONObject("continuationEndpoint")
        val direct = endpoint?.optJSONObject("continuationCommand")?.optString("token")
            ?.takeIf { it.isNotBlank() }
        if (direct != null) return direct
        val commands = endpoint?.optJSONObject("commandExecutorCommand")?.optJSONArray("commands")
        if (commands != null) {
            for (i in 0 until commands.length()) {
                val token = commands.optJSONObject(i)?.optJSONObject("continuationCommand")
                    ?.optString("token")?.takeIf { it.isNotBlank() }
                if (token != null) return token
            }
        }
        return collectObjects(source, "nextContinuationData").firstOrNull()
            ?.optString("continuation")?.takeIf { it.isNotBlank() }
    }

    /** Depth-first renderer collection, matching BitChord's response traversal. */
    private fun collectObjects(root: Any?, targetKey: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject(targetKey)?.let(out::add)
                    val keys = node.keys()
                    while (keys.hasNext()) walk(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        return out
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
