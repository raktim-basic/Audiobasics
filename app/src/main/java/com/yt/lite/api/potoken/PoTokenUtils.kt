package com.yt.lite.api.potoken

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class PoTokenResult(val playerRequestPoToken: String, val streamingDataPoToken: String)
class PoTokenException(message: String) : Exception(message)
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception =
    if (error.contains("SyntaxError")) BadWebViewException(error) else PoTokenException(error)

fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = Json.parseToJsonElement(rawChallengeData).jsonArray
    val challengeData = if (scrambled.size > 1 && scrambled[1].jsonPrimitive.isString) {
        val descrambled = descramble(scrambled[1].jsonPrimitive.content)
        Json.parseToJsonElement(descrambled).jsonArray
    } else {
        scrambled[0].jsonArray
    }
    
    val program = challengeData[4].jsonPrimitive.content
    val globalName = challengeData[5].jsonPrimitive.content
    return "{ program: \"$program\", globalName: \"$globalName\" }"
}

fun u8ToBase64(poToken: String): String {
    val bytes = poToken.split(",").map { it.trim().toUByte().toByte() }.toByteArray()
    return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        .replace("+", "-").replace("/", "_").trimEnd('=')
}

private fun descramble(scrambledChallenge: String): String {
    val base64Mod = scrambledChallenge.replace("-", "+").replace("_", "/")
        .padEnd(scrambledChallenge.length + (4 - scrambledChallenge.length % 4) % 4, '=')
    val decodedBytes = Base64.decode(base64Mod, Base64.DEFAULT)
    return decodedBytes.map { (it + 97).toByte() }.toByteArray().decodeToString()
}
