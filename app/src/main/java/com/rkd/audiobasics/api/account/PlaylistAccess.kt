package com.rkd.audiobasics.api.account

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which linked playlists the account does NOT let this user edit, by cloud playlist ID.
 *
 *  - read-only: playlists someone else made (saved to the library) and YouTube's built-in lists.
 *    They are shown and played like any other, but can't be renamed, can't have songs added or
 *    removed, and "Delete" turns into "Remove from library" (or isn't offered for built-in lists).
 *  - special: the built-in lists (Episodes for Later, Watch Later), which can't be removed at all.
 *
 * Refreshed by every library refresh. A playlist that isn't listed is treated as editable (local
 * playlists, and anything not refreshed yet), and YouTube still refuses an edit that isn't allowed.
 */
internal object PlaylistAccess {

    private const val PREFS = "playlist_access"

    class Flags(val readOnly: Set<String>, val special: Set<String>) {
        fun isReadOnly(cloudId: String?): Boolean = cloudId != null && cloudId in readOnly
        fun isSpecial(cloudId: String?): Boolean = cloudId != null && cloudId in special
    }

    private val _flags = MutableStateFlow(Flags(emptySet(), emptySet()))
    val flags: StateFlow<Flags> = _flags.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _flags.value = Flags(
            readOnly = p.getStringSet("readOnly", null).orEmpty().toSet(),
            special = p.getStringSet("special", null).orEmpty().toSet()
        )
    }

    fun update(readOnly: Set<String>, special: Set<String>) {
        _flags.value = Flags(readOnly, special)
        prefs?.edit()?.putStringSet("readOnly", readOnly)?.putStringSet("special", special)?.apply()
    }

    fun clear() = update(emptySet(), emptySet())

    /** From a freshly read account: everything the account can't edit. */
    fun updateFrom(snapshot: CloudSnapshot) {
        val previous = _flags.value
        val preserved = snapshot.preservedPlaylistIds
        val readOnly = snapshot.playlists.filter { !it.playlist.owned }.map { it.playlist.id }.toSet() +
            previous.readOnly.intersect(preserved)
        val special = snapshot.playlists.filter { it.playlist.special }.map { it.playlist.id }.toSet() +
            previous.special.intersect(preserved)
        update(readOnly, special)
    }
}
