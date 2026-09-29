package com.rkd.audiobasics.data

import org.json.JSONArray

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val duration: Long = 0L,
    val isAlbum: Boolean = false,
    val albumId: String = "",
    val albumTitle: String = "",
    val isCached: Boolean = false,
    val cacheFailed: Boolean = false,
    val isExplicit: Boolean = false,
    val year: String = "",
    // Individual artist names, parsed directly from YouTube's response structure (each name
    // is one "run" in the original data) rather than guessed by splitting the [artist] display
    // string on commas — this is what lets artist names that contain a comma or ampersand
    // themselves (e.g. "Tyler, The Creator") stay intact while genuinely distinct co-artists
    // still get split correctly. Falls back to [artist] as a single-element list if unset.
    val artistNames: List<String> = emptyList(),
    // Each artist's own YTM channel browseId, positionally matched to [artistNames] (same
    // index = same artist; null at a position means that run wasn't a clickable artist link).
    // Captured directly from the response instead of re-derived later via a name search, which
    // is how two distinct artists with a similar/related search-relevant name (e.g. "Kanye
    // West" and "¥$") could end up resolving to the wrong one's page.
    val artistIds: List<String?> = emptyList()
) {
    /** Individual artist names for this song — prefer the structured list; fall back to
     *  treating the whole display string as one artist if it wasn't populated. */
    val resolvedArtistNames: List<String>
        get() = artistNames.ifEmpty { listOf(artist).filter { it.isNotBlank() } }

    /** The browseId for a given artist name on this song, if we captured one directly from
     *  the response — positionally matched against [artistNames]. Null if unknown. */
    fun artistIdFor(name: String): String? {
        val idx = artistNames.indexOfFirst { it.equals(name, ignoreCase = true) }
        return if (idx in artistIds.indices) artistIds[idx] else null
    }

    companion object {
        // Shared JSON encode/decode for artistNames/artistIds, used by every place a Song gets
        // round-tripped through a String-only store (SharedPreferences JSON, Room columns).
        // Centralized here after the same "artistIds silently dropped on the way back out"
        // bug turned up in three separate persistence spots (liked songs, saved-album-song
        // cache, custom playlist songs) — see MusicViewModel's save/load functions and
        // PlaylistSongEntity. artistIds' nulls (an artist run with no clickable channel link)
        // aren't representable in a plain JSON string array, so "" is used as the null sentinel
        // on the way out and mapped back to null on the way in.
        fun encodeArtistNames(names: List<String>): String = JSONArray(names).toString()

        fun decodeArtistNames(json: String): List<String> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }

        fun encodeArtistIds(ids: List<String?>): String = JSONArray(ids.map { it ?: "" }).toString()

        fun decodeArtistIds(json: String): List<String?> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i -> arr.optString(i, "").ifBlank { null } }
        } catch (_: Exception) { emptyList() }
    }
}
