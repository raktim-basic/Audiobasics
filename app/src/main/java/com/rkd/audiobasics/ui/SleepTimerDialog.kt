package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import kotlinx.coroutines.delay
/**
 * The sleep timer entry dialog: "End of this song" / "Custom timer" / "Cancel".
 *
 * Deliberately standalone (not owned by QueueScreen) so it can be launched from anywhere the
 * player can be controlled from — the player dialog's overflow menu, the queue screen, etc. —
 * without those callers needing to navigate to the queue first.
 */
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
    // Progress is tracked as a continuous 0f..1f fraction, exactly like DashedProgressBar's
    // `progress`/`dragProgress` — dot count is only ever derived from it for display/output,
    // never fed back in as the source of truth. Rounding a discrete dot count back into a
    // fraction every drag frame is what made the previous version feel broken.
    var progress by remember { mutableFloatStateOf(DEFAULT_DOTS.toFloat() / TOTAL_DOTS) }
    val selectedDots = (progress * TOTAL_DOTS).toInt().coerceIn(1, TOTAL_DOTS)
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
                hapticsEnabled = hapticsEnabled,
                context = context,
                onStep = {
                    if (selectedDots > 1) {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        progress = ((selectedDots - 1).toFloat() / TOTAL_DOTS).coerceAtLeast(1f / TOTAL_DOTS)
                        true
                    } else false
                }
            )

            SleepTimerDotScrubber(
                totalDots = TOTAL_DOTS,
                progress = progress,
                onProgressChange = { progress = it },
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
                hapticsEnabled = hapticsEnabled,
                context = context,
                onStep = {
                    if (selectedDots < TOTAL_DOTS) {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        progress = ((selectedDots + 1).toFloat() / TOTAL_DOTS).coerceAtMost(1f)
                        true
                    } else false
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

/**
 * +/- stepper button. Tap steps once; press-and-hold repeats rapidly (150ms after an initial
 * 400ms delay) until released, each repeat firing the same haptic as a single tap/dot-cross.
 * [onStep] returns whether a step actually happened (false at the min/max clamp), so the repeat
 * loop can stop itself once it hits the end rather than continuing to fire no-op haptics.
 */
@Composable
private fun DotStepperButton(
    symbol: String,
    enabled: Boolean,
    textColor: Color,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    onStep: () -> Boolean
) {
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed, enabled) {
        if (isPressed && enabled) {
            delay(400) // initial delay before repeat kicks in, so a quick tap doesn't double-step
            while (isPressed) {
                val moved = onStep()
                if (!moved) break
                delay(150)
            }
        }
    }

    Box(
        modifier = Modifier
            .size(32.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onStep()
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
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

/**
 * A row of [totalDots] dots reflecting a continuous [progress] (0f..1f), draggable/scrubable
 * with the exact same gesture handling as the player's [DashedProgressBar]: a live [progress]
 * (or in-drag override) that only ever moves as a continuous fraction — dot count is derived
 * from it purely for rendering — with one haptic pulse per dot boundary crossed, fired from a
 * LaunchedEffect watching the derived dot index rather than from inside the drag callback.
 */
@Composable
private fun SleepTimerDotScrubber(
    totalDots: Int,
    progress: Float,
    onProgressChange: (Float) -> Unit,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    var barWidthPx by remember { mutableStateOf(0f) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }

    val displayProgress = dragProgress ?: progress
    val displayFilled = (displayProgress * totalDots).toInt().coerceIn(0, totalDots)
    var lastFilled by remember { mutableStateOf(displayFilled) }

    LaunchedEffect(displayFilled) {
        if (dragProgress != null && displayFilled != lastFilled && hapticsEnabled) {
            HapticUtils.performSubtleHaptic(context)
            lastFilled = displayFilled
        }
    }

    val unfilledColor = if (isDarkMode) Color(0xFF333333) else Color(0xFFBDBDBD)

    Row(
        modifier = modifier
            .height(24.dp)
            .onSizeChanged { barWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (barWidthPx > 0) {
                            if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                            dragProgress = (offset.x / barWidthPx).coerceIn(0f, 1f)
                            lastFilled = (dragProgress!! * totalDots).toInt().coerceIn(0, totalDots)
                            onProgressChange(dragProgress!!)
                        }
                    },
                    onDragEnd = {
                        dragProgress?.let { onProgressChange(it) }
                        dragProgress = null
                    },
                    onDragCancel = { dragProgress = null },
                    onHorizontalDrag = { _, dragAmount ->
                        if (barWidthPx > 0) {
                            val current = dragProgress ?: progress
                            dragProgress = (current + dragAmount / barWidthPx).coerceIn(0f, 1f)
                            onProgressChange(dragProgress!!)
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
                    .background(if (index < displayFilled) Color.Red else unfilledColor)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                        onProgressChange((index + 1).toFloat() / totalDots.toFloat())
                    }
            )
        }
    }
}
