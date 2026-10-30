package com.rkd.audiobasics.api.account

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** Result of adding a song to a cloud playlist. [setVideoId] is the new entry's ID, when YouTube gave it. */
internal data class AddedEntry(val setVideoId: String?)

/**
 * Changes to the signed-in account's library. Every call either comes back as a confirmed
 * success or as a specific failure; nothing is ever assumed to have worked. The caller changes
 * the local library only after a success, so the two can't drift apart.
 *
 * All calls block, so callers must be on a background dispatcher.
 */
internal object CloudWriter {

    private const val TAG = "Sync"

    // ── Likes and albums ─────────────────────────────────────────────────────

    fun likeSong(session: AccountSession, videoId: String, like: Boolean): AccountResult<Unit> {
        val endpoint = if (like) "like/like" else "like/removelike"
        val body = JSONObject().put("target", JSONObject().put("videoId", videoId))
        return AccountClient.post(session, endpoint, body).flatMap { json -> acknowledged(json, endpoint) }
    }

    /**
     * Saving an album to the library is "liking" its audio playlist (OLAK5uy_...), not its page.
     * The album ID the app uses is usually the page ID (MPREb_...), so the playlist is looked up first.
     */
    fun likeAlbum(session: AccountSession, albumId: String, like: Boolean): AccountResult<Unit> {
        val playlistId = when (val r = resolveAlbumPlaylistId(session, albumId)) {
            is AccountResult.Success -> r.value
            is AccountResult.SessionExpired -> return r
            is AccountResult.NetworkError -> return r
            is AccountResult.ServerError -> return r
            is AccountResult.ParseError -> return r
        }
        val endpoint = if (like) "like/like" else "like/removelike"
        val body = JSONObject().put("target", JSONObject().put("playlistId", playlistId))
        return AccountClient.post(session, endpoint, body).flatMap { json -> acknowledged(json, endpoint) }
    }

    private fun resolveAlbumPlaylistId(session: AccountSession, albumId: String): AccountResult<String> {
        if (albumId.startsWith("OLAK5uy_")) return AccountResult.Success(albumId)
        val browseId = if (albumId.startsWith("MPREb_") || albumId.startsWith("VL")) albumId else "VL$albumId"
        return AccountClient.post(session, "browse", JSONObject().put("browseId", browseId)).flatMap { json ->
            // The album page lists its own tracks first, and each one points at the album's
            // audio playlist, so the first match is this album's.
            val found = Regex("OLAK5uy_[A-Za-z0-9_-]+").find(json.toString())?.value
            if (found == null) {
                AccountResult.ParseError("Couldn't find this album's playlist on YouTube")
            } else {
                AccountResult.Success(found)
            }
        }
    }

    // ── Playlists ────────────────────────────────────────────────────────────

    /** Creates a PRIVATE playlist and returns its ID. */
    fun createPlaylist(session: AccountSession, title: String): AccountResult<String> {
        val body = JSONObject().put("title", title).put("privacyStatus", "PRIVATE")
        return AccountClient.post(session, "playlist/create", body).flatMap { json ->
            val id = json.optString("playlistId").takeIf { it.isNotBlank() }
            if (id == null) {
                AccountResult.ParseError("YouTube didn't return the new playlist's ID")
            } else {
                AccountResult.Success(id)
            }
        }
    }

    fun deletePlaylist(session: AccountSession, playlistId: String): AccountResult<Unit> {
        val body = JSONObject().put("playlistId", playlistId)
        return AccountClient.post(session, "playlist/delete", body).flatMap { json ->
            acknowledged(json, "playlist/delete")
        }
    }

    fun renamePlaylist(session: AccountSession, playlistId: String, title: String): AccountResult<Unit> {
        val actions = JSONArray().put(
            JSONObject().put("action", "ACTION_SET_PLAYLIST_NAME").put("playlistName", title)
        )
        return editPlaylist(session, playlistId, actions).flatMap { AccountResult.Success(Unit) }
    }

