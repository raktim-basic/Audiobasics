package com.rkd.audiobasics.ui

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.rkd.audiobasics.api.account.AccountManager
import com.rkd.audiobasics.api.account.AccountState
import com.rkd.audiobasics.api.account.LibraryDiff
import com.rkd.audiobasics.api.account.LibraryFlow
import com.rkd.audiobasics.api.account.LibraryUpload
import com.rkd.audiobasics.api.account.RefreshResult
import kotlinx.coroutines.CancellationException
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

/**
 * Refreshes this device's library from the account: the pull-down gesture on the library
 * screens, and once automatically when the app starts.
 *
 * It is the same read-everything-then-apply refresh as "Sync now" (including the mass-removal
 * double check), but silent: no dialogs, just the refresh indicator and, when something needs
 * saying, a toast.
 *
 * It only ever syncs when an account is signed in AND its library is linked. Otherwise the
 * indicator still plays briefly (so the gesture always feels alive) and nothing is touched;
 * in particular it never runs the first-link rules by itself.
 *
 * Runs on its own scope, so leaving the screen can't cancel it halfway through applying.
 */
object LibraryRefresh {

    private const val TAG = "Sync"
    private const val MIN_SPIN_MS = 700L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = AtomicBoolean(false)
    private val launchRefreshDone = AtomicBoolean(false)

    private val _refreshing = MutableStateFlow(false)

    /** True while a refresh (pulled or automatic) is in progress or its indicator is playing. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** The pull-down gesture. */
    fun pull(vm: MusicViewModel, context: Context) = start(vm, context, automatic = false)

    /** Once per app start. Does nothing (no indicator either) unless a linked account is signed in. */
    fun refreshOnLaunch(vm: MusicViewModel, context: Context) {
        if (!launchRefreshDone.compareAndSet(false, true)) return
        val st = AccountManager.state.value
        if (st !is AccountState.LoggedIn || !st.libraryLinked) return
        Timber.tag(TAG).i("Launch refresh starting")
        start(vm, context, automatic = true)
    }

    /** What to tell the user: [message] for a pull, [autoMessage] for the automatic one. */
    private class Outcome(val message: String?, val autoMessage: String? = null)

    private fun start(vm: MusicViewModel, context: Context, automatic: Boolean) {
        if (!running.compareAndSet(false, true)) return
        _refreshing.value = true
        val app = context.applicationContext
        scope.launch {
            var outcome = Outcome(null)
            try {
                val began = SystemClock.elapsedRealtime()
                outcome = try {
                    decide(vm)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Library refresh failed")
                    Outcome("Something went wrong. Nothing was changed.", "Something went wrong. Nothing was changed.")
                }
                // A very fast answer (or nothing to do) would flash the indicator; let it play.
                val left = MIN_SPIN_MS - (SystemClock.elapsedRealtime() - began)
                if (left > 0) delay(left)
            } finally {
                _refreshing.value = false
                running.set(false)
            }
            val text = if (automatic) outcome.autoMessage else outcome.message
            if (text != null) Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun decide(vm: MusicViewModel): Outcome =
        when (val st = AccountManager.state.value) {
            // Nothing to sync: the indicator plays and leaves, silently.
            is AccountState.LoggedOut -> Outcome(null)
            is AccountState.SessionExpired ->
                Outcome("Your session expired. Sign in again.")
            is AccountState.LoggedIn ->
                if (!st.libraryLinked && LibraryUpload.isActive()) Outcome("Your library is still uploading. Refresh works once it's finished.")
                else if (!st.libraryLinked) Outcome("Finish setting up your library in Account first.")
                else syncLinkedLibrary(vm)
        }

    /** "Library up to date", or what actually changed on this device. */
    private fun describe(diff: LibraryDiff?): String {
        if (diff == null || diff.isEmpty) return "Library up to date"
        fun n(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
        val parts = buildList {
            if (diff.likedChanged > 0) add(n(diff.likedChanged, "liked song", "liked songs"))
            if (diff.albumsChanged > 0) add(n(diff.albumsChanged, "album", "albums"))
            val playlists = diff.playlistsChanged + diff.playlistsAdded + diff.playlistsRemoved
            if (playlists > 0) add(n(playlists, "playlist", "playlists"))
        }
        return "Library updated: ${parts.joinToString(", ")}"
    }

    private suspend fun syncLinkedLibrary(vm: MusicViewModel): Outcome {
        // A change still on its way to the account would be undone by a read that started
        // before it finished. Wait for the user to try again instead.
        if (vm.cloudBusy.value.isNotEmpty()) {
            return Outcome("Finishing a change. Try again in a moment.")
        }
        var touched = false
        val watcher = scope.launch {
            vm.cloudBusy.collect { if (it.isNotEmpty()) touched = true }
        }
        val result = try {
            LibraryFlow.refresh(VmLibraryStore(vm)) { !touched && vm.cloudBusy.value.isEmpty() }
        } finally {
            watcher.cancel()
        }
        Timber.tag(TAG).i("Library refresh: $result")
        return when (result) {
            is RefreshResult.Updated -> {
                // Shown for the launch refresh too, so it's clear that it ran.
                val text = describe(result.diff)
                Outcome(text, text)
            }
            // Another sync (Account screen) is already running and shows its own progress.
            RefreshResult.Busy -> Outcome(null)
            is RefreshResult.Refused -> {
                // At launch, offline / expired sessions stay quiet; anything else is worth a toast.
                val quietOnLaunch = result.offline || result.expired
                Outcome(result.message, if (quietOnLaunch) null else result.message)
            }
        }
    }
}
