package com.rkd.audiobasics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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

    MorphPopup(
        onDismissRequest = onDismiss,
        placement = MorphPlacement.Center,
        containerColor = bgColor
    ) { close ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            CustomSleepTimerContent(
                isDarkMode = isDarkMode,
                hapticsEnabled = hapticsEnabled,
                context = context,
                textColor = textColor,
                onCancel = close,
                onSet = { minutes ->
                    if (minutes <= 0L) onEndOfSong() else onCustom(minutes)
                }
            )
        }
    }
}

// One dot = 5 minutes; 36 dots = 3 hours, a sensible ceiling for a sleep timer. Selecting 0
// filled dots (below the first dot) means "End of the song" instead of a duration.
private const val MINUTES_PER_DOT = 5
private const val TOTAL_DOTS = 36
private const val DEFAULT_DOTS = 0 // End of the song, by default

/**
 * The scrubber screen — a 36-dot scrubber (5 minutes per dot, up to 180 minutes) that can also
 * be dragged/stepped all the way down to 0 filled dots for "End of the song", with +/- steppers
 * and drag-to-scrub, mirroring the player's own [DashedProgressBar] interaction (same drag
 * model, same per-step haptic pulse).
 */
@Composable
private fun CustomSleepTimerContent(
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    context: android.content.Context,
    textColor: Color,
    onCancel: () -> Unit,
    onSet: (Long) -> Unit
) {
    // Progress is tracked as a continuous 0f..1f fraction, exactly like DashedProgressBar's
    // `progress`/`dragProgress` — dot count is only ever derived from it for display/output,
    // never fed back in as the source of truth. Rounding a discrete dot count back into a
    // fraction every drag frame is what made the previous version feel broken.
    var progress by remember { mutableFloatStateOf(DEFAULT_DOTS.toFloat() / TOTAL_DOTS) }
    val selectedDots = (progress * TOTAL_DOTS).toInt().coerceIn(0, TOTAL_DOTS)
    val minutes = selectedDots * MINUTES_PER_DOT

    Column {
        Text(
            text = if (selectedDots == 0) "End of the song" else "Sleep timer : ${minutes}m",
            fontFamily = NothingFont,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = textColor
        )

        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            DotStepperButton(
                symbol = "–",
                enabled = selectedDots > 0,
                textColor = textColor,
                hapticsEnabled = hapticsEnabled,
                context = context,
                onStep = {
                    if (selectedDots > 0) {
                        progress = ((selectedDots - 1).toFloat() / TOTAL_DOTS).coerceAtLeast(0f)
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
                        progress = ((selectedDots + 1).toFloat() / TOTAL_DOTS).coerceAtMost(1f)
                        true
                    } else false
                }
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onCancel()
            }) {
                Text("Cancel", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = Color.Red)
            }
            TextButton(onClick = {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                onSet(minutes.toLong())
            }) {
                Text("Set", fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = Color.Red)
            }
        }
    }
}

/**
 * Simple +/- stepper button. One tap = one step (5 minutes), with a haptic pulse per tap.
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
