package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

@Composable
fun TempoPitchDialog(
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    speed: Float,
    pitchSemitones: Int,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val iconColor = textColor.copy(alpha = 0.7f)

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
                    text = "Tempo and Pitch",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = textColor
                )

                Spacer(Modifier.height(24.dp))

                TempoPitchRow(
                    icon = Icons.Default.Speed,
                    iconColor = iconColor,
                    valueText = "x${formatSpeed(speed)}",
                    textColor = textColor,
                    hapticsEnabled = hapticsEnabled,
                    context = context,
                    canDecrement = speed > MusicViewModel.TEMPO_MIN + 0.001f,
                    canIncrement = speed < MusicViewModel.TEMPO_MAX - 0.001f,
                    onDecrement = {
                        onSpeedChange((speed - MusicViewModel.TEMPO_STEP).coerceAtLeast(MusicViewModel.TEMPO_MIN))
                    },
                    onIncrement = {
                        onSpeedChange((speed + MusicViewModel.TEMPO_STEP).coerceAtMost(MusicViewModel.TEMPO_MAX))
                    }
                )

                Spacer(Modifier.height(20.dp))

                TempoPitchRow(
                    icon = Icons.Default.Tune,
                    iconColor = iconColor,
                    valueText = pitchSemitones.toString(),
                    textColor = textColor,
                    hapticsEnabled = hapticsEnabled,
                    context = context,
                    canDecrement = pitchSemitones > MusicViewModel.PITCH_MIN,
                    canIncrement = pitchSemitones < MusicViewModel.PITCH_MAX,
                    onDecrement = {
                        onPitchChange((pitchSemitones - 1).coerceAtLeast(MusicViewModel.PITCH_MIN))
                    },
                    onIncrement = {
                        onPitchChange((pitchSemitones + 1).coerceAtMost(MusicViewModel.PITCH_MAX))
                    }
                )

                Spacer(Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onReset()
                    }) {
                        Text("Reset", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = Color.Red)
                    }
                    TextButton(onClick = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onDismiss()
                    }) {
                        Text("Done", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = Color.Red)
                    }
                }
            }
        }
    }
}

/** x1.0, x1.05, x1.15, etc — one decimal normally, two only when the step needs it. */
private fun formatSpeed(speed: Float): String {
    val rounded = Math.round(speed * 100)
    return if (rounded % 10 == 0) "%.1f".format(rounded / 100f) else "%.2f".format(rounded / 100f)
}

@Composable
private fun TempoPitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    valueText: String,
    textColor: Color,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))

        Spacer(Modifier.weight(1f))

        TempoPitchStepperButton(
            symbol = "–",
            enabled = canDecrement,
            textColor = textColor,
            hapticsEnabled = hapticsEnabled,
            context = context,
            onStep = onDecrement
        )

        Text(
            text = valueText,
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = textColor,
            modifier = Modifier.padding(horizontal = 20.dp).widthIn(min = 48.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        TempoPitchStepperButton(
            symbol = "+",
            enabled = canIncrement,
            textColor = textColor,
            hapticsEnabled = hapticsEnabled,
            context = context,
            onStep = onIncrement
        )
    }
}

/** One tap = one step. No long-press acceleration, by design. */
@Composable
private fun TempoPitchStepperButton(
    symbol: String,
    enabled: Boolean,
    textColor: Color,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onStep: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onStep()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = if (enabled) textColor else textColor.copy(alpha = 0.3f)
        )
    }
}
