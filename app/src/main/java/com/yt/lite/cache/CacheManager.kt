package com.yt.lite.cache

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.yt.lite.api.Innertube
import com.yt.lite.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CacheManager {

    private const val CACHE_DIR = "audiobasics_cache"
    private const val MIN_STORAGE_BYTES = 1L * 1024 * 1024 * 1024 // 1GB

    fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getCacheFile(context: Context, songId: String): File {
        return File(getCacheDir(context), "$songId.m4a")
    }

    fun isCached(context: Context, songId: String): Boolean {
        return getCacheFile(context, songId).exists()
    }

    fun hasEnoughStorage(context: Context): Boolean {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            available > MIN_STORAGE_BYTES
        } catch (e: Exception) {
            true // assume enough if we can't check
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
                if (cacheFile.exists()) {
                    return@withContext CacheResult.Success
                }

                val url = Innertube.getStreamUrl(context, song.id)
                    ?: return@withContext CacheResult.Failed("Could not get stream URL")

                val connection = java.net.URL(url).openConnection()
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()

                val input = connection.getInputStream()
                val tempFile = File(getCacheDir(context), "${song.id}.tmp")

                input.use { ins ->
                    tempFile.outputStream().use { out ->
                        ins.copyTo(out)
                    }
                }

                tempFile.renameTo(cacheFile)
                Log.d("CacheManager", "Cached: ${song.title}")
                CacheResult.Success

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
    }

    fun getCachedFilePath(context: Context, songId: String): String? {
        val file = getCacheFile(context, songId)
        return if (file.exists()) file.absolutePath else null
    }
}

sealed class CacheResult {
    object Success : CacheResult()
    object StorageLow : CacheResult()
    data class Failed(val reason: String) : CacheResult()
}
