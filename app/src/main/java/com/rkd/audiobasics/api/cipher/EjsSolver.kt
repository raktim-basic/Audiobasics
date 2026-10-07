package com.rkd.audiobasics.api.cipher

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Structural (AST-based) fallback solver — yt-dlp's EJS (https://github.com/yt-dlp/ejs).
 *
 * Why this exists: the config table and the regex heuristics both depend on the *shape* of
 * YouTube's player JS. EJS parses the player into an AST, finds the n/sig functions by
 * structure, and solves challenges by running them — so a player rotation or a new format
 * does not need a config entry or a regex change, only an occasional EJS bundle update
 * (replace the two files in assets/ejs and bump [EJS_VERSION]).
 *
 * Cost model: parsing a ~3 MB player takes several seconds once per player hash. The result
 * ("preprocessed player", much smaller) is persisted, so later launches skip the parse.
 * Preparation runs on this object's own scope, NOT under CipherDeobfuscator's 20 s timeouts;
 * callers that arrive early simply get null (and fall through to their other paths) while
 * preparation continues in the background.
 */
object EjsSolver {
    private const val TAG = "Metrolist_EjsSolver"

    /** Version of the bundled yt-dlp-ejs release in assets/ejs. Bump when replacing them. */
    const val EJS_VERSION = "0.8.0"

    private const val ASSET_LIB = "ejs/yt.solver.lib.min.js"
    private const val ASSET_CORE = "ejs/yt.solver.core.min.js"
    private const val PREPARE_TIMEOUT_MS = 90_000L
    private const val CALL_TIMEOUT_MS = 10_000L
    private const val FAILURE_RETRY_COOLDOWN_MS = 5 * 60 * 1000L
    private const val MAX_PREPROCESSED_FILES = 3
    private val HASH_RE = Regex("^[a-f0-9]{8}$")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private var job: Deferred<Session?>? = null
    private var jobHash: String? = null
    private var jobFailedAtMs = 0L

    /** Kick off (or reuse) preparation for [hash] without waiting for it. */
    fun prewarm(hash: String, playerProvider: suspend () -> String?) {
        if (!HASH_RE.matches(hash)) return
        ensureJob(hash, playerProvider)
    }

    /** Drops any session so the next call re-prepares from scratch (used on manual refresh). */
    fun reset() {
        val old = synchronized(lock) {
            val j = job
            job = null
            jobHash = null
            jobFailedAtMs = 0L
            j
        }
        scope.launch {
            runCatching { old?.takeIf { it.isCompleted }?.await()?.close() }
        }
    }

    /**
     * [playerProvider] is only invoked when a new preparation is needed and no preprocessed
     * copy exists on disk, so the 3 MB player string isn't loaded for every call.
     */
    suspend fun solveSig(
        hash: String, challenge: String, waitMs: Long = 8_000L, playerProvider: suspend () -> String?,
    ): String? = solve("sig", hash, challenge, waitMs, playerProvider)

    suspend fun solveN(
        hash: String, challenge: String, waitMs: Long = 4_000L, playerProvider: suspend () -> String?,
    ): String? = solve("n", hash, challenge, waitMs, playerProvider)

