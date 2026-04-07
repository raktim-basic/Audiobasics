package com.yt.lite.lyrics

data class LyricsLine(
    val text: String,
    val timeMs: Long
)

data class LyricsResult(
    val syncedLines: List<LyricsLine>,
    val plainText: String
) {
    val hasSynced: Boolean get() = syncedLines.isNotEmpty()
}
