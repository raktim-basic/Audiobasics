package com.rkd.audiobasics.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.rkd.audiobasics.data.Song

/**
 * Opens the Song info popup for a song. Provided once by MainActivity (next to LocalMorphOverlay)
 * so any song menu — search, playlists, albums, artist, queue, Smart Inputs — can offer
 * "Song info" without every screen having to thread a callback through.
 */
val LocalSongInfo = staticCompositionLocalOf<(Song) -> Unit> { {} }

/**
 * Song info as a standalone morph popup (the player still shows it as the back face of its flip
 * card). Same content either way — [SongInfoCardContent]; here its Back button just closes the
 * popup, and tapping an artist/album closes it and navigates, like in the player.
 */
@Composable
fun SongInfoPopup(
    song: Song,
    vm: MusicViewModel,
    isDarkMode: Boolean,
    context: Context,
    onDismiss: () -> Unit,
    onNavigateArtist: (String, String?) -> Unit,
    onNavigateAlbum: (query: String, expectedTitle: String, expectedArtist: String) -> Unit
) {
    val savedAlbums by vm.savedAlbums.collectAsState()
    val resolvedAlbumCache by vm.resolvedAlbumCache.collectAsState()
    val hapticsEnabled by vm.hapticsEnabled.collectAsState()
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)

    MorphPopup(
        onDismissRequest = onDismiss,
        placement = MorphPlacement.Center,
        containerColor = bgColor
    ) { close ->
        SongInfoCardContent(
            song = song,
            isDarkMode = isDarkMode,
            context = context,
            savedAlbums = savedAlbums,
            resolvedAlbumCache = resolvedAlbumCache,
            onCacheResolvedAlbum = { vm.cacheResolvedAlbum(it) },
            onDurationResolved = { id, durationMs -> vm.persistResolvedSongDuration(id, durationMs) },
            hapticsEnabled = hapticsEnabled,
            onDismiss = close,
            onArtistClick = { name, id ->
                close()
                onNavigateArtist(name, id)
            },
            onAlbumClick = { query, expectedTitle, expectedArtist ->
                close()
                onNavigateAlbum(query, expectedTitle, expectedArtist)
            }
        )
    }
}
