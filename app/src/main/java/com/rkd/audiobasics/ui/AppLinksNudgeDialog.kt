package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

/**
 * Shown once (see MusicViewModel.showAppLinksNudge) on API 31+ if Android's automatic
 * verification of Audiobasics Links hasn't taken effect. There's no way for the app to grant
 * itself this — only the user can, via Settings — so this walks through exactly how, and
 * stays up (no back-press/outside-tap dismiss, no skip option) until they do.
 */
@Composable
fun AppLinksNudgeDialog(
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onEnable: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Text(
                    text = "New feature: Audiobasics Link",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = textColor
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Audiobasics now has a new sharing system. Allow Audiobasics to " +
                        "open raktim-basic.github.io links for a seamless experience:",
                    fontSize = 14.sp,
                    color = subTextColor,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(16.dp))

                Column {
                    Text("1. Tap Enable", fontSize = 14.sp, color = textColor, lineHeight = 22.sp)
                    Text("2. Tap Add link", fontSize = 14.sp, color = textColor, lineHeight = 22.sp)
                    Text("3. Select raktim-basic.github.io", fontSize = 14.sp, color = textColor, lineHeight = 22.sp)
                }

                Spacer(Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
