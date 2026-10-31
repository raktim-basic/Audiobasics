package com.rkd.audiobasics.api.account

import android.content.Context
import android.widget.Toast
import com.rkd.audiobasics.data.Album
import com.rkd.audiobasics.data.Song
import com.rkd.audiobasics.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import kotlin.random.Random

// ─── What the upload reads from this device ──────────────────────────────────

/** A local playlist as it is today. [songs] are in the order the app shows them (top first). */
internal class LocalPlaylistForUpload(
    val localId: String,
    val name: String,
    val emoji: String,
    val songs: List<Song>
)

/** The whole local library, newest first everywhere (the order the app shows it in). */
internal class LocalLibraryForUpload(
    val liked: List<Song>,
    val albums: List<Album>,
    val playlists: List<LocalPlaylistForUpload>
)

// ─── What the UI sees ────────────────────────────────────────────────────────

internal enum class PauseReason {
    USER, APP_RESTARTED, OFFLINE, SESSION, WRONG_ACCOUNT, RATE_LIMITED, BLOCKED, TOO_MANY_REFUSALS, ERROR
}

/** One thing the account would not take. [kind] is "like", "album", "playlist" or "playlist:<name>". */
internal data class UploadFailure(val kind: String, val id: String, val label: String, val reason: String)

internal data class UploadSummary(
    val likesTotal: Int,
    val likesFailed: Int,
    val albumsTotal: Int,
    val albumsFailed: Int,
    val playlistsTotal: Int,
    val playlistsCreated: Int,
    val playlistSongsTotal: Int,
    val playlistSongsFailed: Int,
    val failures: List<UploadFailure>,
    val notes: List<String>
) {
    /** Plain text, one line per item, for copying. */
    fun failureText(): String = failures.joinToString("\n") { f ->
        val where = when {
            f.kind == "like" -> "liked song"
            f.kind == "album" -> "saved album"
            f.kind == "playlist" -> "playlist (not created)"
            f.kind.startsWith("playlist:") -> "playlist ${f.kind.removePrefix("playlist:")}"
            else -> f.kind
        }
        "${f.label}  [$where]  ${f.reason}"
    }
}

internal sealed interface UploadState {
    data object Idle : UploadState

    data class Running(
        val step: String,
        val done: Int,
        val total: Int,
        val etaSeconds: Long?,
        val note: String?
    ) : UploadState

    data class Paused(
        val reason: PauseReason,
        val message: String,
        val done: Int,
        val total: Int
    ) : UploadState

    data class Finished(val summary: UploadSummary) : UploadState
}

// ─── The upload ──────────────────────────────────────────────────────────────

/**
 * Copies this device's library into an EMPTY account, slowly and verifiably.
 *
 *  - Same strict confirmed writes as everywhere else ([CloudWriter]); playlists are private.
 *  - Order: albums, then playlists, then liked songs, each oldest first, so the account ends up
 *    looking the same (newest on top). Where YouTube puts a newly added playlist song (top or
 *    bottom) is measured once with a throwaway playlist, not assumed.
 *  - Slow on purpose: a new account doing bulk writes is the likeliest to be rate-limited.
 *    Several playlist songs go in one request; a refused batch is retried song by song so one
 *    bad song can't sink nine good ones.
 *  - Progress is saved after every step. Killing the app, going offline or pausing is safe: the
 *    upload waits and is resumed by the user (never automatically), and refuses to continue into
 *    a different account.
 *  - Nothing is assumed in the end: the whole account is read back, anything that isn't there is
 *    reported, and only then is the library linked (playlists get their cloud IDs).
 *
 * While an upload is unfinished the local library can't be edited (see MusicViewModel.writeMode)
 * and refreshes stay off, because the account only holds part of the library.
 */
internal object LibraryUpload {

    private const val TAG = "Upload"

    private const val BATCH = 10
    private const val ALBUM_PAUSE_MS = 2500L
    private const val PLAYLIST_PAUSE_MS = 3000L
    private const val BATCH_PAUSE_MS = 2500L
    private const val SINGLE_PAUSE_MS = 1500L
    private const val LIKE_PAUSE_MS = 2000L
    private const val FIRST_BACKOFF_MS = 30_000L
    private const val MAX_BACKOFF_TRIES = 4
    private const val MAX_CONSECUTIVE_REFUSALS = 8
    private const val DEFAULT_OP_MS = 3500L
    private const val PROBE_NAME = "Audiobasics placement check"

    private enum class Phase { PROBE, ALBUMS, PLAYLISTS, LIKES, FINISH }

