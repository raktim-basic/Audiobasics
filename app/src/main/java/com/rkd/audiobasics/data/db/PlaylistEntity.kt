package com.rkd.audiobasics.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
)
