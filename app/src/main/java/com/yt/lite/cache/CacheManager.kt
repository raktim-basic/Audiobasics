package com.yt.lite.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.yt.lite.api.Innertube
import com.yt.lite.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object CacheManager {

    private const val CACHE_DIR = "audiobasics_cache"
    private const val THUMB_DIR = "audiobasics_thumbs"
    private const val LYRICS_DIR = "audiobasics_lyrics"
    private const val MIN_STORAGE_BYTES = 512L * 1024 * 1024

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val thumbClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getThumbDir(context: Context): File {
        val dir = File(context.filesDir, THUMB_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getLyricsDir(context: Context): File {
        val dir = File(context.filesDir, LYRICS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getCacheFile(context: Context, songId: String): File =
        File(getCacheDir(context), "$songId.m4a")

    fun getThumbFile(context: Context, songId: String): File =
        File(getThumbDir(context), "$songId.jpg")

    fun getLyricsFile(context: Context, songId: String): File =
        File(getLyricsDir(context), "$songId.json")

    fun isCached(context: Context, songId: String): Boolean {
        val f = getCacheFile(context, songId)
        return f.exists() && f.length() > 0
    }

    fun isThumbCached(context: Context, songId: String): Boolean {
        val f = getThumbFile(context, songId)
        return f.exists() && f.length() > 0
    }

    fun isLyricsCached(context: Context, songId: String): Boolean {
        val f = getLyricsFile(context, songId)
        return f.exists() && f.length() > 0
    }

    fun getCachedThumbPath(context: Context, songId: String): String? {
        val f = getThumbFile(context, songId)
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    fun getCachedFilePath(context: Context, songId: String): String? {
        val f = getCacheFile(context, songId)
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    // Save lyrics as JSON: { synced: "[...]", plain: "...", hasSynced: bool }
    fun saveLyrics(context: Context, songId: String, lyricsJson: String) {
        try {
            val f = getLyricsFile(context, songId)
            f.writeText(lyricsJson)
        } catch (e: Exception) {
            Log.e("CacheManager", "Failed to save lyrics for $songId: ${e.message}")
        }
    }

    fun loadLyrics(context: Context, songId: String): String? {
        return try {
            val f = getLyricsFile(context, songId)
            if (f.exists() && f.length() > 0) f.readText() else null
        } catch (e: Exception) {
            Log.e("CacheManager", "Failed to load lyrics for $songId: ${e.message}")
            null
        }
    }

    fun hasEnoughStorage(context: Context): Boolean {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong > MIN_STORAGE_BYTES
        } catch (e: Exception) { true }
    }

    fun getCacheSizeBytes(context: Context): Long {
        val audio = getCacheDir(context).listFiles()?.sumOf { it.length() } ?: 0L
        val thumb = getThumbDir(context).listFiles()?.sumOf { it.length() } ?: 0L
        val lyrics = getLyricsDir(context).listFiles()?.sumOf { it.length() } ?: 0L
        return audio + thumb + lyrics
    }

    fun getCacheSizeString(context: Context): String {
        val bytes = getCacheSizeBytes(context)
        return when {
            bytes <= 0 -> ""
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 ->
                "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            else ->
                "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    suspend fun cacheThumbnail(context: Context, songId: String, url: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val thumbFile = getThumbFile(context, songId)
                if (thumbFile.exists() && thumbFile.length() > 0) return@withContext true
                if (url.isBlank()) return@withContext false

                val request = Request.Builder().url(url).build()
                val response = thumbClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext false

                val bytes = response.body?.bytes() ?: return@withContext false
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext false

                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                true
            } catch (e: Exception) {
                Log.e("CacheManager", "Thumb cache failed $songId: ${e.message}")
                false
            }
        }

    suspend fun cacheSong(context: Context, song: Song): CacheResult =
        withContext(Dispatchers.IO) {
            if (!hasEnoughStorage(context)) return@withContext CacheResult.StorageLow

            val cacheFile = getCacheFile(context, song.id)

            if (cacheFile.exists() && cacheFile.length() > 0) {
                if (!isThumbCached(context, song.id) && song.thumbnail.isNotBlank()) {
                    cacheThumbnail(context, song.id, song.thumbnail)
                }
                return@withContext CacheResult.Success
            }

            val tempFile = File(getCacheDir(context), "${song.id}.tmp")

            var lastError = ""
            repeat(3) { attempt ->
                try {
                    val streamUrl = Innertube.getStreamUrl(context, song.id)
                        ?: run {
                            lastError = "Could not get stream URL"
                            return@repeat
                        }

                    val request = Request.Builder()
                        .url(streamUrl)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .addHeader("Accept", "*/*")
                        .addHeader("Accept-Encoding", "identity")
                        .addHeader("Range", "bytes=0-")
                        .build()

                    val response = downloadClient.newCall(request).execute()
                    if (!response.isSuccessful && response.code != 206) {
                        lastError = "HTTP ${response.code}"
                        return@repeat
                    }

                    val body = response.body ?: run {
                        lastError = "Empty response body"
                        return@repeat
                    }

                    val buffer = ByteArray(64 * 1024)
                    var bytesWritten = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            var n: Int
                            while (input.read(buffer).also { n = it } != -1) {
                                output.write(buffer, 0, n)
                                bytesWritten += n
                            }
                            output.flush()
                        }
                    }

                    if (bytesWritten < 10_000) {
                        tempFile.delete()
                        lastError = "Downloaded file too small ($bytesWritten bytes)"
                        return@repeat
                    }

                    if (tempFile.renameTo(cacheFile)) {
                        if (song.thumbnail.isNotBlank()) {
                            cacheThumbnail(context, song.id, song.thumbnail)
                        }
                        Log.d("CacheManager", "Cached ${song.title} — $bytesWritten bytes")
                        return@withContext CacheResult.Success
                    } else {
                        tempFile.copyTo(cacheFile, overwrite = true)
                        tempFile.delete()
                        if (cacheFile.exists() && cacheFile.length() > 0) {
                            if (song.thumbnail.isNotBlank()) {
                                cacheThumbnail(context, song.id, song.thumbnail)
                            }
                            return@withContext CacheResult.Success
                        }
                        lastError = "Failed to save file"
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "Unknown error"
                    Log.w("CacheManager", "Attempt ${attempt + 1} failed for ${song.id}: $lastError")
                    tempFile.delete()
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
            }

            tempFile.delete()
            Log.e("CacheManager", "All attempts failed for ${song.title}: $lastError")
            CacheResult.Failed(lastError)
        }

    fun removeCachedSong(context: Context, songId: String) {
        getCacheFile(context, songId).let { if (it.exists()) it.delete() }
        getThumbFile(context, songId).let { if (it.exists()) it.delete() }
        getLyricsFile(context, songId).let { if (it.exists()) it.delete() }
        File(getCacheDir(context), "$songId.tmp").let { if (it.exists()) it.delete() }
    }
}

sealed class CacheResult {
    object Success : CacheResult()
    object StorageLow : CacheResult()
    data class Failed(val reason: String) : CacheResult()
}
