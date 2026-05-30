package com.yt.lite.api.potoken

import org.json.JSONArray
import android.util.Base64

class PoTokenResult(
    val playerRequestPoToken: String,
    val streamingDataPoToken: String,
)

class PoTokenException(message: String) : Exception(message)
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception {
    return if (error.contains("SyntaxError"))
        BadWebViewException(error)
    else
        PoTokenException(error)
}

fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = JSONArray(rawChallengeData)

    val challengeData = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
        val descrambled = descramble(scrambled.getString(1))
        JSONArray(descrambled)
    } else {
        scrambled.getJSONArray(0)
    }

    val program = challengeData.getString(4)
    val globalName = challengeData.getString(5)

    return buildString {
        append("{")
        append("program: \"$program\",")
        append("globalName: \"$globalName\",")
        append("}")
    }
}

fun u8ToBase64(poToken: String): String {
    val bytes = poToken.split(",")
        .map { it.trim().toUByte().toByte() }
        .toByteArray()
    return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        .replace("+", "-")
        .replace("/", "_")
        .trimEnd('=')
}

private fun descramble(scrambledChallenge: String): String {
    val base64Mod = scrambledChallenge
        .replace("-", "+")
        .replace("_", "/")
        .padEnd(scrambledChallenge.length + (4 - scrambledChallenge.length % 4) % 4, '=')

    val decodedBytes = Base64.decode(base64Mod, Base64.DEFAULT)
    return decodedBytes.map { (it + 97).toByte() }.toByteArray().decodeToString()
}
