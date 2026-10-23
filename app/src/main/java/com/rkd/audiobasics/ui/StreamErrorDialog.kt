package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

/** One song that couldn't be played or downloaded, and why. */
data class StreamErrorEntry(
    val songId: String,
    val title: String,
    val artist: String,
    val loginRequired: Boolean,
    val log: String
)

/** What the error dialog shows. [isDownload] only changes the wording of the title. */
data class StreamErrorState(
    val isDownload: Boolean,
    val entries: List<StreamErrorEntry>
)

/**
 * Explains why playback or download failed, naming each song.
 *
 * - Songs YouTube says need an account share one "login required" message.
 * - Anything else shows its raw failure log so people can screenshot it for us.
 *
 * Uses a real Dialog window (like AppLinksNudgeDialog) rather than a MorphPopup so it always
 * appears above the player card, which is usually what's open when playback fails.
 */
@Composable
fun StreamErrorDialog(
    state: StreamErrorState,
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onDismiss: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val logBgColor = if (isDarkMode) Color(0xFF2B2B2B) else Color(0xFFE0E0E0)

    val loginEntries = state.entries.filter { it.loginRequired }
    val otherEntries = state.entries.filter { !it.loginRequired }

    val title = when {
        !state.isDownload -> "Couldn't play this song"
        state.entries.size == 1 -> "Couldn't download this song"
        else -> "Couldn't download ${state.entries.size} songs"
    }
    val loginMessage = if (loginEntries.size == 1) {
        "This song requires login to play, since login in Audiobasics is planned and not yet " +
            "implemented. You can listen to the video version :)"
    } else {
        "These songs require login to play, since login in Audiobasics is planned and not yet " +
            "implemented. You can listen to the video versions :)"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Text(
                    text = title,
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = textColor
                )

                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (loginEntries.isNotEmpty()) {
                        loginEntries.forEach { SongLine(it, textColor, subTextColor) }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = loginMessage,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = subTextColor
                        )
                    }

                    otherEntries.forEachIndexed { index, entry ->
                        if (index > 0 || loginEntries.isNotEmpty()) Spacer(Modifier.height(16.dp))
                        SongLine(entry, textColor, subTextColor)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = entry.log,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = textColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(logBgColor, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        )
                    }

                    if (otherEntries.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Screenshot it and send it on our Discord to help us",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = subTextColor
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onDismiss()
                    }) {
                        Text("OK", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = Color.Red)
                    }
                }
            }
        }
    }
}

@Composable
private fun SongLine(entry: StreamErrorEntry, textColor: Color, subTextColor: Color) {
    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        Text(
            text = entry.title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
        if (entry.artist.isNotBlank()) {
            Text(text = entry.artist, fontSize = 13.sp, color = subTextColor)
        }
    }
}
