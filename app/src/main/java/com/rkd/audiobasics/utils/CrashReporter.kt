package com.rkd.audiobasics.utils

import android.content.Context
import android.os.Build
import com.rkd.audiobasics.ui.DebugLogCollector
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists uncaught-exception crash reports to disk so they survive process death.
 *
 * DebugLogCollector (see DebugLogOverlay.kt) only buffers logs in memory — useful for
 * watching things live, but a real crash kills the process and takes that buffer with
 * it, leaving nothing to inspect afterward. This installs a global uncaught-exception
 * handler that writes the stack trace plus the last buffered log lines to a file in
 * internal storage right before the process dies, then chains to the previous default
 * handler so the crash still behaves normally otherwise.
 *
 * On the next launch, CrashReportDialog checks for a saved report and offers to copy
 * it — the point being to get a real stack trace off devices we can't personally test
 * on (e.g. a report relayed by someone else on a phone we don't have access to).
 */
object CrashReporter {
    private const val CRASH_DIR = "crash_reports"
    private const val CRASH_FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashReport(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Never let report-writing itself interfere with the crash surfacing normally.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashReport(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, CRASH_DIR).apply { mkdirs() }
        val file = File(dir, CRASH_FILE)

        val stackTraceWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stackTraceWriter))

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val report = buildString {
            appendLine("Audiobasics crash report")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("=== Stack trace ===")
            append(stackTraceWriter.toString())
            appendLine()
            appendLine("=== Recent logs ===")
            append(DebugLogCollector.exportText())
        }

        file.writeText(report)
    }

    /** Returns the last saved crash report, if one exists, without deleting it. */
    fun readLastCrash(context: Context): String? {
        val file = File(File(context.filesDir, CRASH_DIR), CRASH_FILE)
        return if (file.exists()) file.readText() else null
    }

    /** Call once the report has been shown/copied so it isn't surfaced again next launch. */
    fun clearLastCrash(context: Context) {
        File(File(context.filesDir, CRASH_DIR), CRASH_FILE).delete()
    }
}
