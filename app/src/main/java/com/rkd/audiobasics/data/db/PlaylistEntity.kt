package com.rkd.audiobasics.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    val createdAt: Long = System.currentTimeMillis()
)
