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
        .readTimeout(60, TimeUnit.SECONDS)
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
            // Step 1: Get player JS URL from YouTube
            val homeBody = fetch("https://www.youtube.com/")
                ?: return@withContext Result.failure(Exception("Could not load YouTube homepage"))

            val playerPath = listOf(
                Regex(""""jsUrl"\s*:\s*"(/s/player/[^"]+\.js)""""),
                Regex("""(/s/player/[a-f0-9]+/player_ias\.vflset/[^"']+\.js)"""),
                Regex("""(/s/player/[a-f0-9]+/[^"']*base\.js)""")
            ).firstNotNullOfOrNull { it.find(homeBody)?.groupValues?.get(1) }
                ?: return@withContext Result.failure(Exception("Could not find player URL"))

            val version = Regex("""/player/([a-f0-9]+)/""")
                .find(playerPath)?.groupValues?.get(1) ?: "unknown"

            // Step 2: Download JS
            val js = fetch("https://www.youtube.com$playerPath")
                ?: return@withContext Result.failure(Exception("Could not download player JS"))

            // Step 3: Find sig decipher function name
            // These patterns are from yt-dlp and NewPipe — most reliable known patterns
            val sigFuncName = listOf(
                Regex("""\.sig\s*\|\|\s*([a-zA-Z0-9${'$'}]+)\(decodeURIComponent"""),
                Regex("""["\']signature["\']\s*,\s*([a-zA-Z0-9${'$'}]+)\("""),
                Regex("""\.set\(["\']signature["\']\s*,\s*([a-zA-Z0-9${'$'}]+)\("""),
                Regex("""([a-zA-Z0-9${'$'}]{2,3})\s*=\s*function\([a-zA-Z]\)\s*\{\s*[a-zA-Z]\s*=\s*\1\.split\(""\)"""),
                Regex("""function\s+([a-zA-Z0-9${'$'}]{2,3})\s*\([a-zA-Z]\)\s*\{\s*[a-zA-Z]\s*=\s*[a-zA-Z]\.split\(""\)"""),
                Regex("""([a-zA-Z0-9${'$'}]+)\.split\(""\);[a-zA-Z0-9${'$'}]+\.[a-zA-Z0-9${'$'}]+\("""),
                Regex("""a=a\.split\(""\).*?return a\.join.*?\}.*?var ([a-zA-Z0-9${'$'}]+)=\{""")
            ).firstNotNullOfOrNull { regex ->
                regex.find(js)?.groupValues?.get(1)?.takeIf { it.length in 2..4 }
            } ?: return@withContext Result.failure(Exception("Could not find sig function. YouTube may have changed their player format."))

            // Step 4: Extract function body
            val escapedSig = Regex.escape(sigFuncName)
            val funcBody = listOf(
                Regex("""${escapedSig}=function\([a-zA-Z0-9_]+\)\{([^}]+(?:\{[^}]*\}[^}]*)*)\}"""),
                Regex("""function\s+${escapedSig}\s*\([^)]*\)\s*\{([^}]+(?:\{[^}]*\}[^}]*)*)\}""")
            ).firstNotNullOfOrNull { it.find(js)?.groupValues?.get(1) }
                ?: return@withContext Result.failure(Exception("Could not extract function body"))

            // Step 5: Get helper object name from the function body
            val helperName = listOf(
                Regex("""([a-zA-Z0-9${'$'}]{2,3})\.[a-zA-Z0-9${'$'}]+\([a-zA-Z0-9${'$'}]+,\d+\)"""),
                Regex("""([a-zA-Z0-9${'$'}]{2,3})\.[a-zA-Z0-9${'$'}]{2,3}\(""")
            ).firstNotNullOfOrNull { regex ->
                regex.find(funcBody)?.groupValues?.get(1)?.takeIf { it.length in 2..3 }
            } ?: return@withContext Result.failure(Exception("Could not find helper object name"))

            // Step 6: Extract helper object body
            val escapedHelper = Regex.escape(helperName)
            val helperBody = listOf(
                Regex("""var\s+${escapedHelper}\s*=\s*\{([\s\S]+?)\}\s*;"""),
                Regex("""${escapedHelper}\s*=\s*\{([\s\S]+?)\}\s*;""")
            ).firstNotNullOfOrNull { it.find(js)?.groupValues?.get(1) }
                ?: return@withContext Result.failure(Exception("Could not find helper object"))

            // Step 7: Identify operations
            val reverseFunc = Regex("""([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\([^)]*\)\s*\{[^}]*\.reverse\(\)[^}]*\}""")
                .find(helperBody)?.groupValues?.get(1)
            val spliceFunc = Regex("""([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\([^,)]+,[^)]+\)\s*\{[^}]*\.splice\([^}]*\}""")
                .find(helperBody)?.groupValues?.get(1)
            val swapFunc = Regex("""([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\([^,)]+,[^)]+\)\s*\{[^}]*\[0\][^}]*\}""")
                .find(helperBody)?.groupValues?.get(1)

            if (reverseFunc == null && spliceFunc == null && swapFunc == null) {
                return@withContext Result.failure(Exception("Could not identify any cipher operations in helper"))
            }

            // Step 8: Parse operation sequence
            val operations = mutableListOf<CipherOperation>()
            val callRegex = Regex("""${escapedHelper}\.([a-zA-Z0-9${'$'}]+)\([a-zA-Z0-9${'$'}]+,(\d+)\)""")
            for (match in callRegex.findAll(funcBody)) {
                val method = match.groupValues[1]
                val param = match.groupValues[2].toIntOrNull() ?: 0
                when (method) {
                    reverseFunc -> operations.add(CipherOperation("reverse"))
                    spliceFunc -> operations.add(CipherOperation("splice", param))
                    swapFunc -> operations.add(CipherOperation("swap", param))
                }
            }

            if (operations.isEmpty()) {
                return@withContext Result.failure(Exception("Could not parse operation sequence"))
            }

            // Step 9: Save
            val serialized = operations.joinToString("|") { op ->
                if (op.type == "reverse") "reverse" else "${op.type},${op.param}"
            }
            getPrefs(ctx).edit()
                .putString(KEY_VERSION, version)
                .putString(KEY_OPERATIONS, serialized)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply()

            Result.success(version)
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
                "splice" -> repeat(op.param) { if (chars.isNotEmpty()) chars.removeAt(0) }
                "swap" -> if (chars.isNotEmpty()) {
                    val i = op.param % chars.size
                    val temp = chars[0]
                    chars[0] = chars[i]
                    chars[i] = temp
                }
            }
        }
        return chars.joinToString("")
    }

    private fun fetch(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        http.newCall(req).execute().body?.string()
    } catch (e: Exception) { null }
}
