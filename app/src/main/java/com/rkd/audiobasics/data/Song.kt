package com.rkd.audiobasics.data

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
}
