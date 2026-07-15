package com.rkd.audiobasics.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lightweight remote-message system.
 *
 * This is decoupled from the app's GitHub repo on purpose: pointing at a separate,
 * low-traffic repo means edits to the message text never trigger a release build.
 *
 * Priority order when deciding what to show in a message slot:
 *   1. Update available banner (handled by the caller, always wins)
 *   2. Remote custom message (fetched from REMOTE_CONTENT_URL) — persisted to disk, so the
 *      last successfully fetched copy survives app restarts and shows immediately, instead
 *      of flashing the hardcoded default while a fresh fetch is in flight.
 *   3. Default hardcoded fallback (only when nothing has ever been fetched successfully)
 *
 * Backed by ab-configs/header.json — a small, dedicated config repo (raktim-basic/ab-configs)
 * separate from the main app repo. Currently used by the homescreen header (line1/line2);
 * the migration-message fields below remain wired in for any future migration banner but
 * aren't populated by header.json today, so they'll keep resolving to their defaults until
 * ab-configs adds those keys.
 */
object MigrationMessageProvider {

    private const val REMOTE_CONTENT_URL =
        "https://raw.githubusercontent.com/raktim-basic/ab-configs/main/header.json"

    private const val PREFS_NAME = "ytlite"
    private const val KEY_HOME_HEADER_LINE1 = "cached_home_header_line1"
    private const val KEY_HOME_HEADER_LINE2 = "cached_home_header_line2"

    // --- Default fallback copy -------------------------------------------------

    const val SLOGAN_TITLE_DEFAULT = "No recommendation bs"
    const val SLOGAN_SUBTITLE_DEFAULT = "Own your taste"
    const val SLOGAN_FOOTER_DEFAULT = ""

    const val UPDATER_WARNING_DEFAULT =
        "A major update is coming. You'll need to uninstall this app and install the new one.\n\n" +
            "To keep your library and playlists, export them before updating, then import on the new version.\n" +
            "(Note: downloaded songs will need to be re-downloaded)\n\n" +
            "This is a one-time change and won't happen again."

    const val POPUP_WARNING_DEFAULT =
        "If you haven't exported your library yet, you may lose your playlists.\n\n" +
            "Please export it now, then import it on the new version.\n\n" +
            "This is a one-time change and won't happen again.\n\n" +
            "(Uninstall the current version before installing the new one)"

    // Homescreen header defaults — matches the slogan the header used before it became
    // remotely editable. Only shown if nothing has ever been cached to disk either.
    const val HOME_HEADER_LINE1_DEFAULT = "No recommendation bs"
    const val HOME_HEADER_LINE2_DEFAULT = "Own your taste"

    // --- Remote fetch + disk cache ------------------------------------------------

    private var cachedRemote: RemoteMessages? = null

    // Last-known-good header, loaded from disk on first access so it's available
    // immediately on cold start, before the network fetch has had a chance to run.
    private var diskCachedHeaderLine1: String? = null
    private var diskCachedHeaderLine2: String? = null
    private var diskCacheLoaded = false

    data class RemoteMessages(
        val sloganTitle: String?,
        val sloganSubtitle: String?,
        val sloganFooter: String?,
        val updaterWarning: String?,
        val popupWarning: String?,
        val homeHeaderLine1: String?,
        val homeHeaderLine2: String?
    )

    /**
     * Loads the last-known-good homescreen header from disk. Cheap and idempotent —
     * safe to call from init{} before the network fetch runs. Must be called once with
     * a Context before homeHeaderLine1()/homeHeaderLine2() are read, or those getters
     * will only have the hardcoded defaults to fall back on for this process.
     */
    fun loadCache(context: Context) {
        if (diskCacheLoaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        diskCachedHeaderLine1 = prefs.getString(KEY_HOME_HEADER_LINE1, null)
        diskCachedHeaderLine2 = prefs.getString(KEY_HOME_HEADER_LINE2, null)
        diskCacheLoaded = true
    }

    /**
     * Attempts to fetch remote message overrides. Returns null on any failure, in which
     * case callers should keep relying on the disk cache / hardcoded defaults — the
     * previously cached header is left untouched so a transient network failure never
     * blanks out or reverts an already-showing custom header.
     */
    suspend fun fetchRemoteMessages(context: Context): RemoteMessages? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val req = Request.Builder().url(REMOTE_CONTENT_URL).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext null
            val json = org.json.JSONObject(body)
            val remote = RemoteMessages(
                sloganTitle = json.optString("sloganTitle").takeIf { it.isNotBlank() },
                sloganSubtitle = json.optString("sloganSubtitle").takeIf { it.isNotBlank() },
                sloganFooter = json.optString("sloganFooter").takeIf { it.isNotBlank() },
                updaterWarning = json.optString("updaterWarning").takeIf { it.isNotBlank() },
                popupWarning = json.optString("popupWarning").takeIf { it.isNotBlank() },
                homeHeaderLine1 = json.optString("line1").takeIf { it.isNotBlank() },
                homeHeaderLine2 = json.optString("line2").takeIf { it.isNotBlank() }
            )
            cachedRemote = remote

            // Persist the header fields so next launch shows this immediately, without
            // waiting on the network. Only overwrite disk cache when the fetch actually
            // returned a header value — a header.json that's missing line1/line2 (but
            // still parses) shouldn't erase a previously cached header.
            if (remote.homeHeaderLine1 != null || remote.homeHeaderLine2 != null) {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    remote.homeHeaderLine1?.let {
                        putString(KEY_HOME_HEADER_LINE1, it)
                        diskCachedHeaderLine1 = it
                    }
                    remote.homeHeaderLine2?.let {
                        putString(KEY_HOME_HEADER_LINE2, it)
                        diskCachedHeaderLine2 = it
                    }
                }.apply()
            }

            remote
        } catch (_: Exception) {
            null
        }
    }

    // --- Resolved getters ---------------------------------------------------------
    // Priority: this-session remote fetch > disk cache (last successful fetch from a
    // prior session) > hardcoded default. "Update available" is handled by the caller.

    fun sloganTitle(): String = cachedRemote?.sloganTitle ?: SLOGAN_TITLE_DEFAULT
    fun sloganSubtitle(): String = cachedRemote?.sloganSubtitle ?: SLOGAN_SUBTITLE_DEFAULT
    fun sloganFooter(): String = cachedRemote?.sloganFooter ?: SLOGAN_FOOTER_DEFAULT
    fun updaterWarning(): String = cachedRemote?.updaterWarning ?: UPDATER_WARNING_DEFAULT
    fun popupWarning(): String = cachedRemote?.popupWarning ?: POPUP_WARNING_DEFAULT

    fun homeHeaderLine1(): String =
        cachedRemote?.homeHeaderLine1 ?: diskCachedHeaderLine1 ?: HOME_HEADER_LINE1_DEFAULT

    fun homeHeaderLine2(): String =
        cachedRemote?.homeHeaderLine2 ?: diskCachedHeaderLine2 ?: HOME_HEADER_LINE2_DEFAULT
}
