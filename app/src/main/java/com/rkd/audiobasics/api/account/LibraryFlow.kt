package com.rkd.audiobasics.api.account

import com.rkd.audiobasics.data.Album
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

// ─── Data ────────────────────────────────────────────────────────────────────

internal data class CloudPlaylistData(val playlist: CloudPlaylist, val songs: List<CloudSong>)

/** The whole account library, fetched completely. Never partial. */
internal data class CloudSnapshot(
    val liked: List<CloudSong>,
    val albums: List<Album>,
    val playlists: List<CloudPlaylistData>
) {
    val counts: LibraryCounts
        get() = LibraryCounts(liked.size, albums.size, playlists.size, playlists.sumOf { it.songs.size })
    val isEmpty: Boolean get() = counts.isEmpty
}

/** "Empty" means no liked songs, no saved albums and no playlists (the sync scope). */
internal data class LibraryCounts(val liked: Int, val albums: Int, val playlists: Int, val playlistSongs: Int) {
    val isEmpty: Boolean get() = liked == 0 && albums == 0 && playlists == 0
}

/** What the flow needs from this device's library. Implemented on top of the ViewModel. */
internal interface LocalLibraryStore {
    suspend fun counts(): LibraryCounts

    /**
     * Makes this device's library match [snapshot]. When [wipeLocalOnly] is true, playlists that
     * were never linked to an account are removed too (the user confirmed a full replace).
     * Must be all-or-nothing for the playlists.
     */
    suspend fun replaceWithCloud(snapshot: CloudSnapshot, wipeLocalOnly: Boolean)

    suspend fun clearAll()

    /** Turns linked playlists into plain local ones. */
    suspend fun detachFromCloud()
}

// ─── Fetching ────────────────────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun <B> AccountResult<*>.asFailure(): AccountResult<B> = when (this) {
    is AccountResult.Success -> error("asFailure() called on a success")
    else -> this as AccountResult<B>
}

internal object CloudSnapshotFetcher {
    private const val STEP_PAUSE_MS = 400L

    /** Reads everything, or fails as a whole. A part that can't be read fails the snapshot. */
    fun fetch(session: AccountSession): AccountResult<CloudSnapshot> {
        val liked = when (val r = CloudLibrary.fetchLikedSongs(session)) {
            is AccountResult.Success -> r.value.items.distinctBy { it.song.id }
            else -> return r.asFailure()
        }
        Thread.sleep(STEP_PAUSE_MS)

        val albums = when (val r = CloudLibrary.fetchSavedAlbums(session)) {
            is AccountResult.Success -> r.value.items.distinctBy { it.id }
            else -> return r.asFailure()
        }
        Thread.sleep(STEP_PAUSE_MS)

        val owned = when (val r = CloudLibrary.fetchPlaylists(session)) {
            is AccountResult.Success -> r.value.items.filter { it.owned }.distinctBy { it.id }
            else -> return r.asFailure()
        }

        val playlists = ArrayList<CloudPlaylistData>(owned.size)
        for (p in owned) {
            Thread.sleep(STEP_PAUSE_MS)
            val songs = when (val r = CloudLibrary.fetchPlaylistSongs(session, p.id)) {
                is AccountResult.Success -> r.value.items.distinctBy { it.song.id }
                else -> return r.asFailure()
            }
            playlists += CloudPlaylistData(p, songs)
        }
        return AccountResult.Success(CloudSnapshot(liked, albums, playlists))
    }
}

// ─── Flow state ──────────────────────────────────────────────────────────────

internal sealed interface LibraryFlowState {
    data object Idle : LibraryFlowState
    data class Working(val message: String) : LibraryFlowState

    /** Both sides have data. Signing in would replace the local library: ask first. */
    data class ConfirmOverride(val local: LibraryCounts, val cloud: LibraryCounts) : LibraryFlowState

    /** This device has a library and the account is empty: empty it here, or (later) upload it. */
    data class ChooseForEmptyAccount(val local: LibraryCounts) : LibraryFlowState

