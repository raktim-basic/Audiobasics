package com.rkd.audiobasics.utils

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Audiobasics Links — deep links that open a song or album directly inside Audiobasics
 * (falling back to a hosted landing page with "Get Audiobasics" / "Open in YouTube" for
 * people who don't have the app). See AndroidManifest.xml's audiobasics-links intent-filter,
 * which must stay in sync with [HOST] and [PATH_PREFIX] below.
 */
object AudiobasicsLinks {

    // TODO — placeholder. Replace with the real hosted domain once GitHub Pages is set up
    // (see AUDIOBASICS_LINKS_SETUP.md), and update AndroidManifest.xml's android:host to match.
    const val HOST = "PLACEHOLDER.audiobasics.example"
    const val PATH_PREFIX = "/l"

    private const val PREFS_NAME = "ytlite"
    private const val KEY_SHOW_YOUTUBE_SHARE_OPTION = "share_show_youtube_option"

    fun songLink(videoId: String): String = "https://$HOST$PATH_PREFIX/song/$videoId"
    fun albumLink(albumId: String): String = "https://$HOST$PATH_PREFIX/album/$albumId"

    /** Parsed result of an incoming Audiobasics Link, or null if the Uri doesn't match one. */
    sealed class ParsedLink {
        data class SongLink(val videoId: String) : ParsedLink()
        data class AlbumLink(val albumId: String) : ParsedLink()
    }

    fun parse(uri: Uri?): ParsedLink? {
        if (uri == null || uri.host != HOST) return null
        val segments = uri.pathSegments // e.g. ["l", "song", "<id>"]
        if (segments.size < 3 || segments[0] != PATH_PREFIX.removePrefix("/")) return null
        val id = segments[2]
        if (id.isBlank()) return null
        return when (segments[1]) {
            "song" -> ParsedLink.SongLink(id)
            "album" -> ParsedLink.AlbumLink(id)
            else -> null
        }
    }

    /** Temporary dev-tools-only setting (see MusicViewModel.shareYoutubeLinkEnabled) — reading
     *  it straight from SharedPreferences here avoids threading a ViewModel flow through every
     *  share call site (SongItem, PlayerDialog, AlbumScreen). Off by default: only the
     *  Audiobasics Link is offered on share unless the user has turned this on. */
    fun isYoutubeShareOptionEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_YOUTUBE_SHARE_OPTION, false)

    fun shareText(context: Context, text: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}
