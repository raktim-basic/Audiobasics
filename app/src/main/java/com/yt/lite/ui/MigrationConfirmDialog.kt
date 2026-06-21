package com.yt.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yt.lite.ui.theme.NothingFont
import com.yt.lite.utils.MigrationMessageProvider
import kotlinx.coroutines.delay

private const val PROCEED_COOLDOWN_SECONDS = 10

/**
 * Last-chance confirmation shown when the user taps "Download and update" on the
 * updater screen. Forces a short pause before Proceed becomes tappable so people
 * don't blindly tap through a breaking change.
 */
@Composable
fun MigrationConfirmDialog(
    onDismiss: () -> Unit,
    onExportLibrary: () -> Unit,
    onProceed: () -> Unit
) {
    var secondsLeft by remember { mutableIntStateOf(PROCEED_COOLDOWN_SECONDS) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
    }

    val canProceed = secondsLeft <= 0

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Row {
                    Text(text = "⚠️ ", fontSize = 18.sp)
                    Text(
                        text = MigrationMessageProvider.popupWarning(),
                        fontFamily = NothingFont,
                        fontSize = 16.sp,
                        color = Color.Black
                    )
                }

                Spacer(Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Export library",
                        fontFamily = NothingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFF1565C0),
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { onExportLibrary() }
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!canProceed) {
                            Text(
                                text = "${secondsLeft}s",
                                fontFamily = NothingFont,
                                fontSize = 14.sp,
                                color = Color(0xFF999999)
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            text = "Proceed",
                            fontFamily = NothingFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (canProceed) Color(0xFF1565C0) else Color(0xFFBBBBBB),
                            textDecoration = TextDecoration.Underline,
                            textAlign = TextAlign.End,
                            modifier = Modifier.clickable(enabled = canProceed) { onProceed() }
                        )
                    }
                }
            }
        }
    }
}
