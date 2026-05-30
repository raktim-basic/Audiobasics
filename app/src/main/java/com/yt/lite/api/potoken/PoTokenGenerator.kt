package com.yt.lite.api.potoken

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PoTokenGenerator {
    private const val TAG = "PoTokenGenerator"

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    suspend fun getWebClientPoToken(context: Context, videoId: String, sessionId: String, forceRecreate: Boolean = false): PoTokenResult? {
        val (poTokenGenerator, streamingPot, hasBeenRecreated) = webPoTokenGenLock.withLock {
            val shouldRecreate = forceRecreate || webPoTokenGenerator == null || webPoTokenSessionId != sessionId
            if (shouldRecreate) {
                Log.d(TAG, "Recreating PoTokenWebView instance. Old session: $webPoTokenSessionId, New: $sessionId")
                webPoTokenGenerator?.close()
                webPoTokenGenerator = PoTokenWebView.getNewPoTokenGenerator(context)
                webPoTokenSessionId = sessionId
                webPoTokenStreamingPot = webPoTokenGenerator!!.generatePoToken(sessionId)
            }
            Triple(webPoTokenGenerator!!, webPoTokenStreamingPot!!, shouldRecreate)
        }

        val playerPot = try {
            poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                Log.e(TAG, "Failed to generate player token even after recreate", throwable)
                throw throwable
            } else {
                Log.w(TAG, "Token generation failed, forcing recreate and retrying...")
                return getWebClientPoToken(context, videoId, sessionId, forceRecreate = true)
            }
        }

        return PoTokenResult(playerPot, streamingPot)
    }
}
