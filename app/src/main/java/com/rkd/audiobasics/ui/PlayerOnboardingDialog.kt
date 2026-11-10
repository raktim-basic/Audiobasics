package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.ui.theme.BodyFont
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

/**
 * One-time first-launch popup (see MusicViewModel.showPlayerOnboarding) asking which player design
 * to use. Same locked popup style as AppLinksNudgeDialog: it has to be answered. Designs are
 * numbered like Settings > Appearance: 0 = OG, 1 = basics, 2 = Full.
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf(0 to "OG", 1 to "basics", 2 to "Full").forEach { (design, label) ->
                        val isSelected = selected == design
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (!isSelected && hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                    selected = design
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            PlayerPreview(
                                design = design,
                                isDarkMode = isDarkMode,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.6f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) Color.Red else textColor.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = label,
                                fontFamily = NothingFont,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                color = if (isSelected) Color.Red else textColor
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

/** A tiny abstract drawing of each player design: OG card, basics (art-first card), Full sheet. */
@Composable
private fun PlayerPreview(design: Int, isDarkMode: Boolean, modifier: Modifier) {
    val ink = if (isDarkMode) Color.White else Color.Black
    val art = Color(0xFF8A8A8A)
    when (design) {
        0 -> Column(
            modifier = modifier
                .background(if (isDarkMode) Color(0xFF2A2A2A) else Color.White)
                .padding(8.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(art)
            )
            Spacer(Modifier.height(10.dp))
            PreviewBar(0.7f, 4.dp, ink.copy(alpha = 0.8f))
            Spacer(Modifier.height(4.dp))
            PreviewBar(0.45f, 3.dp, ink.copy(alpha = 0.4f))
            Spacer(Modifier.height(8.dp))
            PreviewBar(1f, 2.dp, ink.copy(alpha = 0.25f))
            Spacer(Modifier.weight(1f))
            PreviewPlay(Modifier.align(Alignment.CenterHorizontally))
        }

        1 -> Box(modifier = modifier.background(art)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.8f),
                                Color.Black.copy(alpha = 0.45f),
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(2) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.7f))
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                PreviewBar(0.7f, 5.dp, Color.White.copy(alpha = 0.9f))
                Spacer(Modifier.height(4.dp))
                PreviewBar(0.45f, 3.dp, Color.White.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                PreviewBar(1f, 2.dp, Color.White.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))
                PreviewPlay(Modifier.align(Alignment.CenterHorizontally))
            }
        }

        else -> Column(
            modifier = modifier.background(if (isDarkMode) Color(0xFF121212) else Color(0xFFF5F5F5))
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1.1f)
                    .background(art)
            ) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 5.dp)
                        .width(18.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.7f))
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.9f)
                    .padding(8.dp)
            ) {
                PreviewBar(0.7f, 4.dp, ink.copy(alpha = 0.8f))
                Spacer(Modifier.height(4.dp))
                PreviewBar(0.45f, 3.dp, ink.copy(alpha = 0.4f))
                Spacer(Modifier.height(8.dp))
                PreviewBar(1f, 2.dp, ink.copy(alpha = 0.25f))
                Spacer(Modifier.weight(1f))
                PreviewPlay(Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun PreviewBar(widthFraction: Float, height: Dp, color: Color) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

@Composable
private fun PreviewPlay(modifier: Modifier) {
    Box(
        modifier
            .width(18.dp)
            .height(12.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.Red)
    )
}
