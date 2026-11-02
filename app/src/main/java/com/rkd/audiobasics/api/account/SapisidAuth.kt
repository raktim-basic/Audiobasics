package com.rkd.audiobasics.api.account

import java.security.MessageDigest

/**
 * Builds the Authorization header YouTube's web clients send with a signed-in session.
 *
 * For each of the three PAPISID cookies the header carries
 *   <SCHEME> <unixSeconds>_<sha1Hex("<unixSeconds> <cookieValue> <origin>")>
 * where the schemes are SAPISIDHASH, SAPISID1PHASH and SAPISID3PHASH. The hash is time-based,
 * so it has to be rebuilt for every request, never cached. The origin in the hash has to be
 * the same as the Origin header sent with the request.
 */
internal object SapisidAuth {

    const val ORIGIN = "https://music.youtube.com"

    fun parseCookies(cookie: String): Map<String, String> =
        cookie.split(';')
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
            }
            .toMap()

    /** A session without one of these cookies can't sign requests, so it isn't a login. */
    fun hasAuthCookie(cookie: String): Boolean {
        val cookies = parseCookies(cookie)
        return !cookies["SAPISID"].isNullOrBlank() || !cookies["__Secure-3PAPISID"].isNullOrBlank()
    }

    /** Null when the cookie has no usable PAPISID value. */
    fun authorizationHeader(
        cookie: String,
        origin: String = ORIGIN,
        nowSeconds: Long = System.currentTimeMillis() / 1000
    ): String? {
        val cookies = parseCookies(cookie)
        fun value(name: String) = cookies[name]?.takeIf { it.isNotBlank() }

        // Plain SAPISID is what SAPISIDHASH is normally built from; __Secure-3PAPISID is
        // the same secret under another name and is the fallback.
        val primary = value("SAPISID") ?: value("__Secure-3PAPISID") ?: return null

        val parts = mutableListOf("SAPISIDHASH" to primary)
        value("__Secure-1PAPISID")?.let { parts += "SAPISID1PHASH" to it }
        value("__Secure-3PAPISID")?.let { parts += "SAPISID3PHASH" to it }

        return parts.joinToString(" ") { (scheme, secret) ->
            "$scheme ${nowSeconds}_${sha1Hex("$nowSeconds $secret $origin")}"
        }
    }

    private fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
