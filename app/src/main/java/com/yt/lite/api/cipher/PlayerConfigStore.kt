package com.yt.lite.api.cipher

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File

/**
 * Owns the player-config table at runtime: bundled asset as the offline default, overlaid
 * by the same JSON fetched from the zemer-cipher repo so rotated players are fixed without
 * an APK update. Parsing/validation is delegated to [PlayerConfigParser]; only validated
 * payloads ever replace the in-memory map or touch the disk cache.
 *
 * Read path is lock-free: lookups hit an immutable map behind a @Volatile reference that
 * refreshes swap wholesale.
 */
object PlayerConfigStore {
    private const val TAG = "Metrolist_CipherConfig"
    private const val ASSET_NAME = "player_configs.json"

    // Points at zemer-cipher upstream: every device pulls zemer's live, CDN-validated
    // configs automatically (6h TTL + failure-triggered self-heal), so a player rotation
    // zemer has already solved is fixed fleet-wide without an APK release.
    private const val REMOTE_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

    // Mirrors PlayerJsFetcher.CACHE_TTL_MS.
    private const val REFRESH_TTL_MS = 6 * 60 * 60 * 1000L

    // Failure-triggered refreshes are rate-limited so a player that is unknown both locally
    // and remotely doesn't turn every song into a GitHub request.
    private const val FORCE_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L

    // Note: names must not start with "player_" — PlayerJsFetcher purges "player_*" from
    // the shared cache dir on every player-JS refresh.
    private const val CACHE_FILE = "configs_remote.json"
    private const val META_FILE = "configs_remote.meta"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var bundledConfigs: Map<String, FunctionNameExtractor.HardcodedPlayerConfig> = emptyMap()

    @Volatile
    private var mergedConfigs: Map<String, FunctionNameExtractor.HardcodedPlayerConfig> = emptyMap()

    @Volatile
    private var lastForcedAttemptMs = 0L

    @Volatile
    private var lastAttemptReachedServer = false

    private val refreshMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    /**
     * Synchronous: loads the bundled asset and, if present and valid, the last-good cached
     * remote copy. Cheap and guarantees configs exist before any lookup.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext

        bundledConfigs = when (val result = parseSource("bundled asset") { loadBundledJson(context) }) {
            null -> emptyMap()
            else -> result
        }
        if (bundledConfigs.isEmpty()) {
            Timber.tag(TAG).e("Bundled $ASSET_NAME missing or invalid — config table starts empty")
        } else {
            Timber.tag(TAG).d("Loaded bundled configs (${bundledConfigs.size} hashes)")
        }

        applyCachedOverlay()
    }

    internal fun applyCachedOverlay() {
        val cached = parseSource("cached remote copy") { cacheFile()?.takeIf { it.exists() }?.readText() }
        mergedConfigs = if (cached != null) {
            Timber.tag(TAG).d("Overlaying cached remote configs (${cached.size} hashes)")
            PlayerConfigParser.merge(bundledConfigs, cached)
        } else {
            cacheFile()?.delete()
            metaFile()?.delete()
            bundledConfigs
        }
    }

    /** Non-blocking TTL-gated refresh, kicked once from CipherDeobfuscator.initialize(). */
    fun scheduleStartupRefresh() {
        scope.launch {
            try {
                refreshIfStale()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Startup config refresh failed: ${e.message}")
            }
        }
    }

    fun get(hash: String): FunctionNameExtractor.HardcodedPlayerConfig? {
        val configs = mergedConfigs
        if (configs.isEmpty()) {
            Timber.tag(TAG).w("Config table is empty (initialize not called or bundled asset broken)")
        }
        return configs[hash]
    }

    fun knownHashes(): Set<String> = mergedConfigs.keys

    /**
     * Failure-triggered refresh: called when a player-hash lookup misses. Returns true iff
     * [missingHash] is now in the table after the fetch.
     */
    suspend fun forceRefresh(missingHash: String): Boolean = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            if (mergedConfigs.containsKey(missingHash)) {
                Timber.tag(TAG).d("forceRefresh: $missingHash arrived via concurrent refresh")
                return@withLock true
            }