    private class PlanSong(val id: String, val label: String)
    private class PlanPlaylist(val localId: String, val name: String, val emoji: String, val songs: List<PlanSong>)

    /** Fixed when the upload starts. Lists are newest first, like the app shows them. */
    private class Plan(
        val accountId: String,
        val accountName: String,
        val albums: List<PlanSong>,
        val playlists: List<PlanPlaylist>,
        val likes: List<PlanSong>
    )

    /** Moves forward as the upload goes; saved after every step. */
    private class Progress(
        val accountId: String,
        var phase: Phase = Phase.PROBE,
        var placementTop: Boolean? = null,
        var probeId: String? = null,
        var albumNext: Int = 0,
        var plIndex: Int = 0,
        var plNext: Int = 0,
        var likeNext: Int = 0,
        val cloudIds: MutableMap<String, String> = LinkedHashMap(),
        var creating: String? = null,
        val failed: MutableList<UploadFailure> = ArrayList(),
        var okAlbums: Int = 0,
        var avgOpMs: Long = DEFAULT_OP_MS,
        val notes: MutableList<String> = ArrayList()
    )

    private sealed interface Outcome<out T> {
        class Ok<T>(val value: T) : Outcome<T>
        /** YouTube said no to this one item. The upload goes on without it. */
        class Refused(val code: Int, val message: String) : Outcome<Nothing>
        /** Nothing more can be done right now. */
        class Halt(val reason: PauseReason, val message: String) : Outcome<Nothing>
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state.asStateFlow()

    @Volatile private var active = false
    @Volatile private var stopRequested = false
    private var job: Job? = null

    private var appContext: Context? = null
    private var planFile: File? = null
    private var stateFile: File? = null
    private var summaryFile: File? = null

    private var plan: Plan? = null
    private var progress: Progress? = null
    private var corrupt = false
    private var backoffNote: String? = null
    private var consecutiveRefusals = 0

    /** True while an upload is unfinished (running or paused). Cheap; safe to call from anywhere. */
    fun isActive(): Boolean = active

    fun hasUnfinishedFor(accountId: String): Boolean =
        active && !corrupt && progress?.accountId == accountId

