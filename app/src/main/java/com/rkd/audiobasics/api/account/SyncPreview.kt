package com.rkd.audiobasics.api.account

import com.rkd.audiobasics.data.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * A dry run of account sync: reads the whole cloud library with the same strict fetching
 * the real sync will use, compares it with what's on this device, and reports what WOULD
 * change. It writes nothing locally and nothing to the cloud.
 *
 * It exists so the fetching and completeness rules can be proven on real accounts before
 * anything is allowed to modify a library (that arrives together with the login/logout
 * rules, which decide when overwriting local data is acceptable).
 */
internal object SyncPreview {

    private const val TAG = "Sync"
    private const val STEP_PAUSE_MS = 400L

    suspend fun run(localLikedIds: Set<String>, localAlbums: List<Album>): List<String> =
        withContext(Dispatchers.IO) {
            val lines = mutableListOf<String>()
            fun log(line: String) {
                lines += line
                Timber.tag(TAG).i(line)
            }

            val session = AccountManager.activeSession()
            if (session == null) {
                log("Not signed in. Sign in first.")
                return@withContext lines
            }
            val started = System.currentTimeMillis()
            log("Read-only preview. Nothing is changed on this device or in your account.")

            // ── Liked songs ──────────────────────────────────────────────────
            when (val r = CloudLibrary.fetchLikedSongs(session)) {
                is AccountResult.Success -> {
                    val list = r.value
                    val ids = list.items.map { it.song.id }
                    val cloud = ids.toSet()
                    val toAdd = cloud.count { it !in localLikedIds }
                    val toRemove = localLikedIds.count { it !in cloud }
                    log("Liked songs: ${cloud.size} in the cloud, ${localLikedIds.size} here. Complete" +
                        " (${list.pages} page${if (list.pages == 1) "" else "s"}" +
                        (list.advertisedCount?.let { ", header said $it" } ?: ", header gave no count") + ").")
                    log("  Would add $toAdd, would remove $toRemove.")
                    val extra = buildList {
                        if (ids.size != cloud.size) add("${ids.size - cloud.size} repeated")
                        if (list.skippedRows > 0) add("${list.skippedRows} unreadable rows")
                        if (list.items.any { it.setVideoId == null }) add("some rows had no entry ID")
                    }
                    if (extra.isNotEmpty()) log("  Note: ${extra.joinToString(", ")}.")
                }
                else -> {
                    log("Liked songs: NOT READ. ${describe(r)}")
                    if (r is AccountResult.SessionExpired) return@withContext finish(lines, started)
                }
            }
            Thread.sleep(STEP_PAUSE_MS)

            // ── Saved albums ─────────────────────────────────────────────────
            when (val r = CloudLibrary.fetchSavedAlbums(session)) {
                is AccountResult.Success -> {
                    val cloud = r.value.items
                    val localById = localAlbums.associateBy { it.id }
                    val matched = HashSet<String>()
                    var byTitle = 0
                    var toAdd = 0
                    for (c in cloud) {
                        val byId = localById[c.id]
                        val local = byId ?: localAlbums.firstOrNull {
                            it.id !in matched && normalize(it.title) == normalize(c.title)
                        }
                        if (local == null) {
                            toAdd++
                        } else {
                            matched += local.id
                            if (byId == null) byTitle++
                        }
                    }
                    val toRemove = localAlbums.size - matched.size
                    log("Saved albums: ${cloud.size} in the cloud, ${localAlbums.size} here. Complete" +
                        " (${r.value.pages} page${if (r.value.pages == 1) "" else "s"}).")
                    log("  Would add $toAdd, would remove $toRemove" +
                        (if (byTitle > 0) " ($byTitle matched by title, not ID)." else "."))
                }
                else -> {
                    log("Saved albums: NOT READ. ${describe(r)}")
                    if (r is AccountResult.SessionExpired) return@withContext finish(lines, started)
                }
            }
            Thread.sleep(STEP_PAUSE_MS)

            // ── Playlists ────────────────────────────────────────────────────
            when (val r = CloudLibrary.fetchPlaylists(session)) {
                is AccountResult.Success -> {
                    val all = r.value.items
                    val owned = all.filter { it.owned }
                    log("Playlists: ${owned.size} created by you, ${all.size - owned.size} saved from others (those are not synced).")
                    for (p in owned) {
                        Thread.sleep(STEP_PAUSE_MS)
                        when (val pr = CloudLibrary.fetchPlaylistSongs(session, p.id)) {
                            is AccountResult.Success -> {
                                val l = pr.value
                                val unique = l.items.map { it.song.id }.toSet().size
                                val notes = buildList {
                                    if (l.items.size != unique) add("${l.items.size - unique} repeated")
                                    if (l.skippedRows > 0) add("${l.skippedRows} unreadable")
                                    if (l.advertisedCount == null) add("no header count")
                                }
                                log("  ${p.title}: ${l.items.size} songs, complete" +
                                    (if (notes.isEmpty()) "." else " (${notes.joinToString(", ")})."))
                            }
                            else -> {
                                log("  ${p.title}: NOT READ. ${describe(pr)}")
                                if (pr is AccountResult.SessionExpired) return@withContext finish(lines, started)
                            }
                        }
                    }
                }
                else -> log("Playlists: NOT READ. ${describe(r)}")
            }

            finish(lines, started)
        }

    private fun finish(lines: MutableList<String>, started: Long): List<String> {
        val line = "Done in ${(System.currentTimeMillis() - started) / 1000.0}s."
        lines += line
        Timber.tag(TAG).i(line)
        return lines
    }

    private fun describe(r: AccountResult<*>): String = when (r) {
        is AccountResult.Success -> "ok"
        is AccountResult.SessionExpired -> "Your session expired. Sign in again."
        is AccountResult.NetworkError -> "No connection (${r.message}). Nothing would be applied."
        is AccountResult.ServerError -> "YouTube answered HTTP ${r.code}. Nothing would be applied."
        is AccountResult.ParseError -> "${r.message}. Nothing would be applied."
    }

    private fun normalize(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }
}
