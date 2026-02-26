package com.yt.lite.data

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnail: String,
    val duration: Long = 0L,
    val songCount: Int = 0,
    val youtubeUrl: String = ""
)
