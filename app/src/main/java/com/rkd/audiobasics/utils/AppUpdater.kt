package com.rkd.audiobasics.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * In-app updater plumbing: downloads the release APK into the app's cache and hands it to
 * Android's PackageInstaller. No FileProvider is needed — the APK bytes are streamed straight
 * into an install session, and [InstallResultReceiver] launches Android's confirm screen.
 *
 * Updating in place only works because every build is signed with the same keystore.
 */
object AppUpdater {
    private const val APK_NAME = "Audiobasics-update.apk"

    /**
     * Downloads [url] into cacheDir/updates/, reporting whole-percent progress through
     * [onProgress] (called from a background thread, only when the percent changes).
     * Cancelling the calling coroutine aborts the download and deletes the partial file.
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        expectedSize: Long,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, APK_NAME)
        if (target.exists()) target.delete()

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty response")
                val total = body.contentLength().takeIf { it > 0 } ?: expectedSize

                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var readTotal = 0L
                        var lastPct = -1
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            readTotal += n
                            if (total > 0) {
                                val pct = ((readTotal * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
                if (total > 0 && target.length() != total) throw IOException("Incomplete download")
            }
            target
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    /** Streams [apk] into a PackageInstaller session and commits it. Android then shows its
     *  own install confirmation (via [InstallResultReceiver]). */
    suspend fun installApk(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Lets Android skip the confirm screen when it's allowed to (later self-updates);
            // otherwise it falls back to the normal prompt.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("audiobasics.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pi.intentSender)
        }
    }
}

/** Receives the PackageInstaller session result. */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { /* the app is replaced and restarted by Android */ }
            PackageInstaller.STATUS_FAILURE_ABORTED -> { /* user cancelled the install */ }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(
                    context,
                    "Update failed" + if (msg.isNullOrBlank()) "" else " : $msg",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
