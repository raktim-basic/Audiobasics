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
    val sortOrder: Int = 0,
    // YouTube playlist ID when this playlist is linked to the signed-in account (null = local only).
    // Added in DB v5.
    val cloudId: String? = null
)
