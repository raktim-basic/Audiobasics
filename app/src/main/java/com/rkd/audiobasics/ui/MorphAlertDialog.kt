package com.rkd.audiobasics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.ui.theme.BodyFont
import com.rkd.audiobasics.utils.HapticUtils
import kotlinx.coroutines.launch

/**
 * The app's one popup for questions and messages. It is a drop-in replacement for Material's
 * `AlertDialog` (same title / text / confirmButton / dismissButton slots), but it is drawn by
 * the MorphOverlay instead of as a separate Android window, so it grows out of the screen like
 * every other popup, the background goes monochrome behind it, and it extends edge to edge.
 *
 * Buttons sit on the bottom row: [dismissButton] bottom-left, [confirmButton] bottom-right.
 * With no dismiss button the confirm button sits bottom-right on its own.
 *
 * [dismissOnOutsideTap] = false means a tap outside does nothing (back still closes it).
 * [isLocked] returning true blocks BOTH an outside tap and the back button (used while a
 * write is running, or for a question that must be answered).
 */
@Composable
fun MorphAlertDialog(
    onDismissRequest: () -> Unit,
    isDarkMode: Boolean,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    dismissOnOutsideTap: Boolean = true,
    isLocked: () -> Boolean = { false }
) {
    val container = if (isDarkMode) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    // The popup's content is created once, when it opens. Reading the slots through State
    // keeps it current when the caller recomposes with new text (e.g. a progress message).
    val titleSlot by rememberUpdatedState(title)
    val textSlot by rememberUpdatedState(text)
    val confirmSlot by rememberUpdatedState(confirmButton)
    val dismissSlot by rememberUpdatedState(dismissButton)

    MorphPopup(
        onDismissRequest = onDismissRequest,
        placement = MorphPlacement.Center,
        containerColor = container,
        dismissOnOutsideTap = dismissOnOutsideTap,
        isLocked = isLocked
    ) { _ ->
        val titleNow = titleSlot
        val textNow = textSlot
        val confirmNow = confirmSlot
        val dismissNow = dismissSlot
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            if (titleNow != null) {
                titleNow()
                if (textNow != null || confirmNow != null || dismissNow != null) Spacer(Modifier.height(8.dp))
            }
            if (textNow != null) {
                textNow()
                if (confirmNow != null || dismissNow != null) Spacer(Modifier.height(20.dp))
            }
            if (confirmNow != null || dismissNow != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (dismissNow != null) Arrangement.SpaceBetween else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dismissNow?.invoke()
                    confirmNow?.invoke()
                }
            }
        }
    }
}

/**
 * A yes/no question for a change that is written to the account first (so it can take a
 * moment, or fail).
 *
 * Tapping the confirm button swaps its label for a spinner and keeps the dialog open until
 * [onConfirm] returns. While the spinner is showing, a tap outside and the back button both do
 * nothing, and the cancel button is disabled. [onConfirm] returns true when the change was
 * made, which closes the dialog; false (offline, refused...) keeps it open so the person can
 * try again or cancel. The reason has already been shown to them by then.
 */
@Composable
fun MorphConfirmDialog(
    isDarkMode: Boolean,
    hapticsEnabled: Boolean,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: suspend () -> Boolean,
    title: String = "Are you sure?",
    cancelLabel: String = "No",
    confirmLabel: String = "Yes"
) {
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val textColor = if (isDarkMode) Color.White else Color.Black

    MorphAlertDialog(
        onDismissRequest = onDismiss,
        isDarkMode = isDarkMode,
        isLocked = { working },
        title = {
            Text(title, fontFamily = NothingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textColor)
        },
        text = {
            Text(message, fontFamily = BodyFont, color = textColor)
        },
        dismissButton = {
            TextButton(
                enabled = !working,
                onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    onDismiss()
                }
            ) {
                Text(cancelLabel, fontFamily = BodyFont, fontWeight = FontWeight.Bold, color = Color.Red)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working,
                onClick = {
                    if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                    scope.launch {
                        working = true
                        val done = try {
                            onConfirm()
                        } finally {
                            working = false
                        }
                        // A refused change keeps the dialog open; success closes it.
                        if (done) onDismiss()
                    }
                }
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.Red
                    )
                } else {
                    Text(confirmLabel, fontFamily = BodyFont, fontWeight = FontWeight.Bold, color = Color.Red)
                }
            }
        }
    )
}
