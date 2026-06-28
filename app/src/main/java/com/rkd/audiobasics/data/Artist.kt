package com.rkd.audiobasics.data

data class Artist(
    val id: String,      // YTM browseId e.g. "UCxxxxxx"
    val name: String,
    val thumbnail: String
)

data class ArtistPage(
    val artist: Artist,
    val popularSongs: List<Song>,
    val albums: List<Album>,
    val singles: List<Album>   // Singles & EPs — reuse Album model (same shape)
)
