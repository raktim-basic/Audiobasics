package com.rkd.audiobasics.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

/**
 * Small floating dialog shown while the update APK downloads. The Cancel button or back press
 * calls [onCancel], which aborts the download. Tapping outside does nothing, so a stray tap
 * can't throw away a download that's part-way through.
 *
 * [percent] is a provider, not a value: MorphPopup captures its content lambda once when it
 * opens, so the live number has to be read inside the content.
 */
@Composable
fun DownloadProgressDialog(
    percent: () -> Int,
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: Context,
    onCancel: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val trackColor = if (isDarkMode) Color(0xFF3A3A3A) else Color(0xFFD0D0D0)

    MorphPopup(
        onDismissRequest = onCancel,
        placement = MorphPlacement.Center,
        containerColor = bgColor,
        dismissOnOutsideTap = false
    ) { close ->
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = "Downloading update : ${percent()}%",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = textColor
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(trackColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(percent().coerceIn(0, 100) / 100f)
                        .fillMaxHeight()
                        .background(Color.Red)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    close()
                }) {
                    Text("Cancel", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = Color.Red)
                }
            }
        }
    }
}

/**
 * Shown from the moment the APK is handed to Android's installer until the app is replaced (the
 * process then dies) or the install is cancelled / fails. Android's own confirm screen closes as
 * soon as the user taps Install, and the real install then runs in the background for a few
 * seconds — without this the user would just be left looking at an idle app, unsure whether
 * anything is happening. Tapping outside does nothing; back dismisses it (the install carries on).
 */
@Composable
fun InstallingDialog(
    isDarkMode: Boolean,
    onDismiss: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val trackColor = if (isDarkMode) Color(0xFF3A3A3A) else Color(0xFFD0D0D0)

    MorphPopup(
        onDismissRequest = onDismiss,
        placement = MorphPlacement.Center,
        containerColor = bgColor,
        dismissOnOutsideTap = false
    ) { _ ->
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = "Installing update...",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = textColor
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Audiobasics will close by itself when it's done. Open it again afterwards.",
                fontFamily = NothingFont,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = subTextColor
            )
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color.Red,
                trackColor = trackColor
            )
        }
    }
}

/** Popup showing the latest GitHub release's notes (the release body, lightly de-markdowned). */
@Composable
fun ChangelogDialog(
    version: String,
    body: String,
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: Context,
    onDismiss: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val lines = parseChangelog(body)

    MorphPopup(
        onDismissRequest = onDismiss,
        placement = MorphPlacement.Center,
        containerColor = bgColor
    ) { close ->
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = "Changelogs : $version",
                fontFamily = NothingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.Red
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (lines.isEmpty()) {
                    Text(
                        text = "No changelog was provided for this release.",
                        fontSize = 14.sp,
                        color = subTextColor
                    )
                } else {
                    lines.forEach { line ->
                        when {
                            line.text.isEmpty() -> Spacer(Modifier.height(8.dp))
                            line.isHeader -> Text(
                                text = line.text,
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = textColor,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                            else -> Text(
                                text = line.text,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = textColor
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    close()
                }) {
                    Text("Close", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = Color.Red)
                }
            }
        }
    }
}

private data class ChangelogLine(val text: String, val isHeader: Boolean)

private val bulletRegex = Regex("^[-*+]\\s+")
private val linkRegex = Regex("\\[([^\\]]+)]\\([^)]*\\)")

/** GitHub release bodies are Markdown; this strips the syntax that would otherwise show up
 *  as stray symbols (#, **, `, [text](url)) and turns list markers into bullets. */
private fun parseChangelog(md: String): List<ChangelogLine> {
    val out = mutableListOf<ChangelogLine>()
    for (raw in md.lines()) {
        var t = raw.trim()
        if (t.isEmpty()) {
            if (out.isNotEmpty() && out.last().text.isNotEmpty()) out.add(ChangelogLine("", false))
            continue
        }
        val header = t.startsWith("#")
        if (header) t = t.trimStart('#').trim()
        val bullet = !header && bulletRegex.containsMatchIn(t)
        if (bullet) t = t.replaceFirst(bulletRegex, "")
        t = t.replace(linkRegex, "$1").replace("**", "").replace("__", "").replace("`", "")
        out.add(ChangelogLine(if (bullet) "• $t" else t, header))
    }
    while (out.isNotEmpty() && out.last().text.isEmpty()) out.removeAt(out.lastIndex)
    return out
}
