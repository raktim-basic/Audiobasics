package com.yt.lite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

@Composable
fun UpdaterScreen() {
    val scope = rememberCoroutineScope()

    var isChecking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf<String?>(null) }

    val currentVersion = "v0.28.3"

    // Fetch ALL releases including pre-releases, find highest version
    suspend fun fetchLatestVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val req = Request.Builder()
                .url("https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases?per_page=10")
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext null
            val arr = JSONArray(body)
            // Get the first (most recent) release tag
            if (arr.length() > 0) {
                arr.getJSONObject(0).getString("tag_name")
            } else null
        } catch (e: Exception) {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Player Ready",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Powered by NewPipe Extractor",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

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
                        "Current Version",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        currentVersion,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Latest Version",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        latestVersion ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            latestVersion == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            latestVersion == currentVersion -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Status",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        when {
                            latestVersion == null -> "Active"
                            latestVersion == currentVersion -> "Up to date ✓"
                            else -> "Update available"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            latestVersion == null -> MaterialTheme.colorScheme.primary
                            latestVersion == currentVersion -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                scope.launch {
                    isChecking = true
                    statusMessage = null
                    val v = fetchLatestVersion()
                    if (v != null) {
                        latestVersion = v
                        isError = false
                        statusMessage = if (v == currentVersion)
                            "You're on the latest version!"
                        else
                            "New version $v available! Update the app to get it."
                    } else {
                        isError = true
                        statusMessage = "Could not check for updates. Check your connection."
                    }
                    isChecking = false
                }
            },
            enabled = !isChecking,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(12.dp))
                Text("Checking...")
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Check for Updates")
            }
        }

        statusMessage?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = msg,
                color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "NewPipe Extractor handles YouTube stream decryption automatically. If songs stop playing, check here for updates.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
