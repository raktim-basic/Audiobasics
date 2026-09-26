package com.rkd.audiobasics.navigation

import androidx.navigation3.runtime.NavKey
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.data.db.PlaylistEntity
import kotlinx.serialization.Serializable

@Serializable
data object HomeKey : NavKey

@Serializable
data object SearchKey : NavKey

@Serializable
data object QueueKey : NavKey

@Serializable
data class SettingsKey(val openCache: Boolean = false, val openLibrary: Boolean = false) : NavKey

@Serializable
data object LikedKey : NavKey

@Serializable
data object AlbumsKey : NavKey

@Serializable
data object LibraryKey : NavKey

@Serializable
data object UpdaterKey : NavKey

@Serializable
data object EngineInfoKey : NavKey

@Serializable
data class AlbumDetailKey(val album: Album) : NavKey

@Serializable
data class ArtistDetailKey(val artistName: String, val artistBrowseId: String = "") : NavKey

@Serializable
data class SearchAlbumsKey(
    val query: String,
    // When this search was triggered by tapping an album from Song Info, these carry the
    // song's own album title/artist so SearchAlbumsScreen can auto-open a confident first-result
    // match instead of always showing the results list. Blank for every other entry point
    // (Search tab's Albums search, etc.), which disables the auto-match check entirely.
    val expectedTitle: String = "",
    val expectedArtist: String = ""
) : NavKey

@Serializable
data class SearchArtistsKey(val query: String) : NavKey

@Serializable
data class CustomPlaylistKey(val playlist: PlaylistEntity) : NavKey
