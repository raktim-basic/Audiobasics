package com.rkd.audiobasics.api.account

import org.json.JSONObject

/**
 * What the UI is allowed to know about the signed-in account. Deliberately contains no
 * cookies or tokens, so it can never end up in a log line or a screen by accident.
 *
 * [accountId] is the stable identity used later to tell "same account" from "different
 * account" (login/logout rules, upload resume) — never the display name.
 */
data class AccountProfile(
    val accountId: String,
    val name: String,
    val email: String?,
    val handle: String?,
    val photoUrl: String?
)

sealed interface AccountState {
    /** No account linked. The app behaves exactly as it did before v2.8. */
    data object LoggedOut : AccountState

    /**
     * Signed in. The session was accepted the last time it was checked.
     * [libraryLinked] is true once this device's library has been reconciled with the account
     * (the login rules have run and been answered); until then nothing syncs.
     */
    data class LoggedIn(val profile: AccountProfile, val libraryLinked: Boolean = false) : AccountState

    /** YouTube stopped accepting the stored session. The user has to sign in again. */
    data class SessionExpired(val profile: AccountProfile) : AccountState
}

/**
 * Everything needed to make authenticated YouTube Music requests. The cookie is effectively
 * the account password: it lives only in [AuthStore] (encrypted) and in memory, and is never
 * logged or shown.
 */
internal data class AccountSession(
    val cookie: String,
    val visitorData: String,
    /** Raw DATASYNC_ID from the page; may contain "||". */
    val dataSyncId: String,
    /** SESSION_INDEX from the page, sent as X-Goog-AuthUser. */
    val authUser: String,
    val profile: AccountProfile,
    val libraryLinked: Boolean = false
) {
    fun toJson(): String = JSONObject()
        .put("v", 1)
        .put("cookie", cookie)
        .put("visitorData", visitorData)
        .put("dataSyncId", dataSyncId)
        .put("authUser", authUser)
        .put("accountId", profile.accountId)
        .put("name", profile.name)
        .put("email", profile.email)
        .put("handle", profile.handle)
        .put("photoUrl", profile.photoUrl)
        .put("linked", libraryLinked)
        .toString()

    companion object {
        fun fromJson(text: String): AccountSession {
            val o = JSONObject(text)
            return AccountSession(
                cookie = o.getString("cookie"),
                visitorData = o.getString("visitorData"),
                dataSyncId = o.optString("dataSyncId"),
                authUser = o.optString("authUser").ifBlank { "0" },
                profile = AccountProfile(
                    accountId = o.getString("accountId"),
                    name = o.getString("name"),
                    email = o.optString("email").ifBlank { null },
                    handle = o.optString("handle").ifBlank { null },
                    photoUrl = o.optString("photoUrl").ifBlank { null }
                ),
                libraryLinked = o.optBoolean("linked", false)
            )
        }
    }
}