    /** Nothing was changed. */
    data class Failed(val message: String) : LibraryFlowState
    data class Done(val message: String) : LibraryFlowState
}

/**
 * The rules for how this device's library and the account fit together.
 *
 *   local empty                          -> just take the account's library, no questions
 *   local has data, account empty        -> choose: empty the local library, or upload it
 *   both have data                       -> warn: the account replaces the local library
 *
 * Nothing is applied until every part of the account library has been read completely, and
 * a failure at any point leaves this device exactly as it was.
 */
internal object LibraryFlow {

    /** Uploading a local library to an empty account is not built yet. */
    const val UPLOAD_AVAILABLE = false

    private const val TAG = "Sync"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private val _state = MutableStateFlow<LibraryFlowState>(LibraryFlowState.Idle)
    val state: StateFlow<LibraryFlowState> = _state.asStateFlow()

    /** Closes a finished or failed message. A running operation can't be dismissed. */
    fun dismiss() {
        val s = _state.value
        if (s is LibraryFlowState.Failed || s is LibraryFlowState.Done) {
            _state.value = LibraryFlowState.Idle
        }
    }

    /** First contact with an account: read it, compare it with this device, ask if needed. */
    fun begin(store: LocalLibraryStore) = launchExclusive {
        val session = AccountManager.activeSession() ?: return@launchExclusive
        _state.value = LibraryFlowState.Working("Reading your account...")
        val snapshot = fetchOrFail(session) ?: return@launchExclusive

        val local = store.counts()
        Timber.tag(TAG).i("Login check: local=$local, account=${snapshot.counts}")
        if (local.isEmpty) {
            // Nothing on this device to lose: just take the account's library.
            applyAndLink(store, snapshot, wipeLocalOnly = true)
            return@launchExclusive
        }
        _state.value = if (snapshot.isEmpty) {
            LibraryFlowState.ChooseForEmptyAccount(local)
        } else {
            LibraryFlowState.ConfirmOverride(local, snapshot.counts)
        }
    }

    /**
     * The user confirmed replacing this device's library with the account's. The account is read
     * again first, and if it no longer matches what the user was asked about, they're asked
     * again instead of acting on a stale answer.
     */
    fun replaceLocalWithCloud(store: LocalLibraryStore, expectAccountEmpty: Boolean) = launchExclusive {
        val session = AccountManager.activeSession() ?: return@launchExclusive
        _state.value = LibraryFlowState.Working("Reading your account...")
        val snapshot = fetchOrFail(session) ?: return@launchExclusive

        val local = store.counts()
        if (snapshot.isEmpty != expectAccountEmpty) {
            Timber.tag(TAG).w("Account changed while waiting for an answer; asking again")
            _state.value = if (snapshot.isEmpty) {
                LibraryFlowState.ChooseForEmptyAccount(local)
            } else {
                LibraryFlowState.ConfirmOverride(local, snapshot.counts)
            }
            return@launchExclusive
        }
        applyAndLink(store, snapshot, wipeLocalOnly = true)
    }

    /** "Log out instead": the user didn't want to link. The local library is untouched. */
    fun declineAndLogout() = launchExclusive {
        AccountManager.logout()
        _state.value = LibraryFlowState.Idle
    }

    /** Pull the account's current library onto this device (cloud wins). */
    fun syncNow(store: LocalLibraryStore) = launchExclusive {
        val session = AccountManager.activeSession() ?: return@launchExclusive
        _state.value = LibraryFlowState.Working("Reading your account...")
        var snapshot = fetchOrFail(session) ?: return@launchExclusive

        val local = store.counts()
        if (looksLikeMassRemoval(local, snapshot.counts)) {
            // A big drop is either real (cleared on another device) or a bad read. Read again,
            // and only go ahead if two reads agree.
            Timber.tag(TAG).w("Large change ($local -> ${snapshot.counts}); reading again before applying")
            _state.value = LibraryFlowState.Working("Double-checking a large change...")
            delay(1500)
            val second = fetchOrFail(session) ?: return@launchExclusive
            if (!sameLibrary(snapshot, second)) {
                Timber.tag(TAG).w("Two reads disagreed; nothing applied")
                _state.value = LibraryFlowState.Failed(
                    "Your account's library looked different between two reads, so nothing was changed. Try again in a moment."
                )
                return@launchExclusive
            }
            snapshot = second
        }
        applyAndLink(store, snapshot, wipeLocalOnly = false)
    }

