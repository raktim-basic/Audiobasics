package com.rkd.audiobasics.api.account

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The outcome of an account request. Every failure is its own case, so callers can never
 * confuse "no connection" with "signed out" and wipe or refuse something by mistake.
 */
sealed interface AccountResult<out T> {
    data class Success<T>(val value: T) : AccountResult<T>

    /** YouTube explicitly rejected the session (HTTP 401, or a signed-out account menu). */
    data object SessionExpired : AccountResult<Nothing>

    /** The request never completed: offline, DNS failure, timeout. */
    data class NetworkError(val message: String) : AccountResult<Nothing>

    /** YouTube answered with an error that does NOT clearly mean "signed out" (429, 5xx, ...). */
    data class ServerError(val code: Int, val message: String) : AccountResult<Nothing>

    /** YouTube answered, but not in a shape we understand (format change, empty body). */
    data class ParseError(val message: String) : AccountResult<Nothing>
}

internal data class AccountInfo(
    val name: String,
    val email: String?,
    val handle: String?,
    val photoUrl: String?
)

/**
 * Authenticated requests to YouTube Music's internal API. All calls block, so callers
 * must be on a background dispatcher.
 *
 * Nothing in here ever logs the cookie, the Authorization header or a response body.
 */
internal object AccountClient {

    private const val TAG = "Account"
    private const val BASE = "https://music.youtube.com/youtubei/v1"

    // These mirror the private constants in Innertube.kt. If the client version is ever
    // bumped there, bump it here too (a later phase can share one definition).
    private const val CLIENT_NAME = "WEB_REMIX"
    private const val CLIENT_VERSION = "1.20260520.01.00"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    private val API_KEY = buildString {
        append("AIzaSyC9XL3ZjWdd")
        append("Xya6X74dJoCTL-WE")
        append("YFDNX30")
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun post(
        session: AccountSession,
        endpoint: String,
        body: JSONObject = JSONObject()
    ): AccountResult<JSONObject> =
        postRaw(session.cookie, session.visitorData, session.authUser, endpoint, body)

    fun postRaw(
        cookie: String,
        visitorData: String,
        authUser: String,
        endpoint: String,
        body: JSONObject = JSONObject()
    ): AccountResult<JSONObject> {
        val authorization = SapisidAuth.authorizationHeader(cookie)
            ?: return AccountResult.SessionExpired

        body.put(
            "context",
            JSONObject()
                .put(
                    "client",
                    JSONObject()
                        .put("clientName", CLIENT_NAME)
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", "en")
                        .put("gl", "US")
                        .put("visitorData", visitorData)
                )
                .put("user", JSONObject().put("lockedSafetyMode", false))
        )

        val request = Request.Builder()
            .url("$BASE/$endpoint?key=$API_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Origin", SapisidAuth.ORIGIN)
            .addHeader("X-Origin", SapisidAuth.ORIGIN)
            .addHeader("Referer", "${SapisidAuth.ORIGIN}/")
            .addHeader("X-YouTube-Client-Name", "67")
            .addHeader("X-YouTube-Client-Version", CLIENT_VERSION)
            .addHeader("X-Goog-AuthUser", authUser)
            .addHeader("X-Goog-Visitor-Id", visitorData)
            .addHeader("Authorization", authorization)
            .addHeader("Cookie", cookie)
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        val (code, text) = try {
            http.newCall(request).execute().use { response ->
                response.code to response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            Timber.tag(TAG).w("$endpoint: network error (${e.javaClass.simpleName})")
            return AccountResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }

        val json = try {
            if (text.isBlank()) null else JSONObject(text)
        } catch (_: Exception) {
            null
        }

        if (code == 401) {
            Timber.tag(TAG).w("$endpoint: HTTP 401")
            return AccountResult.SessionExpired
        }
        if (code !in 200..299) {
            val status = json?.optJSONObject("error")?.optString("status").orEmpty()
            Timber.tag(TAG).w("$endpoint: HTTP $code ${status}".trim())
            return if (status == "UNAUTHENTICATED") {
                AccountResult.SessionExpired
            } else {
                AccountResult.ServerError(code, status.ifBlank { "HTTP $code" })
            }
        }
        if (json == null) {
            Timber.tag(TAG).w("$endpoint: HTTP $code but the body was empty or not JSON")
            return AccountResult.ParseError("Empty or invalid response")
        }
        return AccountResult.Success(json)
    }

    /** Who is signed in, according to YouTube — also the cheapest "is this session alive?" check. */
    fun fetchAccountInfo(
        cookie: String,
        visitorData: String,
        authUser: String
    ): AccountResult<AccountInfo> =
        postRaw(cookie, visitorData, authUser, "account/account_menu")
            .flatMap { parseAccountInfo(it) }

    private fun parseAccountInfo(json: JSONObject): AccountResult<AccountInfo> {
        val menu = json.optJSONArray("actions")?.optJSONObject(0)
            ?.optJSONObject("openPopupAction")?.optJSONObject("popup")
            ?.optJSONObject("multiPageMenuRenderer")
            // No menu at all means the response format changed — that is NOT the same as
            // being signed out, so it must never be reported as an expired session.
            ?: return AccountResult.ParseError("Account menu missing from response")

        // A signed-out account menu has the menu but no active-account header.
        val header = menu.optJSONObject("header")?.optJSONObject("activeAccountHeaderRenderer")
            ?: return AccountResult.SessionExpired

        fun runsText(key: String): String? =
            header.optJSONObject(key)?.optJSONArray("runs")?.optJSONObject(0)
                ?.optString("text")?.takeIf { it.isNotBlank() }

        val name = runsText("accountName")
            ?: return AccountResult.ParseError("Account name missing from response")

        val photoUrl = header.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")
            ?.let { thumbs -> thumbs.optJSONObject(thumbs.length() - 1) }
            ?.optString("url")?.takeIf { it.isNotBlank() }

        return AccountResult.Success(
            AccountInfo(
                name = name,
                email = runsText("email"),
                handle = runsText("channelHandle"),
                photoUrl = photoUrl
            )
        )
    }
}

/** Chains a dependent step onto a success; every failure case passes through untouched. */
internal inline fun <A, B> AccountResult<A>.flatMap(next: (A) -> AccountResult<B>): AccountResult<B> =
    when (this) {
        is AccountResult.Success -> next(value)
        is AccountResult.SessionExpired -> this
        is AccountResult.NetworkError -> this
        is AccountResult.ServerError -> this
        is AccountResult.ParseError -> this
    }
