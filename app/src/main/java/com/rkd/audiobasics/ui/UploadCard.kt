package com.rkd.audiobasics.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.api.account.LibraryUpload
import com.rkd.audiobasics.api.account.UploadState
import com.rkd.audiobasics.api.account.UploadSummary
import com.rkd.audiobasics.ui.theme.AmoledBlack
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

private const val MAX_SHOWN_FAILURES = 15

/** "about 12 min left" — rounded, and never pretending to be exact. */
private fun etaText(seconds: Long?): String? {
    seconds ?: return null
    return when {
        seconds < 60 -> "less than a minute left"
        seconds < 90 * 60 -> "about ${(seconds + 30) / 60} min left"
        else -> "about ${"%.1f".format(seconds / 3600.0)} h left"
    }
}

/**
 * The library upload on the Account page: progress with an honest estimate while it runs, why it
 * stopped and a Resume button when it's paused, and the report (including anything the account
 * refused) when it's finished. Shows nothing when there is no upload.
 */
@Composable
internal fun UploadCard(isDarkMode: Boolean, hapticsEnabled: Boolean) {
    val context = LocalContext.current
    val state by LibraryUpload.state.collectAsState()
    var confirmDiscard by remember { mutableStateOf(false) }

    val s = state
    if (s is UploadState.Idle) return

    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val surfaceColor = if (isDarkMode) AmoledBlack else Color.White
    val warn = Color(0xFFE57373)

    fun tap() {
        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
    }

    @Composable
    fun Title(text: String) {
        Text(text, fontFamily = NothingFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textColor)
    }

    @Composable
    fun Line(text: String, color: Color = subTextColor) {
        Text(text, fontFamily = NothingFont, fontSize = 13.sp, color = color, modifier = Modifier.padding(top = 4.dp))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        when (s) {
            is UploadState.Idle -> Unit

            is UploadState.Running -> {
                Title("Uploading your library")
                Line(s.step, textColor)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Red,
                    trackColor = if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)
                )
                val percent = if (s.total > 0) s.done * 100 / s.total else 0
                Line("$percent% · ${etaText(s.etaSeconds) ?: "working out the time"} · syncing slowly on purpose")
                s.note?.let { Line(it, warn) }
                Line("Keep Audiobasics open. Pausing is always safe.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { tap(); LibraryUpload.pause() }) {
                        Text("Pause", fontFamily = NothingFont, color = textColor)
                    }
                }
            }

            is UploadState.Paused -> {
                Title("Upload paused")
                Line(s.message, warn)
                if (s.total > 0) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { s.done.toFloat() / s.total },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Red,
                        trackColor = if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)
                    )
                    Line("${s.done * 100 / s.total}% done. Progress is saved.")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { tap(); confirmDiscard = true }) {
                        Text("Discard", fontFamily = NothingFont, color = subTextColor)
                    }
                    TextButton(onClick = { tap(); LibraryUpload.resume() }) {
                        Text("Resume", fontFamily = NothingFont, color = Color.Red)
                    }
                }
            }

            is UploadState.Finished -> FinishedReport(
                summary = s.summary,
                textColor = textColor,
                subTextColor = subTextColor,
                warn = warn,
                context = context,
                onDone = { tap(); LibraryUpload.dismissFinished() }
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White,
            title = { Text("Discard the upload?", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = textColor) },
            text = {
                Text(
                    "The upload stops for good. What was already uploaded stays in your account, and your library on this device is unchanged. You can sign out afterwards.",
                    fontFamily = NothingFont,
                    fontSize = 14.sp,
                    color = subTextColor
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; LibraryUpload.discard() }) {
                    Text("Discard", fontFamily = NothingFont, color = warn)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text("Keep it", fontFamily = NothingFont, color = textColor)
                }
            }
        )
    }
}

@Composable
private fun FinishedReport(
    summary: UploadSummary,
    textColor: Color,
    subTextColor: Color,
    warn: Color,
    context: Context,
    onDone: () -> Unit
) {
    fun lineOf(what: String, total: Int, failed: Int) = "$what: ${total - failed} of $total"

    Text("Upload finished", fontFamily = NothingFont, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textColor)
    Spacer(Modifier.height(6.dp))
    listOf(
        lineOf("Liked songs", summary.likesTotal, summary.likesFailed),
        lineOf("Saved albums", summary.albumsTotal, summary.albumsFailed),
        "Playlists: ${summary.playlistsCreated} of ${summary.playlistsTotal}",
        lineOf("Playlist songs", summary.playlistSongsTotal, summary.playlistSongsFailed)
    ).forEach {
        Text(it, fontFamily = NothingFont, fontSize = 13.sp, color = subTextColor, modifier = Modifier.padding(top = 2.dp))
    }

    if (summary.failures.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(
            "${summary.failures.size} could not be added to your account:",
            fontFamily = NothingFont, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = warn
        )
        summary.failures.take(MAX_SHOWN_FAILURES).forEach {
            Text("• ${it.label}", fontFamily = NothingFont, fontSize = 12.sp, color = subTextColor, modifier = Modifier.padding(top = 2.dp))
        }
        if (summary.failures.size > MAX_SHOWN_FAILURES) {
            Text(
                "…and ${summary.failures.size - MAX_SHOWN_FAILURES} more (Copy list has all of them)",
                fontFamily = NothingFont, fontSize = 12.sp, color = subTextColor, modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "They are still on this device for now, but they aren't in your account, so the next refresh (pull down, Sync now, or the next app start) removes them here too, along with their saved audio. Copy the list first if you want to look for them again.",
            fontFamily = NothingFont, fontSize = 12.sp, color = warn
        )
    }
    summary.notes.forEach {
        Text(it, fontFamily = NothingFont, fontSize = 12.sp, color = subTextColor, modifier = Modifier.padding(top = 6.dp))
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (summary.failures.isNotEmpty()) {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Songs that were not uploaded", summary.failureText()))
                Toast.makeText(context, "List copied", Toast.LENGTH_SHORT).show()
            }) {
                Text("Copy list", fontFamily = NothingFont, color = textColor)
            }
        }
        TextButton(onClick = onDone) {
            Text("Done", fontFamily = NothingFont, color = Color.Red)
        }
    }
}
