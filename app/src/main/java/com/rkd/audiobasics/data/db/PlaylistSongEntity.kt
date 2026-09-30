package com.rkd.audiobasics.data.db

import androidx.room.Entity

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"]
)
data class PlaylistSongEntity(
    val playlistId: String,
    val songId: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val isExplicit: Boolean = false,
    val albumId: String = "",
    val duration: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    // JSON string arrays, encoded/decoded via Song.encodeArtistNames/decodeArtistNames (and
    // the ...Ids pair). Added in DB v4 so artist-tap can use real YTM channel IDs for
    // custom-playlist songs instead of falling back to a name search.
    val artistNames: String = "[]",
    val artistIds: String = "[]"
)
