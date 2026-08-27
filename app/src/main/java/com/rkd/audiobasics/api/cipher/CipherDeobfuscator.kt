package com.rkd.audiobasics.api.cipher

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

object CipherDeobfuscator {
    private const val TAG = "Metrolist_CipherDeobfusc"

    lateinit var appContext: Context
        private set

    fun initialize(context: Context) {
        Timber.tag(TAG).d("CipherDeobfuscator initializing...")
        appContext = context.applicationContext
        // Load bundled player_configs.json and last-good cached remote copy synchronously,
        // then kick a background TTL-gated refresh from zemer-cipher.
        PlayerConfigStore.initialize(context)
        PlayerConfigStore.scheduleStartupRefresh()
        Timber.tag(TAG).d("CipherDeobfuscator initialized")
    }

    private var cipherWebView: CipherWebView? = null
    private var currentPlayerHash: String? = null

    // Prevents two coroutines from creating a CipherWebView simultaneously.
    private val deobfuscateMutex = Mutex()

    // Without a bound, a stale/broken player.js (YouTube rotates these periodically)
    // can leave a WebView JS call hanging indefinitely — Mutex.withLock releases the
    // lock in a finally block even on cancellation, so wrapping the whole locked
    // section in withTimeout guarantees the lock can never be stuck forever and every
    // future stream resolution or download queued behind it isn't either. Previously
    // the only way out of this was force-stopping the app.
    private const val CIPHER_OP_TIMEOUT_MS = 20_000L
    // The n-transform fast path below runs without the mutex, so it gets its own
    // shorter budget — matches PoTokenGenerator's existing timeout convention.
    private const val N_TRANSFORM_FAST_PATH_TIMEOUT_MS = 8_000L

