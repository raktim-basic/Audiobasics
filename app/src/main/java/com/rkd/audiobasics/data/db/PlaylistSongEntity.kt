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
    val addedAt: Long = System.currentTimeMillis()
)