    private suspend fun solve(
        kind: String, hash: String, challenge: String, waitMs: Long, playerProvider: suspend () -> String?,
    ): String? {
        if (!HASH_RE.matches(hash)) return null
        return try {
            val session = withTimeoutOrNull(waitMs) { ensureJob(hash, playerProvider).await() }
            if (session == null) {
                Timber.tag(TAG).w("EJS not ready for $hash (kind=$kind) — caller should use its other paths")
                return null
            }
            val result = session.call(kind, challenge)
            Timber.tag(TAG).d("EJS $kind solved: in=${challenge.length} out=${result?.length}")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "EJS $kind failed: ${e.message}")
            null
        }
    }

    private fun ensureJob(hash: String, playerProvider: suspend () -> String?): Deferred<Session?> = synchronized(lock) {
        val existing = job
        if (existing != null && jobHash == hash) {
            val failed = jobFailedAtMs > 0L
            val cooledDown = System.currentTimeMillis() - jobFailedAtMs >= FAILURE_RETRY_COOLDOWN_MS
            if (!failed || !cooledDown) return existing
        }
        val old = existing
        val created = scope.async {
            runCatching { old?.takeIf { it.isCompleted }?.await()?.close() }
            val s = prepare(hash, playerProvider)
            if (s == null) synchronized(lock) { jobFailedAtMs = System.currentTimeMillis() }
            s
        }
        job = created
        jobHash = hash
        jobFailedAtMs = 0L
        created
    }

    // ── Preparation ───────────────────────────────────────────────────────────

    private suspend fun prepare(hash: String, playerProvider: suspend () -> String?): Session? {
        val started = System.currentTimeMillis()
        return try {
            val ctx = CipherDeobfuscator.appContext
            val dir = File(ctx.filesDir, "ejs").apply { mkdirs() }
            installBundles(ctx, dir)

            val prepFile = File(dir, "prep_${hash}_$EJS_VERSION.js")
            val usePrepared = prepFile.exists() && prepFile.length() > 0
            val dataFile = if (usePrepared) prepFile else File(dir, "raw_${hash}.js")
            if (!usePrepared) {
                val playerJs = playerProvider() ?: error("player JS unavailable")
                dataFile.writeText("window.__PREPARED=false;window.__DATA=${JSONObject.quote(playerJs)};")
            }
            Timber.tag(TAG).d("EJS prepare hash=$hash usePrepared=$usePrepared")

            val session = withContext(Dispatchers.Main) { Session(ctx, dir, dataFile.name) }
            try {
                val prepared = withTimeout(PREPARE_TIMEOUT_MS) { session.ready.await() }
                if (!usePrepared && prepared.isNotEmpty()) {
                    prepFile.writeText("window.__PREPARED=true;window.__DATA=${JSONObject.quote(prepared)};")
                    pruneOld(dir, keep = prepFile.name)
                }
                Timber.tag(TAG).d("EJS ready hash=$hash in ${System.currentTimeMillis() - started}ms")
                session
            } catch (e: Throwable) {
                session.close()
                if (usePrepared) prepFile.delete() // possibly stale/corrupt — rebuild next time
                throw e
            } finally {
                if (!usePrepared) dataFile.delete() // raw player is 3 MB; not needed after prepare
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "EJS prepare failed for $hash: ${e.message}")
            null
        }
    }

    private fun installBundles(ctx: Context, dir: File) {
        for ((asset, prefix) in listOf(ASSET_LIB to "lib", ASSET_CORE to "core")) {
            val target = File(dir, "$prefix-$EJS_VERSION.min.js")
            if (!target.exists() || target.length() == 0L) {
                ctx.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            dir.listFiles()?.filter { it.name.startsWith("$prefix-") && it.name != target.name }?.forEach { it.delete() }
        }
    }

    private fun pruneOld(dir: File, keep: String) {
        val preps = dir.listFiles { f -> f.name.startsWith("prep_") }?.sortedByDescending { it.lastModified() } ?: return
        preps.filter { it.name != keep }.drop(MAX_PREPROCESSED_FILES - 1).forEach { it.delete() }
    }

    // ── WebView session ───────────────────────────────────────────────────────

    private class Session @SuppressLint("SetJavaScriptEnabled") constructor(
        context: Context,
        dir: File,
        dataFileName: String,
    ) {
        /** Completes with the preprocessed player text ("" when it was loaded already-prepared). */
        val ready = CompletableDeferred<String>()

        private val webView = WebView(context)
        private val callMutex = Mutex()
        @Volatile
        private var pending: CompletableDeferred<String>? = null

        @Volatile
        private var closed = false

        init {
            webView.settings.javaScriptEnabled = true
            webView.settings.allowFileAccess = true
            webView.settings.blockNetworkLoads = true
            webView.addJavascriptInterface(Bridge(), "EjsBridge")
            webView.webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    val err = IllegalStateException("EJS render process gone")
                    ready.completeExceptionally(err)
                    pending?.completeExceptionally(err)
                    closed = true
                    runCatching { webView.destroy() }
                    return true
                }
            }
            val html = """<!DOCTYPE html><html><head>
<script src="lib-$EJS_VERSION.min.js"></script>
<script>Object.assign(globalThis, lib);</script>
<script src="core-$EJS_VERSION.min.js"></script>
<script>
var S = null;
function ejsInit() {
  try {
    var prep = window.__DATA;
    if (!window.__PREPARED) {
      var out = jsc({ type: 'player', player: window.__DATA, requests: [], output_preprocessed: true });
      prep = out.preprocessed_player;
    }
    S = { n: null, sig: null };
    Function('_result', prep)(S);
    if (typeof S.n !== 'function' || typeof S.sig !== 'function') {
      EjsBridge.onFail('solver functions missing (n=' + typeof S.n + ', sig=' + typeof S.sig + ')');
      return;
    }
    window.__DATA = null;
    EjsBridge.onReady(window.__PREPARED ? '' : prep);
  } catch (e) {
    EjsBridge.onFail(String((e && e.stack) || e));
  }
}
function ejsSolve(kind, value) {
  try {
    var r = S[kind](value);
    if (typeof r !== 'string' || r.length === 0) { EjsBridge.onSolveError('empty result'); return; }
    EjsBridge.onSolved(r);
  } catch (e) {
    EjsBridge.onSolveError(String((e && e.stack) || e));
  }
}
</script>
<script src="$dataFileName" onload="ejsInit()" onerror="EjsBridge.onFail('failed to load $dataFileName')"></script>
</head><body></body></html>"""
            webView.loadDataWithBaseURL("file://${dir.absolutePath}/", html, "text/html", "utf-8", null)
        }

        suspend fun call(kind: String, value: String): String? = callMutex.withLock {
            if (closed) return@withLock null
            val deferred = CompletableDeferred<String>()
            pending = deferred
            withContext(Dispatchers.Main) {
                val arg = JSONObject.quote(value)
                webView.evaluateJavascript("ejsSolve('$kind', $arg)", null)
            }
            try {
                withTimeout(CALL_TIMEOUT_MS) { deferred.await() }
            } finally {
                pending = null
            }
        }

        suspend fun close() {
            if (closed) return
            closed = true
            withContext(Dispatchers.Main) {
                runCatching {
                    webView.loadUrl("about:blank")
                    webView.removeAllViews()
                    webView.destroy()
                }
            }
        }

        private inner class Bridge {
            @JavascriptInterface
            fun onReady(prepared: String) {
                ready.complete(prepared)
            }

            @JavascriptInterface
            fun onFail(message: String) {
                Timber.tag(TAG).e("EJS init failed: ${message.take(500)}")
                ready.completeExceptionally(IllegalStateException(message.take(500)))
            }

            @JavascriptInterface
            fun onSolved(result: String) {
                pending?.complete(result)
            }

            @JavascriptInterface
            fun onSolveError(message: String) {
                Timber.tag(TAG).w("EJS solve error: ${message.take(300)}")
                pending?.completeExceptionally(IllegalStateException(message.take(300)))
            }
        }
    }
}