    // Pre-warm the CipherWebView at startup so it's ready before the first song plays.
    // Without this, the first deobfuscation call bears the full ~8-10s WebView init cost,
    // causing the resolver to time out before the stream URL is ready.
    suspend fun warmUp() {
        Timber.tag(TAG).d("warmUp: pre-creating CipherWebView...")
        try {
            withTimeout(CIPHER_OP_TIMEOUT_MS) {
                deobfuscateMutex.withLock {
                    if (cipherWebView != null) {
                        Timber.tag(TAG).d("warmUp: CipherWebView already exists, skipping")
                        return@withLock
                    }
                    try {
                        val webView = getOrCreateWebView(forceRefresh = false)
                        if (webView != null) {
                            Timber.tag(TAG).d("warmUp: CipherWebView ready ✅ " +
                                    "(sig=${webView.sigFunctionAvailable} n=${webView.nFunctionAvailable})")
                        } else {
                            Timber.tag(TAG).w("warmUp: CipherWebView creation returned null")
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).w("warmUp failed (non-fatal, will retry on first use): ${e.message}")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Timber.tag(TAG).e("warmUp timed out after ${CIPHER_OP_TIMEOUT_MS}ms — clearing stale state")
            cleanupAfterTimeout()
        }
    }

    suspend fun deobfuscateStreamUrl(signatureCipher: String, videoId: String): String? =
        try {
            withTimeout(CIPHER_OP_TIMEOUT_MS) {
                deobfuscateMutex.withLock {
                    Timber.tag(TAG).d("=== DEOBFUSCATE STREAM URL ===")
                    Timber.tag(TAG).d("videoId: $videoId")
                    Timber.tag(TAG).d("signatureCipher length: ${signatureCipher.length}")
                    Timber.tag(TAG).d("signatureCipher preview: ${signatureCipher.take(100)}...")
                    try {
                        deobfuscateInternal(signatureCipher, videoId, isRetry = false)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Cipher deobfuscation failed, retrying with fresh JS: ${e.message}")
                        try {
                            PlayerJsFetcher.invalidateCache()
                            closeWebView()
                            deobfuscateInternal(signatureCipher, videoId, isRetry = true)
                        } catch (retryE: Exception) {
                            Timber.tag(TAG).e(retryE, "Cipher deobfuscation retry also failed: ${retryE.message}")
                            null
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // A stale/rotated player.js can leave the WebView hanging mid-call. Without
            // this, deobfuscateMutex stays locked forever and every song — streaming or
            // downloading — spins indefinitely until the app is force-stopped.
            Timber.tag(TAG).e("Cipher deobfuscation timed out after ${CIPHER_OP_TIMEOUT_MS}ms for videoId=$videoId — clearing stale state")
            cleanupAfterTimeout()
            null
        }

    private suspend fun deobfuscateInternal(
        signatureCipher: String,
        videoId: String,
        isRetry: Boolean
    ): String? {
        Timber.tag(TAG).d("deobfuscateInternal: videoId=$videoId, isRetry=$isRetry")

        val params = parseQueryParams(signatureCipher)
        val obfuscatedSig = params["s"]
        val sigParam = params["sp"] ?: "signature"
        val baseUrl = params["url"]

        Timber.tag(TAG).d("Parsed signatureCipher params:")
        Timber.tag(TAG).d("  s (obfuscated sig): ${obfuscatedSig?.take(30)}... (length=${obfuscatedSig?.length})")
        Timber.tag(TAG).d("  sp (sig param name): $sigParam")
        Timber.tag(TAG).d("  url: ${baseUrl?.take(80)}...")

        if (obfuscatedSig == null || baseUrl == null) {
            Timber.tag(TAG).e("Could not parse signatureCipher params")
            return null
        }

        val webView = getOrCreateWebView(forceRefresh = isRetry)
        if (webView == null) {
            Timber.tag(TAG).e("Failed to get/create CipherWebView")
            return null
        }

        Timber.tag(TAG).d("Calling webView.deobfuscateSignature()...")
        val deobfuscatedSig = webView.deobfuscateSignature(obfuscatedSig)
        Timber.tag(TAG).d("Deobfuscated signature: ${deobfuscatedSig.take(30)}... (length=${deobfuscatedSig.length})")

        val separator = if ("?" in baseUrl) "&" else "?"
        val finalUrl = "$baseUrl${separator}${sigParam}=${Uri.encode(deobfuscatedSig)}"

        Timber.tag(TAG).d("=== CIPHER DEOBFUSCATION SUCCESS ===")
        Timber.tag(TAG).d("Final URL length: ${finalUrl.length}")
        Timber.tag(TAG).d("Final URL preview: ${finalUrl.take(100)}...")

        return finalUrl
    }

    suspend fun transformNParamInUrl(url: String): String {
        return try {
            transformNInternal(url)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "N-transform failed, returning original URL: ${e.message}")
            url
        }
    }

    private suspend fun transformNInternal(url: String): String {
        val nMatch = Regex("[?&]n=([^&]+)").find(url) ?: return url

        val nValueEncoded = nMatch.groupValues[1]
        val nValue = Uri.decode(nValueEncoded)

        // N-transform uses the existing WebView — no new lock needed since
        // transformNParamInUrl is called after deobfuscateStreamUrl in the same flow
        val webView = cipherWebView ?: run {
            Timber.tag(TAG).w("No CipherWebView for n-transform, acquiring lock...")
            return try {
                withTimeout(CIPHER_OP_TIMEOUT_MS) {
                    deobfuscateMutex.withLock {
                        val wv = getOrCreateWebView(forceRefresh = false)
                        if (wv == null || !wv.nFunctionAvailable) {
                            Timber.tag(TAG).e("N-transform function not available")
                            return@withLock url
                        }
                        val transformed = wv.transformN(nValue)
                        Timber.tag(TAG).d("N-param: $nValue -> $transformed")
                        url.replaceFirst(Regex("([?&])n=[^&]+"), "$1n=${Uri.encode(transformed)}")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Timber.tag(TAG).e("N-transform (cold path) timed out after ${CIPHER_OP_TIMEOUT_MS}ms")
                cleanupAfterTimeout()
                url
            }
        }

        if (!webView.nFunctionAvailable) {
            Timber.tag(TAG).e("N-transform function not available")
            return url
        }

        val transformedN = try {
            withTimeout(N_TRANSFORM_FAST_PATH_TIMEOUT_MS) { webView.transformN(nValue) }
        } catch (e: TimeoutCancellationException) {
            Timber.tag(TAG).e("N-transform timed out after ${N_TRANSFORM_FAST_PATH_TIMEOUT_MS}ms — clearing stale state")
            cleanupAfterTimeout()
            return url
        }
        Timber.tag(TAG).d("N-param: $nValue -> $transformedN")

        return url.replaceFirst(
            Regex("([?&])n=[^&]+"),
            "$1n=${Uri.encode(transformedN)}"
        )
    }

    private suspend fun getOrCreateWebView(forceRefresh: Boolean): CipherWebView? {
        Timber.tag(TAG).d("getOrCreateWebView: forceRefresh=$forceRefresh, existing=${cipherWebView != null}")

        if (!forceRefresh && cipherWebView != null) {
            Timber.tag(TAG).d("Reusing existing CipherWebView (hash=$currentPlayerHash)")
            return cipherWebView
        }

        if (cipherWebView != null) {
            Timber.tag(TAG).d("Closing existing CipherWebView...")
            closeWebView()
        }

        Timber.tag(TAG).d("Fetching player JS...")
        val result = PlayerJsFetcher.getPlayerJs(forceRefresh = forceRefresh)
        if (result == null) {
            Timber.tag(TAG).e("Failed to get player JS")
            return null
        }
        val (playerJs, hash) = result
        Timber.tag(TAG).d("Got player JS: hash=$hash, length=${playerJs.length}")

        Timber.tag(TAG).d("Analyzing player JS for cipher functions (knownHash=$hash)...")
        var analysis = FunctionNameExtractor.analyzePlayerJs(playerJs, knownHash = hash)

        if (analysis.sigInfo == null && hash != null) {
            // Config for this hash isn't in the bundled asset — try pulling the latest
            // player_configs.json from zemer-cipher. Handles new player rotations
            // without requiring an APK update.
            Timber.tag(TAG).w("Sig info missing for hash=$hash, attempting PlayerConfigStore.forceRefresh...")
            val found = PlayerConfigStore.forceRefresh(hash)
            if (found) {
                Timber.tag(TAG).d("forceRefresh succeeded, re-analyzing player JS...")
                analysis = FunctionNameExtractor.analyzePlayerJs(playerJs, knownHash = hash)
            } else {
                Timber.tag(TAG).w("forceRefresh did not find config for hash=$hash")
            }
        }

        if (analysis.sigInfo == null) {
            Timber.tag(TAG).e("Could not extract signature function info from player JS")
            return null
        }

        if (analysis.nFuncInfo == null) {
            Timber.tag(TAG).w("Could not extract n-function info (will try brute-force)")
        }

        Timber.tag(TAG).d("Creating CipherWebView...")
        Timber.tag(TAG).d("  sig: ${analysis.sigInfo.name} expr=${analysis.sigInfo.jsExpression?.take(30)} (hardcoded=${analysis.sigInfo.isHardcoded})")
        Timber.tag(TAG).d("  nFunc: ${analysis.nFuncInfo?.name}[${analysis.nFuncInfo?.arrayIndex}] expr=${analysis.nFuncInfo?.jsExpression?.take(30)} (hardcoded=${analysis.nFuncInfo?.isHardcoded})")

        val webView = CipherWebView.create(
            context = appContext,
            playerJs = playerJs,
            sigInfo = analysis.sigInfo,
            nFuncInfo = analysis.nFuncInfo,
        )

        Timber.tag(TAG).d("CipherWebView created: nFunc=${webView.nFunctionAvailable} sigFunc=${webView.sigFunctionAvailable}")

        cipherWebView = webView
        currentPlayerHash = hash
        return webView
    }

    private suspend fun closeWebView() {
        withContext(Dispatchers.Main) { cipherWebView?.close() }
        cipherWebView = null
        currentPlayerHash = null
        Timber.tag(TAG).d("CipherWebView closed")
    }

    // Runs after a CIPHER_OP_TIMEOUT_MS/N_TRANSFORM_FAST_PATH_TIMEOUT_MS timeout fires.
    // By that point deobfuscateMutex has already been released (Mutex.withLock always
    // unlocks in a finally, even on cancellation), so this is a normal, safe lock
    // acquisition — not a race against whatever hung. Drops the cached player.js too,
    // not just the WebView, since a rotated/stale hash is the most likely cause of a
    // hang in the first place, and the next attempt should start from a clean slate.
    private suspend fun cleanupAfterTimeout() {
        try {
            deobfuscateMutex.withLock {
                PlayerJsFetcher.invalidateCache()
                closeWebView()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "cleanupAfterTimeout failed: ${e.message}")
        }
    }

    /**
     * Full manual reset: drops the cached player.js, closes the current CipherWebView,
     * and re-fetches + re-warms from scratch. This is what the Settings "Refresh" button
     * triggers, for when YouTube rotates player.js and playback/downloads are stuck
     * spinning — previously the only fix was force-stopping the app. Safe to call even
     * if something else is currently mid-hang: CIPHER_OP_TIMEOUT_MS bounds how long this
     * can possibly wait for the lock, since nothing can hold it forever anymore.
     */
    suspend fun forceReset(): Boolean {
        Timber.tag(TAG).w("forceReset: user-triggered — invalidating cache and re-warming CipherWebView")
        return try {
            withTimeout(CIPHER_OP_TIMEOUT_MS) {
                deobfuscateMutex.withLock {
                    PlayerJsFetcher.invalidateCache()
                    closeWebView()
                    getOrCreateWebView(forceRefresh = true) != null
                }
            }
        } catch (e: TimeoutCancellationException) {
            Timber.tag(TAG).e("forceReset timed out after ${CIPHER_OP_TIMEOUT_MS}ms")
            false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "forceReset failed: ${e.message}")
            false
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                result[Uri.decode(pair.substring(0, idx))] = Uri.decode(pair.substring(idx + 1))
            }
        }
        Timber.tag(TAG).i("parseQueryParams: ${result.keys.joinToString()}")
        return result
    }

    fun getDebugInfo(): Map<String, Any?> = mapOf(
        "hasWebView" to (cipherWebView != null),
        "playerHash" to currentPlayerHash,
        "nFunctionAvailable" to cipherWebView?.nFunctionAvailable,
        "sigFunctionAvailable" to cipherWebView?.sigFunctionAvailable,
        "discoveredNFuncName" to cipherWebView?.discoveredNFuncName,
        "usingHardcodedMode" to cipherWebView?.usingHardcodedMode,
    )
}
