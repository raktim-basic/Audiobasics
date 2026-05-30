package com.yt.lite.api.cipher

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object PlayerJsFetcher {
    private const val TAG = "PlayerJsFetcher"
    private val httpClient = OkHttpClient.Builder().build()
    
    var cachedPlayerJs: String? = null
        private set

    suspend fun fetchPlayerJs(videoId: String): String? = withContext(Dispatchers.IO) {
        if (cachedPlayerJs != null) return@withContext cachedPlayerJs

        try {
            val request = Request.Builder()
                .url("https://www.youtube.com/embed/$videoId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3")
                .build()

            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null

            val jsUrlMatch = Regex("""\"jsUrl\":\"(.*?base\.js)\"""").find(html)
                ?: Regex("""src=\"(.*?base\.js)\"""").find(html)

            var jsPath = jsUrlMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: return@withContext null
            if (jsPath.startsWith("/")) jsPath = "https://www.youtube.com$jsPath"

            Log.d(TAG, "Downloading player JS: $jsPath")
            val jsRequest = Request.Builder().url(jsPath).build()
            val jsResponse = httpClient.newCall(jsRequest).execute()
            val jsContent = jsResponse.body?.string()

            cachedPlayerJs = jsContent
            return@withContext jsContent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch player JS", e)
            null
        }
    }
}
