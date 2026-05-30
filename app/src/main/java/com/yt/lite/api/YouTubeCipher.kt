package com.yt.lite.api

import android.content.Context
import android.util.Log
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object YouTubeCipher {
    private const val TAG = "YouTubeCipher"
    private val client = OkHttpClient()
    private val lock = Mutex()

    private var cachedBaseJsUrl: String? = null
    private var cachedBaseJsContent: String? = null
    private var cachedNFunctionName: String? = null

    suspend fun solveN(context: Context, nParam: String, videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            ensureBaseJs(videoId)

            val baseJs = cachedBaseJsContent ?: return@withContext null
            val nFuncName = cachedNFunctionName ?: return@withContext null

            QuickJs.create().use { quickJs ->
                quickJs.evaluate(context.assets.open("solver/meriyah.js").bufferedReader().readText())
                quickJs.evaluate(context.assets.open("solver/astring.js").bufferedReader().readText())
                quickJs.evaluate(context.assets.open("solver/yt.solver.core.js").bufferedReader().readText())

                val safeBaseJs = baseJs.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
                quickJs.evaluate("var baseJsContent = '$safeBaseJs';")

                val script = "extractEjsNTransform(baseJsContent, '$nFuncName')('$nParam')"
                return@use quickJs.evaluate(script) as? String
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to solve N parameter: ${e.message}", e)
            null
        }
    }

    private suspend fun ensureBaseJs(videoId: String) {
        lock.withLock {
            if (cachedBaseJsContent != null) return

            try {
                val request = Request.Builder()
                    .url("https://www.youtube.com/embed/$videoId")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: return
                
                val jsUrlMatch = Regex("""\"jsUrl\":\"(.*?base\.js)\"""").find(html)
                    ?: Regex("""src=\"(.*?base\.js)\"""").find(html)
                
                var jsPath = jsUrlMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: return
                if (jsPath.startsWith("/")) jsPath = "https://www.youtube.com$jsPath"

                Log.d(TAG, "Downloading new base.js: $jsPath")
                val jsRequest = Request.Builder().url(jsPath).build()
                val jsResponse = client.newCall(jsRequest).execute()
                val jsContent = jsResponse.body?.string() ?: return

                val nFuncNameMatch = Regex("""\.get\(\"n\"\)\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\([a-zA-Z0-9]\)""").find(jsContent)
                    ?: Regex("""\b([a-zA-Z0-9$]+)\s*=\s*function\(\s*([a-zA-Z0-9$]+)\s*\)\s*\{\s*var\s+([a-zA-Z0-9$]+)\s*=\s*\2\.split\(\"\"\);\s*var\s+([a-zA-Z0-9$]+)\s*=\s*\[.*?\];""").find(jsContent)

                cachedBaseJsUrl = jsPath
                cachedBaseJsContent = jsContent
                cachedNFunctionName = nFuncNameMatch?.groupValues?.get(1) ?: ""
                
                Log.d(TAG, "Successfully extracted base.js and N-Function Name: $cachedNFunctionName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download/parse base.js: ${e.message}")
            }
        }
    }
    
    fun invalidateCache() {
        lock.tryLock()
        cachedBaseJsContent = null
        cachedBaseJsUrl = null
        cachedNFunctionName = null
        if (lock.isLocked) lock.unlock()
    }
}
