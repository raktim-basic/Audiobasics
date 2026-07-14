package com.rkd.audiobasics.utils

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
 * Backed by ab-configs/header.json — a small, dedicated config repo (raktim-basic/ab-configs)
 * separate from the main app repo. Currently used by the homescreen header (line1/line2);
 * the migration-message fields below remain wired in for any future migration banner but
 * aren't populated by header.json today, so they'll keep resolving to their defaults until
 * ab-configs adds those keys.
 */
object MigrationMessageProvider {

    private const val REMOTE_CONTENT_URL =
        "https://raw.githubusercontent.com/raktim-basic/ab-configs/main/header.json"

    // --- Default fallback copy -------------------------------------------------

    const val SLOGAN_TITLE_DEFAULT = "A big change is coming"
    const val SLOGAN_SUBTITLE_DEFAULT = "Check the updater for details"
    const val SLOGAN_FOOTER_DEFAULT = "one-time change, won't happen again"

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
    // remotely editable.
    const val HOME_HEADER_LINE1_DEFAULT = "No recommendation bs"
    const val HOME_HEADER_LINE2_DEFAULT = "Own your taste"

    // --- Remote fetch ------------------------------------------------------------

    private var cachedRemote: RemoteMessages? = null

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
     * Attempts to fetch remote message overrides. Returns null on any failure,
     * in which case callers should fall back to the hardcoded defaults.
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
                popupWarning = json.optString("popupWarning").takeIf { it.isNotBlank() },
                homeHeaderLine1 = json.optString("line1").takeIf { it.isNotBlank() },
                homeHeaderLine2 = json.optString("line2").takeIf { it.isNotBlank() }
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
    fun homeHeaderLine1(): String = cachedRemote?.homeHeaderLine1 ?: HOME_HEADER_LINE1_DEFAULT
    fun homeHeaderLine2(): String = cachedRemote?.homeHeaderLine2 ?: HOME_HEADER_LINE2_DEFAULT
}
