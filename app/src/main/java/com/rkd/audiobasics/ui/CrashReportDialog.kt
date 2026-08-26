package com.rkd.audiobasics.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.rkd.audiobasics.utils.CrashReporter

/**
 * Shown once, on the launch right after a crash, if CrashReporter found a saved report
 * from the previous run. Lets the user copy it to the clipboard to send back for
 * debugging. The report is cleared either way once this is dismissed, so it only ever
 * surfaces once per crash.
 */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReporter.readLastCrash(context)) }
    val current = report ?: return

    fun dismiss() {
        CrashReporter.clearLastCrash(context)
        report = null
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("Audiobasics crashed last time") },
        text = { Text("Copy the crash report so it can be sent back for debugging?") },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AudiobasicsCrashReport", current))
                dismiss()
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) { Text("Dismiss") }
        }
    )
}
