package com.rkd.audiobasics.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

/**
 * Only shown at all when the (temporary, dev-tools-only) "show YouTube link on share" setting
 * is on — see AudiobasicsLinks.isYoutubeShareOptionEnabled. With it off, sharing just sends the
 * Audiobasics Link directly with no picker step, since there's only one option.
 *
 * Opened from the 3-dot menu, so it has no button of its own to grow out of — it morphs from
 * the screen center instead (MorphPlacement.Center, no anchor).
 */
@Composable
fun ShareChoiceDialog(
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onAudiobasicsLink: () -> Unit,
    onYoutubeLink: () -> Unit,
    onDismiss: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black

    MorphPopup(
        onDismissRequest = onDismiss,
        placement = MorphPlacement.Center,
        containerColor = bgColor
    ) { close ->
            fun dismiss() {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                close()
            }
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(
                    text = "Share",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.Red,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )

                ShareChoiceRow("Audiobasics Link", textColor, hapticsEnabled, context) {
                    dismiss(); onAudiobasicsLink()
                }
                ShareChoiceRow("YouTube Link", textColor, hapticsEnabled, context) {
                    dismiss(); onYoutubeLink()
                }
            }
        }
}

@Composable
private fun ShareChoiceRow(
    label: String,
    textColor: Color,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onClick()
            }
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(label, fontFamily = NothingFont, fontSize = 16.sp, color = textColor)
    }
}
