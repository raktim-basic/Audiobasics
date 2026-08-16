package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils

@Composable
fun SleepTimerDialog(
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onEndOfSong: () -> Unit,
    onCustom: (Long) -> Unit
) {
    val bgColor = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val textColor = if (isDarkMode) Color.White else Color.Black
    val surfaceColor = if (isDarkMode) Color(0xFF2A2A2A) else Color.White

    var showCustomTimer by remember { mutableStateOf(false) }

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
            if (showCustomTimer) {
                CustomSleepTimerContent(
                    isDarkMode = isDarkMode,
                    hapticsEnabled = hapticsEnabled,
                    context = context,
                    textColor = textColor,
                    onBack = {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        showCustomTimer = false
                    },
                    onSet = { minutes -> onCustom(minutes) }
                )
            } else {
                Column {
                    Text(
                        text = "Sleep timer",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = textColor
                    )

                    Spacer(Modifier.height(20.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(surfaceColor, RoundedCornerShape(8.dp))
                            .clickable {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                onEndOfSong()
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "End of this song",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(surfaceColor, RoundedCornerShape(8.dp))
                            .clickable {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                showCustomTimer = true
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Custom timer",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Red, RoundedCornerShape(8.dp))
                            .clickable {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                onDismiss()
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Cancel",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

// One dot = 5 minutes; 36 dots = 3 hours, a sensible ceiling for a sleep timer.
private const val MINUTES_PER_DOT = 5
private const val TOTAL_DOTS = 36
private const val DEFAULT_DOTS = 6 // 30 minutes

/**
 * The "Custom timer" screen within [SleepTimerDialog] — a 36-dot scrubber (5 minutes per dot,
 * up to 180 minutes), with +/- steppers and drag-to-scrub, mirroring the player's own
 * [DashedProgressBar] interaction (same drag model, same per-step haptic pulse).
 */
@Composable
private fun CustomSleepTimerContent(
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    textColor: Color,
    onBack: () -> Unit,
    onSet: (Long) -> Unit
) {
    var selectedDots by remember { mutableIntStateOf(DEFAULT_DOTS) }
    val minutes = selectedDots * MINUTES_PER_DOT

    Column {
        Text(
            text = "Sleep timer : ${minutes}m",
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = textColor
        )

        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            DotStepperButton(
                symbol = "–",
                enabled = selectedDots > 1,
                textColor = textColor,
                onClick = {
                    if (selectedDots > 1) {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        selectedDots--
                    }
                }
            )

            SleepTimerDotScrubber(
                totalDots = TOTAL_DOTS,
                selectedDots = selectedDots,
                onSelectedDotsChange = { selectedDots = it },
                hapticsEnabled = hapticsEnabled,
                context = context,
                isDarkMode = isDarkMode,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )

            DotStepperButton(
                symbol = "+",
                enabled = selectedDots < TOTAL_DOTS,
                textColor = textColor,
                onClick = {
                    if (selectedDots < TOTAL_DOTS) {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        selectedDots++
                    }
                }
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("Back", fontFamily = NothingFont, color = Color.Gray)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(Color.Red, RoundedCornerShape(8.dp))
                    .clickable {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onSet(minutes.toLong())
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    "Set",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun DotStepperButton(
    symbol: String,
    enabled: Boolean,
    textColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
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

/**
 * A row of [totalDots] dots, [selectedDots] of them filled from the left. Draggable/scrubable
 * the same way as [DashedProgressBar]: drag position maps to a dot count, with one haptic pulse
 * per dot crossed. Tapping a dot also jumps straight to that count.
 */
@Composable
private fun SleepTimerDotScrubber(
    totalDots: Int,
    selectedDots: Int,
    onSelectedDotsChange: (Int) -> Unit,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    var barWidthPx by remember { mutableStateOf(0f) }
    var dragDots by remember { mutableStateOf<Int?>(null) }
    var lastHapticDots by remember { mutableStateOf(selectedDots) }

    val displayDots = dragDots ?: selectedDots
    val unfilledColor = if (isDarkMode) Color(0xFF333333) else Color(0xFFBDBDBD)

    fun dotsFromFraction(fraction: Float): Int =
        (fraction.coerceIn(0f, 1f) * totalDots).toInt().coerceIn(1, totalDots)

    Row(
        modifier = modifier
            .height(24.dp)
            .onSizeChanged { barWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (barWidthPx > 0) {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            val d = dotsFromFraction(offset.x / barWidthPx)
                            dragDots = d
                            lastHapticDots = d
                            onSelectedDotsChange(d)
                        }
                    },
                    onDragEnd = { dragDots = null },
                    onDragCancel = { dragDots = null },
                    onHorizontalDrag = { _, dragAmount ->
                        if (barWidthPx > 0) {
                            val currentFraction = (dragDots ?: selectedDots).toFloat() / totalDots
                            val d = dotsFromFraction(currentFraction + dragAmount / barWidthPx)
                            dragDots = d
                            if (d != lastHapticDots) {
                                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                                lastHapticDots = d
                            }
                            onSelectedDotsChange(d)
                        }
                    }
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(totalDots) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(if (index < displayDots) Color.Red else unfilledColor)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onSelectedDotsChange(index + 1)
                    }
            )
        }
    }
}

