package com.rkd.audiobasics.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.api.account.SyncPreview
import com.rkd.audiobasics.ui.theme.NothingFont
import com.rkd.audiobasics.utils.HapticUtils
import kotlinx.coroutines.launch

/**
 * Dev Tools entry for the account sync dry run. Reads the signed-in account's whole library
 * and reports what a real sync WOULD change. Read-only: nothing is written anywhere.
 */
@Composable
internal fun SyncPreviewRow(
    vm: MusicViewModel,
    isDarkMode: Boolean,
    hapticsEnabled: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<List<String>?>(null) }

    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)

    SettingsRow(
        isDarkMode = isDarkMode,
        title = "Account sync preview",
        subtitle = if (running) "Reading your library..." else "Read-only: compares your account with this device",
        icon = {
            Icon(
                Icons.Default.Sync,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        },
        onClick = {
            if (!running) {
                if (hapticsEnabled) HapticUtils.performSubtleHaptic(context)
                running = true
                scope.launch {
                    try {
                        report = SyncPreview.run(
                            localLikedIds = vm.likedSongs.value.map { it.id }.toSet(),
                            localAlbums = vm.savedAlbums.value
                        )
                    } finally {
                        running = false
                    }
                }
            }
        }
    )

    report?.let { lines ->
        AlertDialog(
            onDismissRequest = { report = null },
            containerColor = if (isDarkMode) Color(0xFF1E1E1E) else Color.White,
            title = {
                Text(
                    "Sync preview",
                    fontFamily = NothingFont,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            fontFamily = NothingFont,
                            fontSize = 12.sp,
                            color = if (line.contains("NOT READ")) Color(0xFFE57373) else subTextColor
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { report = null }) {
                    Text("Close", fontFamily = NothingFont, color = textColor)
                }
            }
        )
    }
}
