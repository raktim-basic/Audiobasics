package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

/**
 * Shown once (see MusicViewModel.showAppLinksNudge) on API 31+ if Android's automatic
 * verification of Audiobasics Links hasn't taken effect. There's no way for the app to grant
 * itself this — only the user can, via Settings — so this just explains why and offers a
 * shortcut straight to the right screen instead of the generic app-info page.
 */
@Composable
fun AppLinksNudgeDialog(
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onEnable: () -> Unit,
    onDismiss: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)

    Dialog(onDismissRequest = {
        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
        onDismiss()
    }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Text(
                    text = "Open Audiobasics Links directly",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = textColor
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Shared song and album links can open straight in Audiobasics " +
                        "instead of a browser. Android needs your one-time permission for this " +
                        "app to handle raktim-basic.github.io links.",
                    fontSize = 14.sp,
                    color = subTextColor,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onDismiss()
                    }) {
                        Text("Not now", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = subTextColor)
                    }
                    TextButton(onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onEnable()
                    }) {
                        Text("Enable", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = Color.Red)
                    }
                }
            }
        }
    }
}