    /**
     * Logs out, after the user chose what happens to the library on this device. The local step
     * runs first; if it fails the user stays signed in and nothing has changed.
     */
    fun logout(store: LocalLibraryStore, clearLocal: Boolean) = launchExclusive {
        try {
            if (clearLocal) store.clearAll() else store.detachFromCloud()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Logout: couldn't update the local library")
            _state.value = LibraryFlowState.Failed("Couldn't update the library on this device, so you're still signed in. Nothing was changed.")
            return@launchExclusive
        }
        AccountManager.logout()
        _state.value = LibraryFlowState.Idle
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private suspend fun applyAndLink(store: LocalLibraryStore, snapshot: CloudSnapshot, wipeLocalOnly: Boolean) {
        _state.value = LibraryFlowState.Working("Updating this device...")
        try {
            store.replaceWithCloud(snapshot, wipeLocalOnly)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Applying the account library failed")
            _state.value = LibraryFlowState.Failed("Couldn't update this device's library. Nothing was changed.")
            return
        }
        if (!AccountManager.markLibraryLinked(true)) {
            Timber.tag(TAG).w("Library applied but the link state couldn't be saved")
        }
        val c = snapshot.counts
        Timber.tag(TAG).i("Applied: $c")
        _state.value = LibraryFlowState.Done(
            "${c.liked} liked songs, ${c.albums} albums and ${c.playlists} playlists are now on this device."
        )
    }

    /** The snapshot, or null after moving to a Failed state (nothing was changed). */
    private suspend fun fetchOrFail(session: AccountSession): CloudSnapshot? {
        val result = kotlinx.coroutines.withContext(Dispatchers.IO) { CloudSnapshotFetcher.fetch(session) }
        return when (result) {
            is AccountResult.Success -> result.value
            is AccountResult.SessionExpired -> failed("Your session expired. Sign in again.")
            is AccountResult.NetworkError -> failed("No connection. Nothing was changed.")
            is AccountResult.ServerError -> failed("YouTube is having trouble right now (${result.code}). Nothing was changed.")
            is AccountResult.ParseError -> failed("YouTube sent something unexpected (${result.message}). Nothing was changed.")
        }
    }

    private fun failed(message: String): CloudSnapshot? {
        Timber.tag(TAG).w("Not applied: $message")
        _state.value = LibraryFlowState.Failed(message)
        return null
    }

    /** Dropping to nothing, or losing at least half of 10+ items, in any part of the library. */
    private fun looksLikeMassRemoval(local: LibraryCounts, cloud: LibraryCounts): Boolean {
        fun drop(l: Int, c: Int) = (l > 0 && c == 0) || (l - c >= 10 && c * 2 < l)
        return drop(local.liked, cloud.liked) ||
            drop(local.albums, cloud.albums) ||
            drop(local.playlists, cloud.playlists) ||
            drop(local.playlistSongs, cloud.playlistSongs)
    }

    private fun sameLibrary(a: CloudSnapshot, b: CloudSnapshot): Boolean =
        a.counts == b.counts &&
            a.liked.map { it.song.id } == b.liked.map { it.song.id } &&
            a.albums.map { it.id } == b.albums.map { it.id } &&
            a.playlists.map { it.playlist.id } == b.playlists.map { it.playlist.id }

    private fun launchExclusive(block: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Library flow failed")
                _state.value = LibraryFlowState.Failed("Something went wrong. Nothing was changed.")
            } finally {
                busy.set(false)
            }
        }
    }
}
