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
    private const val MIN_STORAGE_BYTES = 512L * 1024 * 1024 // 512MB — less strict

    // Aggressive client — large buffers, longer timeout for big files
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Fast client for thumbnails
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

    fun getCacheFile(context: Context, songId: String): File =
        File(getCacheDir(context), "$songId.m4a")

    fun getThumbFile(context: Context, songId: String): File =
        File(getThumbDir(context), "$songId.jpg")

    fun isCached(context: Context, songId: String): Boolean {
        val f = getCacheFile(context, songId)
        return f.exists() && f.length() > 0
    }

    fun isThumbCached(context: Context, songId: String): Boolean {
        val f = getThumbFile(context, songId)
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

    fun hasEnoughStorage(context: Context): Boolean {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong > MIN_STORAGE_BYTES
        } catch (e: Exception) { true }
    }

    fun getCacheSizeBytes(context: Context): Long {
        val audio = getCacheDir(context).listFiles()?.sumOf { it.length() } ?: 0L
        val thumb = getThumbDir(context).listFiles()?.sumOf { it.length() } ?: 0L
        return audio + thumb
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

    // Cache thumbnail — small fast download
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

    // Main cache function — aggressive with 3 retries
    suspend fun cacheSong(context: Context, song: Song): CacheResult =
        withContext(Dispatchers.IO) {
            if (!hasEnoughStorage(context)) return@withContext CacheResult.StorageLow

            val cacheFile = getCacheFile(context, song.id)

            // Already cached — just make sure thumb is done too
            if (cacheFile.exists() && cacheFile.length() > 0) {
                if (!isThumbCached(context, song.id) && song.thumbnail.isNotBlank()) {
                    cacheThumbnail(context, song.id, song.thumbnail)
                }
                return@withContext CacheResult.Success
            }

            val tempFile = File(getCacheDir(context), "${song.id}.tmp")

            // Try up to 3 times
            var lastError = ""
            repeat(3) { attempt ->
                try {
                    // Get fresh stream URL each attempt
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

                    // Stream directly to temp file with large buffer
                    val buffer = ByteArray(64 * 1024) // 64KB buffer
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

                    // Atomic rename
                    if (tempFile.renameTo(cacheFile)) {
                        // Also cache thumbnail in parallel
                        if (song.thumbnail.isNotBlank()) {
                            cacheThumbnail(context, song.id, song.thumbnail)
                        }
                        Log.d("CacheManager", "Cached ${song.title} — $bytesWritten bytes")
                        return@withContext CacheResult.Success
                    } else {
                        // renameTo failed — try copy + delete
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
                    // Small delay before retry
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
            }

            // All 3 attempts failed
            tempFile.delete()
            Log.e("CacheManager", "All attempts failed for ${song.title}: $lastError")
            CacheResult.Failed(lastError)
        }

    fun removeCachedSong(context: Context, songId: String) {
        getCacheFile(context, songId).let { if (it.exists()) it.delete() }
        getThumbFile(context, songId).let { if (it.exists()) it.delete() }
        File(getCacheDir(context), "$songId.tmp").let { if (it.exists()) it.delete() }
    }
}

sealed class CacheResult {
    object Success : CacheResult()
    object StorageLow : CacheResult()
    data class Failed(val reason: String) : CacheResult()
}