    // ── Start-up ─────────────────────────────────────────────────────────────

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        planFile = File(app.noBackupFilesDir, "upload_plan.json")
        stateFile = File(app.noBackupFilesDir, "upload_state.json")
        summaryFile = File(app.noBackupFilesDir, "upload_summary.json")
        try {
            val summary = summaryFile!!
            if (summary.exists()) {
                // Finished, but the user hasn't closed the report yet.
                _state.value = UploadState.Finished(summaryFromJson(JSONObject(summary.readText())))
                planFile!!.delete(); stateFile!!.delete()
                active = false
                return
            }
            if (stateFile!!.exists() || planFile!!.exists()) {
                active = true
                plan = planFromJson(JSONObject(planFile!!.readText()))
                progress = progressFromJson(JSONObject(stateFile!!.readText()))
                val (done, total) = counts()
                _state.value = UploadState.Paused(
                    PauseReason.APP_RESTARTED,
                    "The upload was paused when the app closed. Resume to continue.",
                    done, total
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Saved upload progress couldn't be read")
            corrupt = true
            active = true
            _state.value = UploadState.Paused(
                PauseReason.ERROR,
                "The saved upload progress couldn't be read. Discard it to start over. What was already uploaded stays in your account.",
                0, 0
            )
        }
    }

    /** One toast at app start if an upload is waiting. */
    fun launchNotice(context: Context) {
        if (_state.value is UploadState.Paused && active) {
            Toast.makeText(context.applicationContext, "Your library upload is paused. Open Account to resume.", Toast.LENGTH_LONG).show()
        }
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    /** Writes the plan for [local] and gets ready; call [resume] to start. False if it couldn't be saved. */
    suspend fun prepare(session: AccountSession, local: LocalLibraryForUpload): Boolean = withContext(Dispatchers.IO) {
        try {
            fun song(s: Song) = PlanSong(s.id, "${s.title} — ${s.artist}")
            val newPlan = Plan(
                accountId = session.profile.accountId,
                accountName = session.profile.name,
                albums = local.albums.distinctBy { it.id }.map { PlanSong(it.id, "${it.title} — ${it.artist}") },
                playlists = local.playlists.map { p ->
                    PlanPlaylist(p.localId, p.name, p.emoji, p.songs.distinctBy { it.id }.map(::song))
                },
                likes = local.liked.distinctBy { it.id }.map(::song)
            )
            val newProgress = Progress(accountId = newPlan.accountId)
            writeAtomically(planFile!!, planToJson(newPlan).toString())
            writeAtomically(stateFile!!, progressToJson(newProgress).toString())
            summaryFile!!.delete()
            plan = newPlan
            progress = newProgress
            corrupt = false
            consecutiveRefusals = 0
            active = true
            val (done, total) = counts()
            _state.value = UploadState.Paused(PauseReason.USER, "Ready to upload.", done, total)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Couldn't prepare the upload")
            false
        }
    }

    /** Starts or continues the upload. Only ever called by the user (or right after they chose to upload). */
    fun resume() {
        if (!active || corrupt || job?.isActive == true) return
        val p = progress ?: return
        val pl = plan ?: return
        val session = AccountManager.activeSession()
        if (session == null) {
            val (done, total) = counts()
            _state.value = UploadState.Paused(PauseReason.SESSION, "Sign in to ${pl.accountName} to continue.", done, total)
            return
        }
        if (session.profile.accountId != p.accountId) {
            val (done, total) = counts()
            _state.value = UploadState.Paused(
                PauseReason.WRONG_ACCOUNT,
                "This upload belongs to ${pl.accountName}. Sign in with that account to continue.",
                done, total
            )
            return
        }
        stopRequested = false
        consecutiveRefusals = 0
        backoffNote = null
        publish("Getting started")
        job = scope.launch {
            try {
                runAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Upload stopped by an unexpected error")
                halt(PauseReason.ERROR, "Something went wrong. Your progress is saved. Resume to try again.")
            }
        }
    }

    /** Stops after the step in progress. Always safe. */
    fun pause() {
        stopRequested = true
        val s = _state.value
        if (s is UploadState.Running) _state.value = s.copy(note = "Pausing after this step...")
    }

    /** Throws the unfinished upload away (not while it's running). What's already in the account stays there. */
    fun discard(): Boolean {
        if (job?.isActive == true) return false
        planFile?.delete(); stateFile?.delete(); summaryFile?.delete()
        plan = null; progress = null; corrupt = false; active = false
        _state.value = UploadState.Idle
        return true
    }

    /** Closes the finished report. */
    fun dismissFinished() {
        if (_state.value is UploadState.Finished) {
            summaryFile?.delete()
            _state.value = UploadState.Idle
        }
    }

    // ── The run ──────────────────────────────────────────────────────────────

    private suspend fun runAll() {
        val p = progress ?: return
        while (true) {
            val keepGoing = when (p.phase) {
                Phase.PROBE -> doProbe()
                Phase.ALBUMS -> doAlbums()
                Phase.PLAYLISTS -> doPlaylists()
                Phase.LIKES -> doLikes()
                Phase.FINISH -> { doFinish(); return }
            }
            if (!keepGoing) return
        }
    }

    // Finds out whether YouTube puts a newly added playlist song on top or at the bottom, with a
    // throwaway playlist (made, read back, deleted). Decides the order playlists are filled in.
    private suspend fun doProbe(): Boolean {
        val p = progress!!
        val pl = plan!!
        if (p.placementTop != null) return next(Phase.ALBUMS)
        val source = pl.playlists.firstOrNull { it.songs.size >= 2 }
        if (source == null) {
            p.placementTop = true
            return next(Phase.ALBUMS)
        }

        // A throwaway left behind by an interrupted earlier run goes first.
        p.probeId?.let { old ->
            publish("Tidying up")
            when (val o = attempt { s -> CloudWriter.deletePlaylist(s, old) }) {
                is Outcome.Halt -> return halt(o.reason, o.message)
                else -> { p.probeId = null; save() }
            }
        }

        publish("Checking how YouTube orders new songs")
        val probeId = when (val o = attempt { s -> CloudWriter.createPlaylist(s, PROBE_NAME) }) {
            is Outcome.Ok -> o.value
            is Outcome.Halt -> return halt(o.reason, o.message)
            is Outcome.Refused -> return assumeTop(p, "The ordering check couldn't be made (${o.message}).")
        }
        p.probeId = probeId
        save()
        if (!nap(SINGLE_PAUSE_MS)) return halt(PauseReason.USER, "Paused.")

        val accepted = ArrayList<String>(2)
        for (candidate in source.songs.take(8)) {
            if (accepted.size == 2) break
            when (val o = attempt { s -> CloudWriter.addSongs(s, probeId, listOf(candidate.id)) }) {
                is Outcome.Ok -> accepted += candidate.id
                is Outcome.Refused -> Unit
                is Outcome.Halt -> return halt(o.reason, o.message)
            }
            if (!nap(SINGLE_PAUSE_MS)) return halt(PauseReason.USER, "Paused.")
        }

        var top: Boolean? = null
        if (accepted.size == 2) {
            when (val o = attempt { s -> CloudLibrary.fetchPlaylistSongs(s, probeId) }) {
                is Outcome.Ok -> {
                    val order = o.value.items.map { it.song.id }
                    val first = order.indexOf(accepted[0])
                    val second = order.indexOf(accepted[1])
                    if (first >= 0 && second >= 0) top = second < first
                }
                is Outcome.Refused -> Unit
                is Outcome.Halt -> return halt(o.reason, o.message)
            }
        }

        when (val o = attempt { s -> CloudWriter.deletePlaylist(s, probeId) }) {
            is Outcome.Ok -> p.probeId = null
            is Outcome.Refused -> {
                p.probeId = null
                p.notes += "A temporary playlist called \"$PROBE_NAME\" couldn't be removed. You can delete it in YouTube Music."
            }
            is Outcome.Halt -> {
                // Keep the ID so the cleanup is retried on resume, and don't lose the answer.
                p.placementTop = top
                save()
                return halt(o.reason, o.message)
            }
        }
        if (top == null) return assumeTop(p, "The ordering check wasn't conclusive.")
        Timber.tag(TAG).i("New playlist songs go on ${if (top) "top" else "the bottom"}")
        p.placementTop = top
        return next(Phase.ALBUMS)
    }

    private fun assumeTop(p: Progress, why: String): Boolean {
        p.notes += "$why Playlists were filled assuming YouTube puts new songs on top, so their order may be reversed."
        p.placementTop = true
        return next(Phase.ALBUMS)
    }

    private suspend fun doAlbums(): Boolean {
        val p = progress!!
        val albums = plan!!.albums
        while (p.albumNext < albums.size) {
            if (stopRequested) return halt(PauseReason.USER, "Paused.")
            val album = albums[albums.size - 1 - p.albumNext]   // oldest first
            publish("Albums · ${p.albumNext + 1} of ${albums.size}")
            val began = now()
            when (val o = attempt { s -> CloudWriter.likeAlbum(s, album.id, true) }) {
                is Outcome.Ok -> { p.okAlbums++; consecutiveRefusals = 0 }
                is Outcome.Refused -> {
                    addFailure("album", album, refusal(o))
                    if (tooManyRefusals()) return halt(PauseReason.TOO_MANY_REFUSALS, refusalHalt())
                }
                is Outcome.Halt -> return halt(o.reason, o.message)
            }
            p.albumNext++
            save()
            if (!nap(jitter(ALBUM_PAUSE_MS))) return halt(PauseReason.USER, "Paused.")
            learn(began)
        }
        return next(Phase.PLAYLISTS)
    }

    private suspend fun doPlaylists(): Boolean {
        val p = progress!!
        val pl = plan!!
        while (p.plIndex < pl.playlists.size) {
            if (stopRequested) return halt(PauseReason.USER, "Paused.")
            val pp = pl.playlists[p.plIndex]
            val title = PlaylistNameCodec.compose(pp.emoji, pp.name)

            // 1. The playlist itself.
            var cloudId = p.cloudIds[pp.localId]
            if (cloudId == null) {
                publish("Playlists · ${p.plIndex + 1} of ${pl.playlists.size} · $title")
                val began = now()

                // Killed between "create" and "saved the ID"? Look for it before making a second one.
                if (p.creating == pp.localId) {
                    when (val o = attempt { s -> CloudLibrary.fetchPlaylists(s) }) {
                        is Outcome.Ok -> {
                            val matches = o.value.items.filter { it.owned && it.title == title }
                            if (matches.size == 1) cloudId = matches[0].id
                        }
                        is Outcome.Refused -> Unit
                        is Outcome.Halt -> return halt(o.reason, o.message)
                    }
                }
                if (cloudId == null) {
                    p.creating = pp.localId
                    save()
                    when (val o = attempt { s -> CloudWriter.createPlaylist(s, title) }) {
                        is Outcome.Ok -> cloudId = o.value
                        is Outcome.Refused -> {
                            addFailure("playlist", PlanSong(pp.localId, title), refusal(o))
                            p.creating = null
                            p.plIndex++
                            p.plNext = 0
                            save()
                            if (tooManyRefusals()) return halt(PauseReason.TOO_MANY_REFUSALS, refusalHalt())
                            continue
                        }
                        is Outcome.Halt -> return halt(o.reason, o.message)
                    }
                }
                p.cloudIds[pp.localId] = cloudId!!
                p.creating = null
                save()
                if (!nap(jitter(PLAYLIST_PAUSE_MS))) return halt(PauseReason.USER, "Paused.")
                learn(began)
            }

            // 2. Its songs, oldest first. Where new songs land was measured in the probe.
            val order = if (p.placementTop == false) pp.songs else pp.songs.asReversed()
            val kind = "playlist:${pp.name}"
            while (p.plNext < order.size) {
                if (stopRequested) return halt(PauseReason.USER, "Paused.")
                val batch = order.subList(p.plNext, minOf(p.plNext + BATCH, order.size))
                publish("Playlist ${pp.name} · ${p.plNext + batch.size} of ${order.size}")
                val began = now()
                val id = cloudId!!
                when (val o = attempt { s -> CloudWriter.addSongs(s, id, batch.map { it.id }) }) {
                    is Outcome.Ok -> consecutiveRefusals = 0
                    is Outcome.Halt -> return halt(o.reason, o.message)
                    is Outcome.Refused -> {
                        if (batch.size == 1) {
                            addFailure(kind, batch[0], refusal(o))
                        } else {
                            // Find out which song(s) it didn't like: one at a time.
                            for (song in batch) {
                                if (stopRequested) return halt(PauseReason.USER, "Paused.")
                                when (val single = attempt { s -> CloudWriter.addSongs(s, id, listOf(song.id)) }) {
                                    is Outcome.Ok -> consecutiveRefusals = 0
                                    is Outcome.Refused -> addFailure(kind, song, refusal(single))
                                    is Outcome.Halt -> return halt(single.reason, single.message)
                                }
                                if (tooManyRefusals()) return halt(PauseReason.TOO_MANY_REFUSALS, refusalHalt())
                                if (!nap(jitter(SINGLE_PAUSE_MS))) return halt(PauseReason.USER, "Paused.")
                            }
                        }
                        if (tooManyRefusals()) return halt(PauseReason.TOO_MANY_REFUSALS, refusalHalt())
                    }
                }
                p.plNext += batch.size
                save()
                if (!nap(jitter(BATCH_PAUSE_MS))) return halt(PauseReason.USER, "Paused.")
                learn(began)
            }
            p.plIndex++
            p.plNext = 0
            save()
        }
        return next(Phase.LIKES)
    }

    private suspend fun doLikes(): Boolean {
        val p = progress!!
        val likes = plan!!.likes
        while (p.likeNext < likes.size) {
            if (stopRequested) return halt(PauseReason.USER, "Paused.")
            val song = likes[likes.size - 1 - p.likeNext]   // oldest first, so the newest ends up on top
            publish("Liked songs · ${p.likeNext + 1} of ${likes.size}")
            val began = now()
            when (val o = attempt { s -> CloudWriter.likeSong(s, song.id, true) }) {
                is Outcome.Ok -> consecutiveRefusals = 0
                is Outcome.Refused -> {
                    addFailure("like", song, refusal(o))
                    if (tooManyRefusals()) return halt(PauseReason.TOO_MANY_REFUSALS, refusalHalt())
                }
                is Outcome.Halt -> return halt(o.reason, o.message)
            }
            p.likeNext++
            save()
            if (!nap(jitter(LIKE_PAUSE_MS))) return halt(PauseReason.USER, "Paused.")
            learn(began)
        }
        return next(Phase.FINISH)
    }

    // Reads the whole account back, reports what didn't arrive, links the library. Safe to run
    // again if it was interrupted: everything it writes is the same the second time.
    private suspend fun doFinish() {
        val p = progress!!
        val pl = plan!!
        publish("Checking your account")
        val snapshot = when (val o = attempt { s -> CloudSnapshotFetcher.fetch(s) }) {
            is Outcome.Ok -> o.value
            is Outcome.Refused -> {
                halt(PauseReason.ERROR, "Couldn't read your account back (${o.message}). Resume to try again.")
                return
            }
            is Outcome.Halt -> {
                halt(o.reason, o.message)
                return
            }
        }

        val likedInCloud = snapshot.liked.map { it.song.id }.toHashSet()
        pl.likes.forEach { if (it.id !in likedInCloud) addFailure("like", it, "Not in your liked songs after the upload") }

        val links = ArrayList<Triple<PlanPlaylist, String, Map<String, String>>>()
        for (pp in pl.playlists) {
            val cid = p.cloudIds[pp.localId] ?: continue
            val kind = "playlist:${pp.name}"
            val cloud = snapshot.playlists.firstOrNull { it.playlist.id == cid }
            if (cloud == null) {
                pp.songs.forEach { addFailure(kind, it, "The playlist wasn't found in your account after the upload") }
                p.notes += "Playlist \"${pp.name}\" wasn't found in your account, so it stays on this device only."
                continue
            }
            val entries = HashMap<String, String>()
            cloud.songs.forEach { c -> c.setVideoId?.let { entries.putIfAbsent(c.song.id, it) } }
            val present = cloud.songs.map { it.song.id }.toHashSet()
            pp.songs.forEach { if (it.id !in present) addFailure(kind, it, "Not in the playlist after the upload") }
            links += Triple(pp, cid, entries)
        }

        try {
            val dao = AppDatabase.getInstance(appContext!!).playlistDao()
            withContext(Dispatchers.IO) {
                links.forEach { (pp, cid, entries) -> dao.linkUploadedPlaylist(pp.localId, cid, entries) }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Couldn't save the playlist links")
            halt(PauseReason.ERROR, "Couldn't save the result on this device. Resume to try again.")
            return
        }
        if (!AccountManager.markLibraryLinked(true)) {
            halt(PauseReason.ERROR, "Couldn't save that the library is linked. Resume to try again.")
            return
        }

        val failures = p.failed.toList()
        val summary = UploadSummary(
            likesTotal = pl.likes.size,
            likesFailed = failures.count { it.kind == "like" },
            albumsTotal = pl.albums.size,
            albumsFailed = failures.count { it.kind == "album" },
            playlistsTotal = pl.playlists.size,
            playlistsCreated = links.size,
            playlistSongsTotal = pl.playlists.sumOf { it.songs.size },
            playlistSongsFailed = failures.count { it.kind.startsWith("playlist:") },
            failures = failures,
            notes = p.notes.toList()
        )
        try {
            writeAtomically(summaryFile!!, summaryToJson(summary).toString())
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Couldn't save the report (it is still shown)")
        }
        planFile!!.delete()
        stateFile!!.delete()
        active = false
        plan = null
        progress = null
        _state.value = UploadState.Finished(summary)
        Timber.tag(TAG).i("Upload finished: $summary")
    }

    // ── One request, with the retry rules ────────────────────────────────────

    /**
     * Runs one account call. Rate limits and server trouble wait and retry (30s, 60s, 2m, 4m),
     * then stop. Offline, an expired session, a block (403) or the wrong account stop at once.
     * Anything else YouTube answers is a refusal of THIS item only.
     */
    private suspend fun <T> attempt(call: (AccountSession) -> AccountResult<T>): Outcome<T> {
        val p = progress!!
        var wait = FIRST_BACKOFF_MS
        var tries = 0
        while (true) {
            if (stopRequested) return Outcome.Halt(PauseReason.USER, "Paused.")
            val session = AccountManager.activeSession()
                ?: return Outcome.Halt(PauseReason.SESSION, "Your session expired. Sign in again to continue.")
            if (session.profile.accountId != p.accountId) {
                return Outcome.Halt(PauseReason.WRONG_ACCOUNT, "This upload belongs to ${plan?.accountName ?: "another account"}. Sign in with that account to continue.")
            }
            val r = withContext(Dispatchers.IO) { call(session) }
            when (r) {
                is AccountResult.Success -> {
                    backoffNote = null
                    return Outcome.Ok(r.value)
                }
                is AccountResult.SessionExpired -> {
                    AccountManager.reportSessionRejected()
                    return Outcome.Halt(PauseReason.SESSION, "Your session expired. Sign in again to continue.")
                }
                is AccountResult.NetworkError ->
                    return Outcome.Halt(PauseReason.OFFLINE, "No connection. Resume when you're back online.")
                is AccountResult.ParseError ->
                    return Outcome.Refused(0, r.message)
                is AccountResult.ServerError -> {
                    val code = r.code
                    when {
                        code == 429 || code >= 500 -> {
                            if (tries >= MAX_BACKOFF_TRIES) {
                                return Outcome.Halt(PauseReason.RATE_LIMITED, "YouTube keeps asking us to slow down. Wait a while, then resume.")
                            }
                            tries++
                            backoffNote = "YouTube asked us to slow down. Waiting ${wait / 1000}s before trying again."
                            republish()
                            if (!nap(wait)) return Outcome.Halt(PauseReason.USER, "Paused.")
                            wait *= 2
                        }
                        code == 401 -> {
                            AccountManager.reportSessionRejected()
                            return Outcome.Halt(PauseReason.SESSION, "Your session expired. Sign in again to continue.")
                        }
                        code == 403 -> return Outcome.Halt(PauseReason.BLOCKED, "YouTube refused the request (403). Wait a while before resuming.")
                        else -> return Outcome.Refused(code, r.message)
                    }
                }
            }
        }
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    private fun next(phase: Phase): Boolean {
        val p = progress!!
        p.phase = phase
        consecutiveRefusals = 0
        save()
        return true
    }

    private fun halt(reason: PauseReason, message: String): Boolean {
        runCatching { save() }
        val (done, total) = counts()
        backoffNote = null
        _state.value = UploadState.Paused(reason, message, done, total)
        Timber.tag(TAG).i("Paused ($reason): $message")
        return false
    }

    private fun addFailure(kind: String, song: PlanSong, reason: String) {
        val p = progress!!
        if (p.failed.any { it.kind == kind && it.id == song.id }) return
        p.failed += UploadFailure(kind, song.id, song.label, reason)
        consecutiveRefusals++
    }

    private fun refusal(o: Outcome.Refused): String =
        if (o.code == 0) o.message else "YouTube refused it (${o.code})"

    private fun tooManyRefusals() = consecutiveRefusals >= MAX_CONSECUTIVE_REFUSALS

    private fun refusalHalt() =
        "YouTube refused $MAX_CONSECUTIVE_REFUSALS items in a row, so the upload stopped to be safe. Resume to continue, or check the log."

    private fun jitter(base: Long) = base + Random.nextLong(0, base / 2)

    private fun now() = System.currentTimeMillis()

    /** Smooths how long one step really takes (including the pause), for the time estimate. */
    private fun learn(began: Long) {
        val p = progress!!
        val took = (now() - began).coerceIn(500L, 120_000L)
        p.avgOpMs = (p.avgOpMs * 8 + took * 2) / 10
    }

    private suspend fun nap(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            if (stopRequested) return false
            val slice = minOf(250L, left)
            delay(slice)
            left -= slice
        }
        return !stopRequested
    }

    private fun save() {
        val p = progress ?: return
        writeAtomically(stateFile!!, progressToJson(p).toString())
    }

    /** Items processed so far and in total (albums, playlists, playlist songs, liked songs). */
    private fun counts(): Pair<Int, Int> {
        val pl = plan ?: return 0 to 0
        val p = progress ?: return 0 to 0
        val songs = pl.playlists.map { it.songs.size }
        val total = pl.albums.size + pl.playlists.size + songs.sum() + pl.likes.size
        if (p.phase == Phase.FINISH) return total to total
        val prefixSongs = songs.take(p.plIndex).sum()
        val done = p.albumNext + p.plIndex + prefixSongs + p.plNext + p.likeNext
        return done.coerceAtMost(total) to total
    }

    private fun etaSeconds(): Long? {
        val pl = plan ?: return null
        val p = progress ?: return null
        var ops = 0
        if (p.phase <= Phase.ALBUMS) ops += pl.albums.size - p.albumNext
        if (p.phase <= Phase.PLAYLISTS) {
            ops += pl.playlists.size - p.plIndex
            val songsLeft = pl.playlists.drop(p.plIndex).sumOf { it.songs.size } - p.plNext
            ops += (songsLeft.coerceAtLeast(0) + BATCH - 1) / BATCH
        }
        if (p.phase <= Phase.LIKES) ops += pl.likes.size - p.likeNext
        return (ops.coerceAtLeast(0) * p.avgOpMs) / 1000
    }

    private fun publish(step: String) {
        val (done, total) = counts()
        _state.value = UploadState.Running(step, done, total, etaSeconds(), backoffNote)
    }

    private fun republish() {
        val s = _state.value
        if (s is UploadState.Running) _state.value = s.copy(note = backoffNote)
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "couldn't replace ${file.name}" }
        }
    }

    private fun songsToJson(list: List<PlanSong>) = JSONArray().also { arr ->
        list.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label)) }
    }

    private fun songsFromJson(arr: JSONArray?): List<PlanSong> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PlanSong(o.getString("id"), o.optString("label"))
        }
    }

    private fun planToJson(p: Plan) = JSONObject()
        .put("v", 1)
        .put("accountId", p.accountId)
        .put("accountName", p.accountName)
        .put("albums", songsToJson(p.albums))
        .put("likes", songsToJson(p.likes))
        .put("playlists", JSONArray().also { arr ->
            p.playlists.forEach {
                arr.put(
                    JSONObject().put("localId", it.localId).put("name", it.name)
                        .put("emoji", it.emoji).put("songs", songsToJson(it.songs))
                )
            }
        })

    private fun planFromJson(o: JSONObject): Plan {
        val pls = o.getJSONArray("playlists")
        return Plan(
            accountId = o.getString("accountId"),
            accountName = o.optString("accountName"),
            albums = songsFromJson(o.optJSONArray("albums")),
            likes = songsFromJson(o.optJSONArray("likes")),
            playlists = (0 until pls.length()).map { i ->
                val x = pls.getJSONObject(i)
                PlanPlaylist(x.getString("localId"), x.getString("name"), x.optString("emoji"), songsFromJson(x.optJSONArray("songs")))
            }
        )
    }

    private fun failuresToJson(list: List<UploadFailure>) = JSONArray().also { arr ->
        list.forEach {
            arr.put(JSONObject().put("kind", it.kind).put("id", it.id).put("label", it.label).put("reason", it.reason))
        }
    }

    private fun failuresFromJson(arr: JSONArray?): MutableList<UploadFailure> {
        val out = ArrayList<UploadFailure>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += UploadFailure(o.getString("kind"), o.getString("id"), o.optString("label"), o.optString("reason"))
        }
        return out
    }

    private fun progressToJson(p: Progress) = JSONObject()
        .put("v", 1)
        .put("accountId", p.accountId)
        .put("phase", p.phase.name)
        .put("placementTop", if (p.placementTop == null) JSONObject.NULL else p.placementTop)
        .put("probeId", p.probeId ?: JSONObject.NULL)
        .put("albumNext", p.albumNext)
        .put("plIndex", p.plIndex)
        .put("plNext", p.plNext)
        .put("likeNext", p.likeNext)
        .put("creating", p.creating ?: JSONObject.NULL)
        .put("okAlbums", p.okAlbums)
        .put("avgOpMs", p.avgOpMs)
        .put("cloudIds", JSONObject().also { o -> p.cloudIds.forEach { (k, v) -> o.put(k, v) } })
        .put("failed", failuresToJson(p.failed))
        .put("notes", JSONArray().also { arr -> p.notes.forEach { arr.put(it) } })

    private fun progressFromJson(o: JSONObject): Progress {
        val p = Progress(accountId = o.getString("accountId"))
        p.phase = Phase.valueOf(o.getString("phase"))
        p.placementTop = if (o.isNull("placementTop")) null else o.getBoolean("placementTop")
        p.probeId = if (o.isNull("probeId")) null else o.getString("probeId")
        p.albumNext = o.optInt("albumNext")
        p.plIndex = o.optInt("plIndex")
        p.plNext = o.optInt("plNext")
        p.likeNext = o.optInt("likeNext")
        p.creating = if (o.isNull("creating")) null else o.getString("creating")
        p.okAlbums = o.optInt("okAlbums")
        p.avgOpMs = o.optLong("avgOpMs", DEFAULT_OP_MS)
        val ids = o.optJSONObject("cloudIds")
        if (ids != null) ids.keys().forEach { k -> p.cloudIds[k] = ids.getString(k) }
        p.failed += failuresFromJson(o.optJSONArray("failed"))
        val notes = o.optJSONArray("notes")
        if (notes != null) {
            for (i in 0 until notes.length()) p.notes += notes.getString(i)
        }
        return p
    }

    private fun summaryToJson(s: UploadSummary) = JSONObject()
        .put("likesTotal", s.likesTotal).put("likesFailed", s.likesFailed)
        .put("albumsTotal", s.albumsTotal).put("albumsFailed", s.albumsFailed)
        .put("playlistsTotal", s.playlistsTotal).put("playlistsCreated", s.playlistsCreated)
        .put("playlistSongsTotal", s.playlistSongsTotal).put("playlistSongsFailed", s.playlistSongsFailed)
        .put("failures", failuresToJson(s.failures))
        .put("notes", JSONArray().also { arr -> s.notes.forEach { arr.put(it) } })

    private fun summaryFromJson(o: JSONObject): UploadSummary {
        val notes = ArrayList<String>()
        val arr = o.optJSONArray("notes")
        if (arr != null) {
            for (i in 0 until arr.length()) notes += arr.getString(i)
        }
        return UploadSummary(
            likesTotal = o.optInt("likesTotal"), likesFailed = o.optInt("likesFailed"),
            albumsTotal = o.optInt("albumsTotal"), albumsFailed = o.optInt("albumsFailed"),
            playlistsTotal = o.optInt("playlistsTotal"), playlistsCreated = o.optInt("playlistsCreated"),
            playlistSongsTotal = o.optInt("playlistSongsTotal"), playlistSongsFailed = o.optInt("playlistSongsFailed"),
            failures = failuresFromJson(o.optJSONArray("failures")),
            notes = notes
        )
    }
}
