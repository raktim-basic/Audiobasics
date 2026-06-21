package com.yt.lite.utils

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
 *   2. Remote custom message (fetched from REMOTE_CONTENT_URL)
 *   3. Default hardcoded fallback
 *
 * For 2.3.1 the remote fetch is wired in but not actively used — every slot just
 * returns its hardcoded fallback. A future release can start populating the remote
 * repo and this will pick it up with no app update needed.
 */
object MigrationMessageProvider {

    // Placeholder URL for a small content-only repo, e.g.:
    // https://raw.githubusercontent.com/<user>/audiobasics-content/main/messages.json
    private const val REMOTE_CONTENT_URL =
        "https://raw.githubusercontent.com/raktim-basic/audiobasics-content/main/messages.json"

    // --- Default fallback copy -------------------------------------------------

    const val SLOGAN_TITLE_DEFAULT = "A big change is coming"
    const val SLOGAN_SUBTITLE_DEFAULT = "Check the updater for details"
    const val SLOGAN_FOOTER_DEFAULT = "one-time change, won't happen again"

    const val UPDATER_WARNING_DEFAULT =
        "A major update is coming. You'll need to uninstall this app and install the new one.\n\n" +
            "To keep your library and playlists, export them before updating, then import on the new version.\n" +
            "(Note: cached songs will need to be re-downloaded)\n\n" +
            "This is a one-time change and won't happen again."

    const val POPUP_WARNING_DEFAULT =
        "If you haven't exported your library yet, you may lose your playlists.\n\n" +
            "Please export it now, then import it on the new version.\n\n" +
            "This is a one-time change and won't happen again.\n\n" +
            "(Uninstall the current version before installing the new one)"

    // --- Remote fetch (not yet used, kept ready for future releases) -----------

    private var cachedRemote: RemoteMessages? = null

    data class RemoteMessages(
        val sloganTitle: String?,
        val sloganSubtitle: String?,
        val sloganFooter: String?,
        val updaterWarning: String?,
        val popupWarning: String?
    )

    /**
     * Attempts to fetch remote message overrides. Returns null on any failure,
     * in which case callers should fall back to the hardcoded defaults.
     * Not called anywhere yet in 2.3.1 — reserved for future use.
     */
    suspend fun fetchRemoteMessages(): RemoteMessages? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val req = Request.Builder().url(REMOTE_CONTENT_URL).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext null
            val json = org.json.JSONObject(body)
            RemoteMessages(
                sloganTitle = json.optString("sloganTitle").takeIf { it.isNotBlank() },
                sloganSubtitle = json.optString("sloganSubtitle").takeIf { it.isNotBlank() },
                sloganFooter = json.optString("sloganFooter").takeIf { it.isNotBlank() },
                updaterWarning = json.optString("updaterWarning").takeIf { it.isNotBlank() },
                popupWarning = json.optString("popupWarning").takeIf { it.isNotBlank() }
            ).also { cachedRemote = it }
        } catch (_: Exception) {
            null
        }
    }

    // --- Resolved getters (priority: remote > default; "update available" is handled by caller) ---

    fun sloganTitle(): String = cachedRemote?.sloganTitle ?: SLOGAN_TITLE_DEFAULT
    fun sloganSubtitle(): String = cachedRemote?.sloganSubtitle ?: SLOGAN_SUBTITLE_DEFAULT
    fun sloganFooter(): String = cachedRemote?.sloganFooter ?: SLOGAN_FOOTER_DEFAULT
    fun updaterWarning(): String = cachedRemote?.updaterWarning ?: UPDATER_WARNING_DEFAULT
    fun popupWarning(): String = cachedRemote?.popupWarning ?: POPUP_WARNING_DEFAULT
}
