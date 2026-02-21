package com.yt.lite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yt.lite.api.SignatureExtractor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun UpdaterScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var isUpdating by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    var version by remember { mutableStateOf(SignatureExtractor.getVersion(ctx)) }
    var updatedAt by remember { mutableStateOf(SignatureExtractor.getUpdatedAt(ctx)) }
    var isReady by remember { mutableStateOf(SignatureExtractor.isReady(ctx)) }

    fun formatTime(ts: Long): String {
        if (ts == 0L) return "Never"
        val sdf = SimpleDateFormat("MMM dd, yyyy · hh:mm a", Locale.getDefault())
        return sdf.format(Date(ts))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Status icon
        Icon(
            imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = if (isReady) "Player Ready" else "Player Not Installed",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(32.dp))

        // Info card
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Version",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        version ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Last Updated",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatTime(updatedAt),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        // Update button
        Button(
            onClick = {
                scope.launch {
                    isUpdating = true
                    statusMessage = null
                    val result = SignatureExtractor.update(ctx)
                    result.fold(
                        onSuccess = { v ->
                            version = v
                            updatedAt = SignatureExtractor.getUpdatedAt(ctx)
                            isReady = true
                            isError = false
                            statusMessage = "Player updated successfully!"
                        },
                        onFailure = { e ->
                            isError = true
                            statusMessage = "Failed: ${e.message}"
                        }
                    )
                    isUpdating = false
                }
            },
            enabled = !isUpdating,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (isUpdating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(12.dp))
                Text("Updating...")
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Update Player")
            }
        }

        // Status message
        statusMessage?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = msg,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }

        if (!isReady) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Tap Update Player to enable music playback.\nThis only takes a few seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