    fun addSong(session: AccountSession, playlistId: String, videoId: String): AccountResult<AddedEntry> {
        val actions = JSONArray().put(
            JSONObject()
                .put("action", "ACTION_ADD_VIDEO")
                .put("addedVideoId", videoId)
                .put("dedupeOption", "DEDUPE_OPTION_SKIP")
        )
        return editPlaylist(session, playlistId, actions).flatMap { json ->
            var entryId: String? = null
            val results = json.optJSONArray("playlistEditResults")
            if (results != null) {
                for (i in 0 until results.length()) {
                    val id = results.optJSONObject(i)?.optJSONObject("playlistEditVideoAddedResultData")
                        ?.optString("setVideoId")?.takeIf { it.isNotBlank() }
                    if (id != null) { entryId = id; break }
                }
            }
            if (entryId == null) {
                // Confirmed, but YouTube didn't say which entry it is (for instance when the song
                // was already there and the add was skipped). Read it back so the local copy has it.
                entryId = lookUpEntryIds(session, playlistId, videoId).firstOrNull()
            }
            AccountResult.Success(AddedEntry(entryId))
        }
    }

    /**
     * Removes [videoId] from the playlist. [knownEntryId] is used when the app has it; otherwise
     * the playlist is read to find the entry. A song that is already gone from the cloud counts
     * as removed, because the goal is "not in the playlist".
     */
    fun removeSong(
        session: AccountSession,
        playlistId: String,
        videoId: String,
        knownEntryId: String?
    ): AccountResult<Unit> {
        val entryIds = if (knownEntryId != null) {
            listOf(knownEntryId)
        } else {
            lookUpEntryIds(session, playlistId, videoId)
        }
        if (entryIds.isEmpty()) {
            Timber.tag(TAG).d("removeSong: $videoId is not in $playlistId on the account; nothing to remove")
            return AccountResult.Success(Unit)
        }
        val actions = JSONArray()
        entryIds.forEach { entryId ->
            actions.put(
                JSONObject()
                    .put("action", "ACTION_REMOVE_VIDEO")
                    .put("removedVideoId", videoId)
                    .put("setVideoId", entryId)
            )
        }
        return editPlaylist(session, playlistId, actions).flatMap { AccountResult.Success(Unit) }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Every entry ID of [videoId] in the playlist, read fresh from the account. */
    private fun lookUpEntryIds(session: AccountSession, playlistId: String, videoId: String): List<String> =
        when (val r = CloudLibrary.fetchPlaylistSongs(session, playlistId)) {
            is AccountResult.Success ->
                r.value.items.filter { it.song.id == videoId }.mapNotNull { it.setVideoId }
            else -> emptyList()
        }

    /** A playlist edit only counts when YouTube says it succeeded. */
    private fun editPlaylist(
        session: AccountSession,
        playlistId: String,
        actions: JSONArray
    ): AccountResult<JSONObject> {
        val body = JSONObject().put("playlistId", playlistId).put("actions", actions)
        return AccountClient.post(session, "browse/edit_playlist", body).flatMap { json ->
            val status = json.optString("status")
            if (status == "STATUS_SUCCEEDED") {
                AccountResult.Success(json)
            } else {
                Timber.tag(TAG).w("edit_playlist was not confirmed (status='$status')")
                AccountResult.ServerError(200, "YouTube didn't confirm the change")
            }
        }
    }

    /** For endpoints that reply with a bare acknowledgement: an `error` object means it failed. */
    private fun acknowledged(json: JSONObject, endpoint: String): AccountResult<Unit> {
        val error = json.optJSONObject("error")
        if (error != null) {
            val code = error.optInt("code", 0)
            Timber.tag(TAG).w("$endpoint was rejected (code $code)")
            return AccountResult.ServerError(code, "YouTube didn't accept the change")
        }
        return AccountResult.Success(Unit)
    }
}
