package com.rkd.audiobasics.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    // ── Playlists ──────────────────────────────────────────────

    @Query("SELECT * FROM playlists ORDER BY sortOrder ASC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY sortOrder ASC")
    suspend fun getPlaylists(): List<PlaylistEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM playlists")
    suspend fun getMaxSortOrder(): Int

    // Lowest sortOrder in use (null when there are no playlists). A new playlist takes one less
    // than this so it sorts first under "ORDER BY sortOrder ASC", without touching any existing row.
    @Query("SELECT MIN(sortOrder) FROM playlists")
    suspend fun getMinSortOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    @Query("UPDATE playlists SET name = :name, emoji = :emoji WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: String, name: String, emoji: String)

    @Query("UPDATE playlists SET sortOrder = :order WHERE id = :playlistId")
    suspend fun updateSortOrder(playlistId: String, order: Int)

    // Persists a full manual reorder: each id's new position becomes its sortOrder.
    @Transaction
    suspend fun reorderPlaylists(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id -> updateSortOrder(id, index) }
    }

    // ── Playlist Songs ─────────────────────────────────────────

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    fun observePlaylistSongs(playlistId: String): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    suspend fun getPlaylistSongs(playlistId: String): List<PlaylistSongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: PlaylistSongEntity)

    // Lowest addedAt in a playlist (null when it is empty). A newly added song takes one less than
    // this so it sorts first under "ORDER BY addedAt ASC", without touching any existing row.
    @Query("SELECT MIN(addedAt) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getMinAddedAt(playlistId: String): Long?

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: String, songId: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: String)

    @Query("UPDATE playlist_songs SET addedAt = :order WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun updateSongOrder(playlistId: String, songId: String, order: Long)

    @Transaction
    suspend fun reorderPlaylistSongs(playlistId: String, orderedSongIds: List<String>) {
        orderedSongIds.forEachIndexed { index, songId ->
            updateSongOrder(playlistId, songId, index.toLong())
        }
    }

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId)")
    suspend fun isSongInPlaylist(playlistId: String, songId: String): Boolean

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    fun observeSongCount(playlistId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE songId = :songId")
    suspend fun countPlaylistsContainingSong(songId: String): Int

    @Query("SELECT DISTINCT songId FROM playlist_songs")
    suspend fun getAllPlaylistSongIds(): List<String>

    // Updates resolved metadata on every playlist row for this song while preserving each
    // row's playlist membership and addedAt ordering.
    @Query("UPDATE playlist_songs SET title = :title, artist = :artist, thumbnail = :thumbnail, isExplicit = :isExplicit, albumId = :albumId, duration = :duration, artistNames = :artistNames, artistIds = :artistIds WHERE songId = :songId")
    suspend fun updateSongMetadata(
        songId: String,
        title: String,
        artist: String,
        thumbnail: String,
        isExplicit: Boolean,
        albumId: String,
        duration: Long,
        artistNames: String,
        artistIds: String
    )

    // Writes corrected artist info onto every playlist row for this song (a song can sit in
    // several playlists, each with its own row).
    @Query("UPDATE playlist_songs SET artistNames = :artistNames, artistIds = :artistIds WHERE songId = :songId")
    suspend fun updateSongArtists(songId: String, artistNames: String, artistIds: String)

    @Query("SELECT * FROM playlist_songs GROUP BY songId")
    suspend fun getAllPlaylistSongs(): List<PlaylistSongEntity>
}
