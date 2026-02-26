package com.yt.lite.cache

import android.content.Context
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
import java.util.concurrent.TimeUnit

object CacheManager {

    private const val CACHE_DIR = "audiobasics_cache"
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

    fun getCacheFile(context: Context, songId: String): File {
        return File(getCacheDir(context), "$songId.m4a")
    }

    fun isCached(context: Context, songId: String): Boolean {
        val file = getCacheFile(context, songId)
        return file.exists() && file.length() > 0
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
        return getCacheDir(context)
            .listFiles()
            ?.sumOf { it.length() }
            ?: 0L
    }

    fun getCacheSizeString(context: Context): String {
        val bytes = getCacheSizeBytes(context)
        return when {
            bytes <= 0 -> ""
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))}GB"
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
                    return@withContext CacheResult.Success
                }

                // Get stream URL
                val streamUrl = Innertube.getStreamUrl(context, song.id)
                    ?: return@withContext CacheResult.Failed("Could not get stream URL")

                // Download using OkHttp
                val request = Request.Builder()
                    .url(streamUrl)
                    .addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext CacheResult.Failed("HTTP ${response.code}")
                }

                val body = response.body
                    ?: return@withContext CacheResult.Failed("Empty response")

                val tempFile = File(getCacheDir(context), "${song.id}.tmp")

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // Verify temp file has content
                if (tempFile.length() == 0L) {
                    tempFile.delete()
                    return@withContext CacheResult.Failed("Downloaded file is empty")
                }

                // Rename to final file
                if (tempFile.renameTo(cacheFile)) {
                    Log.d("CacheManager", "Cached: ${song.title} (${cacheFile.length()} bytes)")
                    CacheResult.Success
                } else {
                    tempFile.delete()
                    CacheResult.Failed("Failed to save file")
                }

            } catch (e: Exception) {
                Log.e("CacheManager", "Cache failed for ${song.id}: ${e.message}")
                CacheResult.Failed(e.message ?: "Unknown error")
            }
        }
    }

    fun removeCachedSong(context: Context, songId: String) {
        val file = getCacheFile(context, songId)
        if (file.exists()) {
            file.delete()
            Log.d("CacheManager", "Removed cache for: $songId")
        }
        // Also clean up any temp files
        val tempFile = File(getCacheDir(context), "$songId.tmp")
        if (tempFile.exists()) tempFile.delete()
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
