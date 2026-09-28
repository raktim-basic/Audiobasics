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
    // Added in schema v4 (MIGRATION_3_4) to fix artist-tap navigation failing/mis-resolving
    // for custom-playlist songs (this table previously carried neither field at all — a worse
    // version of the same bug already fixed for liked songs/saved-album songs). JSON-encoded
    // via Song.encodeArtistNames/encodeArtistIds since Room has no native List<String> column
    // type here (no TypeConverter registered on this DB) — decode with Song.decodeArtistNames/
    // decodeArtistIds when converting back to a Song (see MusicViewModel.PlaylistSongEntity.toSong()).
    val artistNamesJson: String = "[]",
    val artistIdsJson: String = "[]"
)
