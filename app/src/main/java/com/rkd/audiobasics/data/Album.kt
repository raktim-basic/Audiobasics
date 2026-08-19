package com.rkd.audiobasics.data

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val duration: Long = 0L,
    val songCount: Int = 0,
    val youtubeUrl: String = "",
    val year: String = "",
    // The individual, correctly-separated album-artist names, in display order — e.g.
    // ["¥$", "Kanye West", "Ty Dolla $ign"] as three distinct entries. Populated directly from
    // the tracklist's structured per-song artistNames (real run boundaries from the response,
    // no string-splitting involved), so a name containing its own comma or ampersand (or two
    // co-artist names joined by a bare comma, like "¥$, Kanye West") comes out right either
    // way. UI code should read this list directly for anything that needs individual artist
    // names/links — re-splitting the [artist] display string with splitArtistNames() is lossy
    // and can silently re-merge names that were already correctly separated here.
    val artistNames: List<String> = emptyList(),
    // Each name in [artistNames]' own YTM channel browseId, keyed by name — populated only for
    // names that appear (with the same id) on every track of the album, captured directly from
    // the tracklist's response data rather than re-derived later via a name search. Lets
    // tapping an artist credited here open the exact channel that name links to, instead of
    // risking a name search landing on a different, related artist (e.g. tapping "Kanye West"
    // on an album he shares credit with "¥$" on).
    val artistIds: Map<String, String> = emptyMap()
)