            val now = System.currentTimeMillis()
            if (now - lastForcedAttemptMs < FORCE_REFRESH_COOLDOWN_MS) {
                Timber.tag(TAG).d("forceRefresh skipped (cooldown)")
                return@withLock false
            }
            lastForcedAttemptMs = now
            fetchAndApply()
            if (!lastAttemptReachedServer) lastForcedAttemptMs = 0L
            mergedConfigs.containsKey(missingHash)
        }
    }

    private suspend fun refreshIfStale() {
        val lastFetchMs = readMeta()?.second ?: 0L
        if (System.currentTimeMillis() - lastFetchMs < REFRESH_TTL_MS) {
            Timber.tag(TAG).d("Remote configs fresh (fetched ${System.currentTimeMillis() - lastFetchMs} ms ago)")
            return
        }
        withContext(Dispatchers.IO) {
            refreshMutex.withLock { fetchAndApply() }
        }
    }

    private fun fetchAndApply(): Boolean {
        lastAttemptReachedServer = false
        return try {
            val etag = readMeta()?.first
            val request = Request.Builder()
                .url(REMOTE_URL)
                .header("User-Agent", "Mozilla/5.0")
                .apply { if (!etag.isNullOrEmpty()) header("If-None-Match", etag) }
                .build()

            httpClient.newCall(request).execute().use { response ->
                lastAttemptReachedServer = true
                if (response.code == 304) {
                    Timber.tag(TAG).d("Remote configs unchanged (304)")
                    writeMeta(etag.orEmpty(), System.currentTimeMillis())
                    return false
                }
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("Remote config fetch HTTP ${response.code} — keeping previous configs")
                    return false
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    Timber.tag(TAG).w("Remote config fetch returned empty body — keeping previous configs")
                    return false
                }

                val remote = when (val result = PlayerConfigParser.parse(body)) {
                    is PlayerConfigParser.ParseResult.Failure -> {
                        Timber.tag(TAG).w("Remote configs rejected: ${result.reason} — keeping previous configs")
                        return false
                    }
                    is PlayerConfigParser.ParseResult.Success -> {
                        if (result.skippedEntries.isNotEmpty()) {
                            Timber.tag(TAG).w("Remote configs: skipped invalid entries ${result.skippedEntries}")
                        }
                        result.configs
                    }
                }

                applyRemote(remote, body, response.header("ETag").orEmpty())
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Remote config fetch failed: ${e.message} — keeping previous configs")
            false
        }
    }

    internal fun applyRemote(
        remote: Map<String, FunctionNameExtractor.HardcodedPlayerConfig>,
        body: String,
        etag: String,
    ): Boolean {
        val merged = PlayerConfigParser.merge(bundledConfigs, remote)
        val changed = merged != mergedConfigs
        mergedConfigs = merged
        Timber.tag(TAG).d("Remote configs applied (${remote.size} hashes, merged=${merged.size}, changed=$changed)")

        try {
            cacheFile()?.let { writeAtomic(it, body) }
            writeMeta(etag, System.currentTimeMillis())
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not persist remote configs (kept in memory): ${e.message}")
        }
        return changed
    }

    private fun parseSource(
        label: String,
        read: () -> String?,
    ): Map<String, FunctionNameExtractor.HardcodedPlayerConfig>? {
        val text = try {
            read() ?: return null
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read $label: ${e.message}")
            return null
        }
        return when (val result = PlayerConfigParser.parse(text)) {
            is PlayerConfigParser.ParseResult.Failure -> {
                Timber.tag(TAG).w("Rejected $label: ${result.reason}")
                null
            }
            is PlayerConfigParser.ParseResult.Success -> {
                if (result.skippedEntries.isNotEmpty()) {
                    Timber.tag(TAG).w("$label: skipped invalid entries ${result.skippedEntries}")
                }
                result.configs
            }
        }
    }

    private fun loadBundledJson(context: Context): String? =
        context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }

    private fun cacheDir(): File? {
        val context = appContext ?: return null
        return File(context.filesDir, "cipher_cache").apply { if (!exists()) mkdirs() }
    }

    private fun cacheFile(): File? = cacheDir()?.let { File(it, CACHE_FILE) }

    private fun metaFile(): File? = cacheDir()?.let { File(it, META_FILE) }

    private fun readMeta(): Pair<String, Long>? {
        return try {
            val file = metaFile()?.takeIf { it.exists() } ?: return null
            val lines = file.readText().split("\n")
            if (lines.size < 2) return null
            val lastFetchMs = lines[1].toLongOrNull() ?: return null
            lines[0] to lastFetchMs
        } catch (e: Exception) {
            null
        }
    }

    private fun writeMeta(etag: String, lastFetchMs: Long) {
        try {
            metaFile()?.let { writeAtomic(it, "$etag\n$lastFetchMs") }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not write config meta: ${e.message}")
        }
    }

    internal fun writeAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.writeText(content)
            tmp.delete()
        }
    }
}
