package com.rkd.audiobasics.utils

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Audiobasics Links — deep links that open a song or album directly inside Audiobasics
 * (falling back to a hosted landing page with "Get Audiobasics" / "Open in YouTube" for
 * people who don't have the app). See AndroidManifest.xml's audiobasics-links intent-filter,
 * which must stay in sync with [HOST] and [PATH_PREFIX] below.
 */
object AudiobasicsLinks {

    const val HOST = "raktim-basic.github.io"
    const val PATH_PREFIX = "/l"

    private const val PREFS_NAME = "ytlite"
    private const val KEY_SHOW_YOUTUBE_SHARE_OPTION = "share_show_youtube_option"

    fun songLink(videoId: String): String = "https://$HOST$PATH_PREFIX/song/$videoId"

    /** [title]/[thumbnail] ride along as query params so the receiving side has a known-good
     *  fallback even when Innertube's own album metadata parsing comes back blank for this
     *  browse id's response shape (seen in practice for OLAK5uy_... playlist-style album ids,
     *  where the title/thumbnail header fields don't parse but the track list still does) —
     *  the sender already has this from [Album] at share time, no reason to make the
     *  receiver re-derive what's already known. */
    fun albumLink(albumId: String, title: String, thumbnail: String, year: String): String {
        val base = "https://$HOST$PATH_PREFIX/album/$albumId"
        val params = buildList {
            if (title.isNotBlank()) add("t=${Uri.encode(title)}")
            if (thumbnail.isNotBlank()) add("th=${Uri.encode(thumbnail)}")
            if (year.isNotBlank()) add("y=${Uri.encode(year)}")
        }
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    /** Parsed result of an incoming Audiobasics Link, or null if the Uri doesn't match one. */
    sealed class ParsedLink {
        data class SongLink(val videoId: String) : ParsedLink()
        data class AlbumLink(
            val albumId: String,
            val title: String,
            val thumbnail: String,
            val year: String
        ) : ParsedLink()
    }

    fun parse(uri: Uri?): ParsedLink? {
        if (uri == null || uri.host != HOST) return null
        val segments = uri.pathSegments // e.g. ["l", "song", "<id>"]
        if (segments.size < 3 || segments[0] != PATH_PREFIX.removePrefix("/")) return null
        val id = segments[2]
        if (id.isBlank()) return null
        return when (segments[1]) {
            "song" -> ParsedLink.SongLink(id)
            "album" -> ParsedLink.AlbumLink(
                albumId = id,
                title = uri.getQueryParameter("t").orEmpty(),
                thumbnail = uri.getQueryParameter("th").orEmpty(),
                year = uri.getQueryParameter("y").orEmpty()
            )
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

    /** Whether Android has already verified (or the user has manually approved via Settings)
     *  this app as the handler for HOST's /l/... links. Only meaningful on API 31+
     *  (DomainVerificationManager) — returns true on older versions since there's nothing to
     *  nudge the user toward there: an unverified link on pre-12 shows a disambiguation
     *  chooser rather than silently opening a browser, so the problem this solves doesn't
     *  really exist in the same form. Fails open (returns true) on any lookup error rather
     *  than risk nagging the user over something we can't actually diagnose. */
    fun isDomainLinkHandlingEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return try {
            val manager = context.getSystemService(DomainVerificationManager::class.java)
            val hostState = manager
                ?.getDomainVerificationUserState(context.packageName)
                ?.hostToStateMap
                ?.get(HOST)
            hostState == DomainVerificationUserState.DOMAIN_STATE_VERIFIED ||
                hostState == DomainVerificationUserState.DOMAIN_STATE_SELECTED
        } catch (e: Exception) {
            true
        }
    }

    /** Deep-links straight to this app's "Open by default" settings screen (API 31+) — the
     *  exact screen with the domain toggle — rather than the generic app-info page. */
    fun openLinkHandlingSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }
}
