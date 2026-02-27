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
    private const val MIN_STORAGE_BYTES = 1L * 1024 * 1024 * 1024 // 1GB

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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

    fun getCacheFile(context: Context, songId: String): File {
        return File(getCacheDir(context), "$songId.m4a")
    }

    fun getThumbFile(context: Context, songId: String): File {
        return File(getThumbDir(context), "$songId.jpg")
    }

    fun isCached(context: Context, songId: String): Boolean {
        val file = getCacheFile(context, songId)
        return file.exists() && file.length() > 0
    }

    fun isThumbCached(context: Context, songId: String): Boolean {
        val file = getThumbFile(context, songId)
        return file.exists() && file.length() > 0
    }

    fun getCachedThumbPath(context: Context, songId: String): String? {
        val file = getThumbFile(context, songId)
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    fun hasEnoughStorage(context: Context): Boolean {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            available > MIN_STORAGE_BYTES
        } catch (e: Exception) {
            true
        }
    }

    fun getCacheSizeBytes(context: Context): Long {
        val audioBytes = getCacheDir(context).listFiles()?.sumOf { it.length() } ?: 0L
        val thumbBytes = getThumbDir(context).listFiles()?.sumOf { it.length() } ?: 0L
        return audioBytes + thumbBytes
    }

    fun getCacheSizeString(context: Context): String {
        val bytes = getCacheSizeBytes(context)
        return when {
            bytes <= 0 -> ""
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 ->
                "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
            else ->
                "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))}GB"
        }
    }

    suspend fun cacheThumbnail(context: Context, songId: String, url: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val thumbFile = getThumbFile(context, songId)
                if (thumbFile.exists() && thumbFile.length() > 0) return@withContext true

                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext false

                val bytes = response.body?.bytes() ?: return@withContext false

                // Decode and re-save as JPEG to save space
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext false

                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }

                Log.d("CacheManager", "Thumb cached: $songId")
                true
            } catch (e: Exception) {
                Log.e("CacheManager", "Thumb cache failed for $songId: ${e.message}")
                false
            }
        }
    }

    suspend fun cacheSong(context: Context, song: Song): CacheResult {
        return withContext(Dispatchers.IO) {
            try {
                if (!hasEnoughStorage(context)) {
                    return@withContext CacheResult.StorageLow
                }

                val cacheFile = getCacheFile(context, song.id)
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    // Audio cached — also cache thumbnail if missing
                    if (!isThumbCached(context, song.id) && song.thumbnail.isNotBlank()) {
                        cacheThumbnail(context, song.id, song.thumbnail)
                    }
                    return@withContext CacheResult.Success
                }

                // Get stream URL
                val streamUrl = Innertube.getStreamUrl(context, song.id)
                    ?: return@withContext CacheResult.Failed("Could not get stream URL")

                // Download audio
                val audioRequest = Request.Builder()
                    .url(streamUrl)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                val audioResponse = httpClient.newCall(audioRequest).execute()
                if (!audioResponse.isSuccessful) {
                    return@withContext CacheResult.Failed("HTTP ${audioResponse.code}")
                }

                val body = audioResponse.body
                    ?: return@withContext CacheResult.Failed("Empty response")

                val tempFile = File(getCacheDir(context), "${song.id}.tmp")

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (tempFile.length() == 0L) {
                    tempFile.delete()
                    return@withContext CacheResult.Failed("Downloaded file is empty")
                }

                if (!tempFile.renameTo(cacheFile)) {
                    tempFile.delete()
                    return@withContext CacheResult.Failed("Failed to save file")
                }

                // Cache thumbnail too
                if (song.thumbnail.isNotBlank()) {
                    cacheThumbnail(context, song.id, song.thumbnail)
                }

                Log.d("CacheManager", "Cached: ${song.title} (${cacheFile.length()} bytes)")
                CacheResult.Success

            } catch (e: Exception) {
                Log.e("CacheManager", "Cache failed for ${song.id}: ${e.message}")
                // Clean up temp file if exists
                File(getCacheDir(context), "${song.id}.tmp").delete()
                CacheResult.Failed(e.message ?: "Unknown error")
            }
        }
    }

    fun removeCachedSong(context: Context, songId: String) {
        getCacheFile(context, songId).let { if (it.exists()) it.delete() }
        getThumbFile(context, songId).let { if (it.exists()) it.delete() }
        File(getCacheDir(context), "$songId.tmp").let { if (it.exists()) it.delete() }
        Log.d("CacheManager", "Removed cache for: $songId")
    }

    fun getCachedFilePath(context: Context, songId: String): String? {
        val file = getCacheFile(context, songId)
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }
}

sealed class CacheResult {
    object Success : CacheResult()
    object StorageLow : CacheResult()
    data class Failed(val reason: String) : CacheResult()
}
