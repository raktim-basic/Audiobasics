package com.yt.lite.data

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val isAlbum: Boolean = false,
    val albumId: String? = null,
    val duration: Long = 0L,
    val isCached: Boolean = false,
    val cacheFailed: Boolean = false
)
