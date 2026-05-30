package com.yt.lite.api.potoken

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.annotation.MainThread
import kotlinx.coroutines.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PoTokenWebView private constructor(context: Context, private val continuation: Continuation<PoTokenWebView>) {
    private val webView = WebView(context)
    private val scope = MainScope()
    private val poTokenContinuations = ConcurrentHashMap<String, Continuation<String>>()
    private val exceptionHandler = CoroutineExceptionHandler { _, t -> onInitializationErrorCloseAndCancel(t) }

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val msg = "[WebView] ${consoleMessage.message()}"
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, msg)
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, msg)
                    else -> Log.d(TAG, msg)
                }
                return true
            }
        }

        scope.launch(exceptionHandler) {
            val challengeData = fetchChallengeData()
            val html = withContext(Dispatchers.IO) {
                context.assets.open("po_token.html").bufferedReader().use { it.readText() }
            }
            val finalHtml = html.replace("", "<script>loadBotGuard($challengeData);</script>")
            webView.loadDataWithBaseURL("https://www.youtube.com", finalHtml, "text/html", "utf-8", null)
        }
    }

    suspend fun generatePoToken(identifier: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            poTokenContinuations[identifier] = cont
            webView.evaluateJavascript("obtainPoToken(\"$identifier\");", null)
            cont.invokeOnCancellation { poTokenContinuations.remove(identifier) }
        }
    }

    @JavascriptInterface
    fun onPoTokenGenerated(identifier: String, token: String) {
        poTokenContinuations.remove(identifier)?.resume(u8ToBase64(token))
    }

    @JavascriptInterface
    fun onPoTokenGenerationFailed(identifier: String, error: String) {
        poTokenContinuations.remove(identifier)?.resumeWithException(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onBotGuardLoaded() {
        continuation.resume(this)
    }

    private suspend fun fetchChallengeData(): String = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player/ad_break?key=$GOOGLE_API_KEY")
            .headers(mapOf("User-Agent" to USER_AGENT, "Content-Type" to "application/json").toHeaders())
            .post("{\"videoId\":\"$REQUEST_KEY\",\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.20240909.00.00\"}}}".toRequestBody(null))
            .build()
            
        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty body")
        val bgConfig = JSONObject(body).optJSONArray("playerAds")
            ?.optJSONObject(0)?.optJSONObject("playerLegacyDesktopWatchAdsRenderer")
            ?.optJSONObject("playerAdParams")?.optJSONObject("showAdFragmentsCommand")
            ?.optJSONObject("generateIt")?.optString("bgConfig")
            ?: throw Exception("No bgConfig")
            
        parseChallengeData(bgConfig)
    }

    private fun onInitializationErrorCloseAndCancel(error: Throwable) {
        close()
        continuation.resumeWithException(error)
    }

    @MainThread
    fun close() {
        scope.cancel()
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }

    companion object {
        private const val TAG = "PoTokenWebView"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"
        private val httpClient = OkHttpClient.Builder().build()

        suspend fun getNewPoTokenGenerator(context: Context): PoTokenWebView = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val generator = PoTokenWebView(context, cont)
                cont.invokeOnCancellation { generator.close() }
            }
        }
    }
}
