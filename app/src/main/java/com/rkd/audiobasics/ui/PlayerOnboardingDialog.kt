package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.ui.theme.BodyFont
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.ui.theme.animatedBodyWeight
import com.rkd.audiobasics.ui.theme.bodyFontAt
import com.rkd.audiobasics.utils.HapticUtils

/**
 * One-time first-launch popup (see MusicViewModel.showPlayerOnboarding) asking which player design
 * to use. Same locked popup style as AppLinksNudgeDialog: it has to be answered. Uses the same
 * segmented selector as Settings > Appearance. Designs are numbered like there:
 * 0 = OG, 1 = basics, 2 = Full.
 */
@Composable
fun PlayerOnboardingDialog(
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    initialDesign: Int,
    onConfirm: (Int) -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    var selected by remember { mutableStateOf(initialDesign) }

    MorphPopup(
        onDismissRequest = {},
        placement = MorphPlacement.Center,
        containerColor = bgColor,
        dismissOnOutsideTap = false,
        isLocked = { true }
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Column {
                Text(
                    text = "Choose your player",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = textColor
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "You can change this anytime in Settings > Appearance.",
                    fontFamily = BodyFont,
                    fontSize = 14.sp,
                    color = subTextColor,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isDarkMode) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(0 to "OG", 1 to "basics", 2 to "Full").forEach { (value, label) ->
                        val isSelected = selected == value
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) Color.Red else Color.Transparent)
                                .clickable {
                                    if (!isSelected && hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    selected = value
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val segWeight = animatedBodyWeight(isSelected)
                            Text(
                                text = label,
                                fontFamily = bodyFontAt(segWeight),
                                fontWeight = FontWeight(segWeight),
                                fontSize = 13.sp,
                                color = if (isSelected) Color.White else textColor
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onConfirm(selected)
                    }) {
                        Text("Continue", fontFamily = BodyFont, fontWeight = FontWeight.Bold, color = Color.Red)
                    }
                }
            }
        }
    }
}
