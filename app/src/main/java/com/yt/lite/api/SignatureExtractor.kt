package com.yt.lite.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class CipherOperation(val type: String, val param: Int = 0)

object SignatureExtractor {

    private const val PREFS = "ytlite_player"
    private const val KEY_VERSION = "player_version"
    private const val KEY_OPERATIONS = "cipher_operations"
    private const val KEY_UPDATED_AT = "updated_at"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getVersion(ctx: Context): String? =
        getPrefs(ctx).getString(KEY_VERSION, null)

    fun getUpdatedAt(ctx: Context): Long =
        getPrefs(ctx).getLong(KEY_UPDATED_AT, 0L)

    fun isReady(ctx: Context): Boolean =
        getPrefs(ctx).getString(KEY_OPERATIONS, null) != null

    fun loadOperations(ctx: Context): List<CipherOperation> {
        val raw = getPrefs(ctx).getString(KEY_OPERATIONS, null) ?: return emptyList()
        return raw.split("|").mapNotNull { part ->
            val tokens = part.split(",")
            when (tokens.getOrNull(0)) {
                "reverse" -> CipherOperation("reverse")
                "splice" -> CipherOperation("splice", tokens.getOrNull(1)?.toIntOrNull() ?: 0)
                "swap" -> CipherOperation("swap", tokens.getOrNull(1)?.toIntOrNull() ?: 0)
                else -> null
            }
        }
    }

    suspend fun update(ctx: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Get YouTube homepage to find JS player URL
            val homeReq = Request.Builder()
                .url("https://www.youtube.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val homeBody = http.newCall(homeReq).execute().body?.string()
                ?: return@withContext Result.failure(Exception("Could not load YouTube homepage"))

            // Step 2: Extract JS player URL
            val playerUrlMatch = Regex("""(/s/player/[a-f0-9]+/player_ias\.vflset/en_US/base\.js)""")
                .find(homeBody)
            val playerPath = playerUrlMatch?.groupValues?.get(1)
                ?: return@withContext Result.failure(Exception("Could not find JS player URL"))

            val playerVersion = Regex("""/s/player/([a-f0-9]+)/""")
                .find(playerPath)?.groupValues?.get(1) ?: "unknown"

            // Step 3: Download the JS player
            val jsReq = Request.Builder()
                .url("https://www.youtube.com$playerPath")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val jsBody = http.newCall(jsReq).execute().body?.string()
                ?: return@withContext Result.failure(Exception("Could not download JS player"))

            // Step 4: Extract decipher function name
            val decipherFuncName = Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9${'$'}]+?)(?:\[(\d+)\])?\([a-zA-Z0-9]\)""")
                .find(jsBody)?.groupValues?.get(1)
                ?: Regex("""[a-zA-Z0-9${'$'}]+\s*=\s*function\([a-zA-Z0-9]+\)\{[a-zA-Z0-9]+=[a-zA-Z0-9]+\.split\(""\)""")
                    .find(jsBody)?.value?.substringBefore("=")?.trim()
                ?: return@withContext Result.failure(Exception("Could not find decipher function"))

            // Step 5: Extract the decipher function body
            val funcPattern = Regex(
                """${Regex.escape(decipherFuncName)}=function\([a-zA-Z0-9]+\)\{(.*?)\}""",
                RegexOption.DOT_MATCHES_ALL
            )
            val funcBody = funcPattern.find(jsBody)?.groupValues?.get(1)
                ?: return@withContext Result.failure(Exception("Could not extract decipher function body"))

            // Step 6: Find the helper object name
            val helperName = Regex("""([a-zA-Z0-9${'$'}]{2,3})\.[a-zA-Z0-9${'$'}]{2,3}\(""")
                .find(funcBody)?.groupValues?.get(1)
                ?: return@withContext Result.failure(Exception("Could not find helper object"))

            // Step 7: Extract helper object
            val helperPattern = Regex(
                """var\s+${Regex.escape(helperName)}\s*=\s*\{(.*?)\};""",
                RegexOption.DOT_MATCHES_ALL
            )
            val helperBody = helperPattern.find(jsBody)?.groupValues?.get(1)
                ?: return@withContext Result.failure(Exception("Could not find helper object body"))

            // Step 8: Map helper method names to operations
            val reverseFunc = Regex("""([a-zA-Z0-9${'$'}]+):function\([a-zA-Z0-9]+\)\{[^}]*reverse[^}]*\}""")
                .find(helperBody)?.groupValues?.get(1)
            val spliceFunc = Regex("""([a-zA-Z0-9${'$'}]+):function\([a-zA-Z0-9]+,[a-zA-Z0-9]+\)\{[^}]*splice[^}]*\}""")
                .find(helperBody)?.groupValues?.get(1)
            val swapFunc = Regex("""([a-zA-Z0-9${'$'}]+):function\([a-zA-Z0-9]+,[a-zA-Z0-9]+\)\{[^}]*[0]\)[^}]*\}""")
                .find(helperBody)?.groupValues?.get(1)

            // Step 9: Parse operations in order from function body
            val operations = mutableListOf<CipherOperation>()
            val callPattern = Regex("""${Regex.escape(helperName)}\.([a-zA-Z0-9${'$'}]+)\([a-zA-Z0-9]+,(\d+)\)""")
            for (match in callPattern.findAll(funcBody)) {
                val method = match.groupValues[1]
                val param = match.groupValues[2].toIntOrNull() ?: 0
                when (method) {
                    reverseFunc -> operations.add(CipherOperation("reverse"))
                    spliceFunc -> operations.add(CipherOperation("splice", param))
                    swapFunc -> operations.add(CipherOperation("swap", param))
                }
            }

            if (operations.isEmpty()) {
                return@withContext Result.failure(Exception("Could not extract cipher operations"))
            }

            // Step 10: Save everything
            val serialized = operations.joinToString("|") { op ->
                when (op.type) {
                    "reverse" -> "reverse"
                    else -> "${op.type},${op.param}"
                }
            }
            getPrefs(ctx).edit()
                .putString(KEY_VERSION, playerVersion)
                .putString(KEY_OPERATIONS, serialized)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply()

            Result.success(playerVersion)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun decipher(ctx: Context, signature: String): String {
        val ops = loadOperations(ctx)
        val chars = signature.toMutableList()
        for (op in ops) {
            when (op.type) {
                "reverse" -> chars.reverse()
                "splice" -> repeat(op.param) { chars.removeAt(0) }
                "swap" -> {
                    val temp = chars[0]
                    chars[0] = chars[op.param % chars.size]
                    chars[op.param % chars.size] = temp
                }
            }
        }
        return chars.joinToString("")
    }
}
